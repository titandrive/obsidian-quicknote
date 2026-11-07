package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The rendered rows of a widget's note. On Android 12+ they're handed to the launcher all at once
 * with the widget (see [NoteWidgetProvider.update]); otherwise [NoteListService] serves them one by one.
 */
object NoteRows {
    /**
     * One strip of the note. Every strip is the same height, one line of body text, so the
     * placeholders a launcher shows while fetching rows are exactly the right size and nothing
     * shifts as real rows arrive. [image] is the strip compressed (a few KB); [line] is the note
     * line it shows, and [task] whether that line is a task, for taps.
     */
    class Row(
        val image: ByteArray, val line: Int, val task: Boolean, val text: CharSequence?,
        /** The foldable line whose arrow is in this strip, or -1; and whether it's folded. */
        val fold: Int = -1, val folded: Boolean = false,
        /** For a task, its line as written, so a tick can find it if the note changed since. */
        val source: String? = null,
        /** For a strip beside a task's checkbox, the box's middle, in px across the strip; else -1. */
        val boxX: Int = -1,
    )

    /** One block laid out on the note's tall imaginary canvas, [top] to [top] + [height]. */
    private class Piece(
        val block: Block, val layout: StaticLayout?, val top: Int, val height: Int, val pad: Int,
        val table: TableLayout? = null,
        /** Where the text is drawn within the piece (callouts are padded). */
        val textX: Int = 0, val textTop: Int = 0,
    )

    /** A table laid out: column lefts and widths, row tops and heights, and each cell's text. */
    private class TableLayout(val xs: IntArray, val widths: IntArray, val ys: IntArray, val heights: IntArray, val cells: List<List<StaticLayout>>)

    /**
     * A note's rows. When the widget follows the phone's dark mode (Android 12+), [night] holds the
     * same rows drawn dark, and [rows] light; the launcher picks between them.
     */
    class Built(val rows: List<Row>, val night: List<Row>?)

    /** Whether the widget carries both a light and a dark look, for the launcher to switch between. */
    fun dayAndNight(cfg: WidgetConfig) = cfg.followsSystem && android.os.Build.VERSION.SDK_INT >= 31

    /** The note's rows, or null if the widget isn't set up; with [unfolded], ignoring folds. */
    fun build(ctx: Context, id: Int, unfolded: Boolean = false): Built? = build(ctx, id, unfolded, null)

    /**
     * How big widget [id]'s rows are with nothing folded, which decides how they reach the
     * launcher, so folding never switches that. With notes to swap between it's the biggest of
     * them, so swapping never switches it either: a switch has the launcher rebuild the whole list,
     * which hitched quick taps through the notes.
     */
    fun wholeSize(ctx: Context, id: Int): Int {
        val cfg = WidgetStore.load(ctx, id) ?: return Int.MAX_VALUE
        val notes = if (cfg.swaps) cfg.swapNotes.map { cfg.copy(noteUri = it.uri, notePath = it.path) } else listOf(cfg)
        return notes.maxOf { noteSize(ctx, id, it) }
    }

    /** How big [cfg]'s note is drawn whole in widget [id]; remembered per note, so a fold doesn't draw it again just to measure it. */
    private fun noteSize(ctx: Context, id: Int, cfg: WidgetConfig): Int {
        val text = Vault.recent(ctx, cfg.uri)
        val width = (TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)).coerceAtLeast(1)
        val key = cacheKey(ctx, cfg, text, width, false)
        val slot = "$id-${cfg.noteUri.hashCode().toUInt()}"
        val memo = ctx.getSharedPreferences("routes", Context.MODE_PRIVATE)
        if (memo.getString("sizeKey$slot", null) == key) return memo.getInt("size$slot", Int.MAX_VALUE)
        val whole = build(ctx, id, true, text, cfg) ?: return Int.MAX_VALUE
        val size = whole.rows.sumOf { it.image.size } + whole.night.orEmpty().sumOf { it.image.size }
        memo.edit().putString("sizeKey$slot", key).putInt("size$slot", size).apply()
        return size
    }

    /** Widget [id]'s rows, for its note or the one in [given]. */
    private fun build(ctx: Context, id: Int, unfolded: Boolean, known: String?, given: WidgetConfig? = null): Built? {
        val cfg = given ?: WidgetStore.load(ctx, id) ?: return null
        // As last read, so a slow storage provider never holds up the widget; changed, it's redrawn.
        val text = known ?: Vault.recent(ctx, cfg.uri) { NoteWidgetProvider.update(ctx.applicationContext, AppWidgetManager.getInstance(ctx), id) }
        // The list's width: the widget minus its side padding.
        val width = (TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)).coerceAtLeast(1)
        val regions = text?.let(Folds::regions).orEmpty()
        val folded = if (unfolded) emptySet() else text?.let { Folds.folded(ctx, id, it) }.orEmpty()
        fun rows(dark: Boolean): List<Row> {
            val key = cacheKey(ctx, cfg, text, width, dark) + "|" + folded.sorted()
            return cached(ctx, id, dark, key, cfg.noteUri)
                ?: render(ctx, cfg, cfg.palette(ctx, dark), text, width, regions, folded).also { save(ctx, id, dark, key, cfg.noteUri, it) }
        }
        if (!dayAndNight(cfg)) return Built(rows(cfg.isDark(ctx)), null)
        val day = rows(false)
        // Only colours differ, so the two always line up row for row.
        return Built(day, rows(true).takeIf { it.size == day.size })
    }

    /** For the debug render probe: [text] drawn as widget [id] would draw its note. */
    internal fun renderForProbe(ctx: Context, id: Int, text: String): List<Row> {
        val cfg = WidgetStore.load(ctx, id)!!
        return render(ctx, cfg, cfg.palette(ctx), text, (TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)).coerceAtLeast(1), emptyMap(), emptySet())
    }

    private fun render(
        ctx: Context, cfg: WidgetConfig, p: Palette, text: String?, fullWidth: Int,
        regions: Map<Int, Folds.Region>, folded: Set<Int>,
    ): List<Row> {
        // A gutter for fold arrows, as in Obsidian, only when the note has something to fold.
        val gutter = if (regions.isEmpty()) 0 else dp(ctx, 18)
        val width = fullWidth - gutter
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, ctx.resources.displayMetrics).toInt()
        val sourceLines = text?.split('\n')
        val blocks = if (text == null) {
            listOf(Block("Couldn't read ${cfg.notePath}. It may have been moved or renamed; tap the note's name to pick it again.", 0))
        } else {
            Markdown.render(text, p, indent, regions, folded)
        }
        val paint = TextBitmaps.paint(ctx, Fonts.typeface(cfg.fontFile), cfg.textSize, p.text)
        fun layout(t: CharSequence, w: Int) = StaticLayout.Builder.obtain(t, 0, t.length, paint, w.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(true).build()

        // Callouts are boxes padded as Obsidian pads them (12px around, 24px at the left, in CSS
        // pixels for 16px text), scaled to the widget's text size.
        val css = paint.textSize / 16f
        val boxPad = (12 * css).toInt()
        val boxLeft = (24 * css).toInt()
        val iconSize = (18 * css).toInt()
        val iconGap = (4 * css).toInt()

        // Stack the blocks: code blocks get padding and a background, rules are a thin line.
        val pieces = ArrayList<Piece>()
        var y = 0
        for (b in blocks) {
            val callout = b.callout
            val piece = when {
                callout != null -> {
                    val x = boxLeft + if (callout.icon != null) iconSize + iconGap else 0
                    val l = layout(b.text, width - x - boxPad)
                    val above = if (callout.icon != null) boxPad else 0
                    Piece(b, l, y, above + l.height + if (callout.last) boxPad else 0, 0, textX = x, textTop = above)
                }
                b.table != null -> layoutTable(ctx, b.table, paint, width).let { Piece(b, null, y, it.ys.last() + it.heights.last() + 1, 0, it) }
                b.rule -> Piece(b, null, y, dp(ctx, 17), 0)
                b.code -> {
                    val pad = dp(ctx, 8)
                    val l = layout(b.text, width - 2 * pad)
                    Piece(b, l, y, l.height + 2 * dp(ctx, 6), pad)
                }
                else -> layout(b.text, width).let { Piece(b, it, y, it.height, 0) }
            }
            pieces.add(piece)
            y += piece.height
        }

        val strip = layout("Ag", width).height.coerceAtLeast(1)
        val count = (y + strip - 1) / strip
        val rule = Paint().apply { color = p.faint }
        val codeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.codeBg }
        val headerBg = Paint().apply { color = p.backgroundAlt }
        val radius = dp(ctx, 6).toFloat()
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted }
        // Each callout's box: from its title's top to its last line's bottom.
        class Box(val top: Int, val bottom: Int, val color: Int)
        val boxes = ArrayList<Box>()
        var boxTop = -1
        for (piece in pieces) {
            val c = piece.block.callout ?: continue
            if (c.icon != null || boxTop < 0) boxTop = piece.top
            if (c.last) {
                boxes.add(Box(boxTop, piece.top + piece.height, c.color))
                boxTop = -1
            }
        }
        val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
        val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, p.calloutBorder * css)
        }
        val boxRadius = p.calloutRadius * css
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val rows = (0 until count).map { i ->
            val top = i * strip
            val bmp = Bitmap.createBitmap(fullWidth, strip, Bitmap.Config.ARGB_8888)
            bmp.density = ctx.resources.displayMetrics.densityDpi
            val canvas = Canvas(bmp)
            canvas.translate(gutter.toFloat(), -top.toFloat())
            // Callout boxes go under their text: the callout's colour at 10%, as in Obsidian.
            for (box in boxes) {
                if (box.bottom <= top || box.top >= top + strip) continue
                boxFill.color = (box.color and 0xFFFFFF) or (0x1A shl 24)
                canvas.drawRoundRect(0f, box.top.toFloat(), width.toFloat(), box.bottom.toFloat(), boxRadius, boxRadius, boxFill)
                if (p.calloutBorder > 0f) {
                    boxBorder.color = (box.color and 0xFFFFFF) or ((p.calloutBorderOpacity * 255).toInt().coerceIn(0, 255) shl 24)
                    val inset = boxBorder.strokeWidth / 2
                    canvas.drawRoundRect(inset, box.top + inset, width - inset, box.bottom - inset, boxRadius, boxRadius, boxBorder)
                }
            }
            for (piece in pieces) {
                if (piece.top + piece.height <= top || piece.top >= top + strip) continue
                canvas.save()
                canvas.translate(0f, piece.top.toFloat())
                when {
                    piece.table != null -> drawTable(ctx, canvas, piece.table, rule, headerBg)
                    piece.block.rule -> canvas.drawRect(0f, dp(ctx, 8).toFloat(), width.toFloat(), dp(ctx, 9).toFloat(), rule)
                    piece.block.code -> {
                        canvas.drawRoundRect(0f, 0f, width.toFloat(), piece.height.toFloat(), radius, radius, codeBg)
                        canvas.translate(piece.pad.toFloat(), dp(ctx, 6).toFloat())
                        piece.layout!!.draw(canvas)
                    }
                    else -> {
                        val c = piece.block.callout
                        val icon = c?.icon
                        if (icon != null) {
                            // The type's icon, level with the title's first line.
                            val l = piece.layout!!
                            val mid = piece.textTop + (l.getLineTop(0) + l.getLineBottom(0)) / 2f
                            canvas.save()
                            canvas.translate(boxLeft.toFloat(), mid - iconSize / 2f)
                            canvas.scale(iconSize / 24f, iconSize / 24f)
                            iconPaint.color = c.color
                            iconPaint.strokeWidth = 2f
                            canvas.drawPath(Callouts.icon(icon), iconPaint)
                            canvas.restore()
                        }
                        canvas.translate(piece.textX.toFloat(), piece.textTop.toFloat())
                        piece.layout!!.draw(canvas)
                    }
                }
                canvas.restore()
            }
            // Fold arrows in the gutter, beside each foldable line's first line of text.
            // An arrow can straddle two strips (big headings put it on the border), so each strip it
            // overlaps draws its part. Every strip beside the foldable line's first row of text takes
            // taps for it, so a big heading's arrow is as easy to hit as its text is tall.
            // When two lines' rows both reach into a strip (a big heading's spills into the next
            // line's), the strip goes to the one covering more of it.
            var fold: Piece? = null
            var foldOverlap = 0
            val r = dp(ctx, 4)
            for (piece in pieces) {
                if (piece.block.fold == Block.NONE) continue
                val y = arrowY(piece)
                if (y + r >= top && y - r < top + strip) {
                    drawArrow(canvas, -gutter / 2f, y.toFloat(), r.toFloat(), piece.block.fold == Block.FOLDED, arrow)
                }
                val (rowTop, rowBottom) = firstRow(piece)
                val overlap = minOf(rowBottom, top + strip) - maxOf(rowTop, top)
                if (overlap > foldOverlap) {
                    fold = piece
                    foldOverlap = overlap
                }
            }
            // Taps go to the block under the strip's middle; but a task reaching a third of the way
            // into the strip takes it, since strips don't line up with lines, and a tap on a task's
            // top or bottom edge went to the line beside it.
            val mid = top + strip / 2
            val middle = pieces.firstOrNull { mid < it.top + it.height } ?: pieces.last()
            val under = if (middle.block.task) middle else pieces.filter { it.block.task }
                .maxByOrNull { minOf(it.top + it.height, top + strip) - maxOf(it.top, top) }
                ?.takeIf { minOf(it.top + it.height, top + strip) - maxOf(it.top, top) >= strip / 3 } ?: middle
            // A task's checkbox ("☐ ", its first character) sits on its first row of text.
            val boxX = under.layout?.takeIf { under.block.task }?.let { l ->
                val (rowTop, rowBottom) = firstRow(under)
                if (rowBottom <= top || rowTop >= top + strip) -1
                else gutter + under.textX + ((l.getPrimaryHorizontal(0) + l.getPrimaryHorizontal(1)) / 2).toInt()
            } ?: -1
            Row(
                compress(bmp), under.block.line, under.block.task, spoken(pieces, top, top + strip),
                fold?.block?.line ?: -1, fold?.block?.fold == Block.FOLDED,
                if (under.block.task) sourceLines?.getOrNull(under.block.line)?.removeSuffix("\r") else null,
                boxX,
            )
        }
        return rows
    }

    /** The top and bottom of a block's first row of text on the note's canvas. */
    private fun firstRow(piece: Piece): Pair<Int, Int> {
        val l = piece.layout ?: return piece.top to piece.top + piece.height
        return piece.top + piece.textTop + l.getLineTop(0) to piece.top + piece.textTop + l.getLineBottom(0)
    }

    /** Where a foldable block's arrow goes: level with the middle of its first line. */
    private fun arrowY(piece: Piece): Int {
        val l = piece.layout ?: return piece.top + piece.height / 2
        return piece.top + piece.textTop + (l.getLineTop(0) + l.getLineBottom(0)) / 2
    }

    /** Obsidian's fold arrow: pointing down when open, right when folded. */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, folded: Boolean, paint: Paint) {
        val path = android.graphics.Path()
        if (folded) {
            path.moveTo(cx - r * 0.6f, cy - r); path.lineTo(cx + r * 0.8f, cy); path.lineTo(cx - r * 0.6f, cy + r)
        } else {
            path.moveTo(cx - r, cy - r * 0.6f); path.lineTo(cx + r, cy - r * 0.6f); path.lineTo(cx, cy + r * 0.8f)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * What a screen reader says for the strip from [top] to [bottom]: the text of every line (or
     * table row) whose middle falls in it, so each line is read once, by the strip that shows it.
     */
    private fun spoken(pieces: List<Piece>, top: Int, bottom: Int): String? {
        val parts = ArrayList<String>()
        for (piece in pieces) {
            if (piece.top + piece.height <= top || piece.top >= bottom) continue
            val table = piece.table
            val layout = piece.layout
            if (table != null) {
                table.ys.forEachIndexed { r, rowTop ->
                    val mid = piece.top + rowTop + table.heights[r] / 2
                    if (mid in top until bottom) parts.add(table.cells[r].joinToString(", ") { it.text.toString().trim() })
                }
            } else if (layout != null) {
                val offset = piece.top + piece.textTop + if (piece.block.code) piece.pad else 0
                for (l in 0 until layout.lineCount) {
                    val mid = offset + (layout.getLineTop(l) + layout.getLineBottom(l)) / 2
                    if (mid in top until bottom) parts.add(layout.text.subSequence(layout.getLineStart(l), layout.getLineEnd(l)).toString().trim())
                }
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /**
     * Sizes a table's columns to their contents, then, if that's wider than the widget, narrows
     * the widest ones until it fits, wrapping their text.
     */
    private fun layoutTable(ctx: Context, t: Table, paint: android.text.TextPaint, width: Int): TableLayout {
        val pad = dp(ctx, 6)
        val columns = t.align.size
        val natural = IntArray(columns) { c ->
            t.rows.maxOf { row -> kotlin.math.ceil(Layout.getDesiredWidth(row[c], paint)).toInt() } + 2 * pad
        }
        val widths = natural.copyOf()
        val minWidth = 3 * pad + paint.textSize.toInt()
        while (widths.sum() > width - 1) {
            val widest = widths.indices.maxBy { widths[it] }
            if (widths[widest] <= minWidth) break
            widths[widest] = maxOf(minWidth, widths[widest] - maxOf(1, (widths.sum() - width + 1).coerceAtMost(widths[widest] / 4)))
        }
        val xs = IntArray(columns).also { for (c in 1 until columns) it[c] = it[c - 1] + widths[c - 1] }
        val cells = t.rows.map { row ->
            row.mapIndexed { c, cell ->
                StaticLayout.Builder.obtain(cell, 0, cell.length, paint, (widths[c] - 2 * pad).coerceAtLeast(1))
                    .setAlignment(t.align[c]).setLineSpacing(0f, 1.15f).setIncludePad(true).build()
            }
        }
        val heights = IntArray(cells.size) { r -> cells[r].maxOf { it.height } + 2 * pad / 2 }
        val ys = IntArray(cells.size).also { for (r in 1 until cells.size) it[r] = it[r - 1] + heights[r - 1] }
        return TableLayout(xs, widths, ys, heights, cells)
    }

    private fun drawTable(ctx: Context, canvas: Canvas, t: TableLayout, line: Paint, headerBg: Paint) {
        val pad = dp(ctx, 6)
        val right = (t.xs.last() + t.widths.last()).toFloat()
        val bottom = (t.ys.last() + t.heights.last()).toFloat()
        canvas.drawRect(0f, 0f, right, t.heights[0].toFloat(), headerBg)
        t.cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell ->
                canvas.save()
                canvas.translate((t.xs[c] + pad).toFloat(), (t.ys[r] + pad / 2).toFloat())
                cell.draw(canvas)
                canvas.restore()
            }
        }
        // The grid, one pixel wide, in the theme's faint colour.
        for (r in t.ys) canvas.drawRect(0f, r.toFloat(), right, r + 1f, line)
        canvas.drawRect(0f, bottom, right, bottom + 1f, line)
        for (x in t.xs) canvas.drawRect(x.toFloat(), 0f, x + 1f, bottom + 1f, line)
        canvas.drawRect(right - 1f, 0f, right, bottom + 1f, line)
    }

    /*
     * Rendered rows are kept, in memory and in the cache folder, keyed by everything that goes
     * into them. The launcher asks for a widget's rows again whenever it redraws it (after an
     * unlock, or coming back from an app), often after the app was frozen or closed; rendering
     * them again left the widget blank for a moment. Memory holds a few versions per widget,
     * so folding something and unfolding it again is instant.
     */
    private val memory = object : LinkedHashMap<String, List<Row>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Row>>?) = size > 8
    }

    private fun cacheKey(ctx: Context, cfg: WidgetConfig, text: String?, width: Int, dark: Boolean): String {
        val res = ctx.resources
        // The app's install time stands in for its version, since rendering changes with it.
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val parts = listOf(
            cfg.toJson().toString(), text.orEmpty(), width, dark,
            res.displayMetrics.densityDpi, res.configuration.fontScale, installed,
        ).joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * A widget's rows for one note, so switching between notes after the app was closed finds both
     * still drawn rather than drawing the other one afresh.
     */
    private fun cacheFile(ctx: Context, id: Int, dark: Boolean, note: String) =
        File(ctx.cacheDir, "rows-$id-${if (dark) "dark" else "light"}-${note.hashCode().toUInt()}.bin")

    private fun cached(ctx: Context, id: Int, dark: Boolean, key: String, note: String): List<Row>? = synchronized(memory) {
        memory["$id/$dark/$key"]
            ?: runCatching {
                DataInputStream(cacheFile(ctx, id, dark, note).inputStream().buffered()).use { input ->
                    if (input.readUTF() != key) return@use null
                    List(input.readInt()) {
                        val line = input.readInt()
                        val task = input.readBoolean()
                        val text = if (input.readBoolean()) input.readUTF() else null
                        val image = ByteArray(input.readInt()).also(input::readFully)
                        val fold = input.readInt()
                        val folded = input.readBoolean()
                        val source = if (input.readBoolean()) input.readUTF() else null
                        Row(image, line, task, text, fold, folded, source, input.readInt())
                    }
                }
            }.getOrNull()?.also { memory["$id/$dark/$key"] = it }
    }

    private fun save(ctx: Context, id: Int, dark: Boolean, key: String, note: String, rows: List<Row>) = synchronized(memory) {
        memory["$id/$dark/$key"] = rows
        runCatching {
            val file = cacheFile(ctx, id, dark, note)
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeUTF(key)
                out.writeInt(rows.size)
                for (r in rows) {
                    out.writeInt(r.line)
                    out.writeBoolean(r.task)
                    out.writeBoolean(r.text != null)
                    // writeUTF takes at most 64 KB; this only feeds screen readers.
                    r.text?.let { out.writeUTF(it.toString().take(8000)) }
                    out.writeInt(r.image.size)
                    out.write(r.image)
                    out.writeInt(r.fold)
                    out.writeBoolean(r.folded)
                    out.writeBoolean(r.source != null)
                    r.source?.let { out.writeUTF(it.take(8000)) }
                    out.writeInt(r.boxX)
                }
            }
            tmp.renameTo(file)
            // Kept for the last few notes shown (two notes, light and dark), not every note ever picked.
            ctx.cacheDir.listFiles { f -> f.name.startsWith("rows-$id-") }
                ?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        }
    }

    fun forget(ctx: Context, id: Int) = synchronized(memory) {
        for (dark in listOf(false, true)) {
            memory.keys.removeAll { it.startsWith("$id/$dark/") }
            ctx.cacheDir.listFiles { f -> f.name.startsWith("rows-$id-") }?.forEach { it.delete() }
        }
    }

    /**
     * One row's view; with [night], the launcher shows that one instead while in dark mode. In
     * [reading] mode a tap anywhere on a task ticks it, and anywhere on a foldable line folds it;
     * otherwise only the checkbox ticks and the arrow folds, and the rest of the line opens the editor there.
     */
    fun view(ctx: Context, row: Row, night: Row? = null, reading: Boolean = false): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_item).apply {
        val day = Icon.createWithData(row.image, 0, row.image.size)
        if (night != null && android.os.Build.VERSION.SDK_INT >= 31) {
            setIcon(R.id.item_img, "setImageIcon", day, Icon.createWithData(night.image, 0, night.image.size))
        } else {
            setImageViewIcon(R.id.item_img, day)
        }
        row.text?.let { setContentDescription(R.id.item_img, it) }
        setOnClickFillInIntent(
            R.id.item,
            // In reading mode a tap anywhere on a foldable line folds it (a task's ticks it instead).
            if (reading && !row.task && row.fold >= 0) Intent().putExtra(TapActivity.EXTRA_FOLD, row.fold)
            else Intent().putExtra(EditorActivity.EXTRA_LINE, row.line).putExtra(EditorActivity.EXTRA_TOGGLE, row.task && reading)
                .putExtra(EditorActivity.EXTRA_SOURCE, row.source),
        )
        if (row.task && !reading && row.boxX >= 0) {
            // A target the size of the fold arrows' over the box, clear of the fold arrow's gutter.
            val half = (22 * ctx.resources.displayMetrics.density).toInt()
            val gutter = if (row.fold >= 0) (18 * ctx.resources.displayMetrics.density).toInt() else 0
            setViewVisibility(R.id.task_hit_box, android.view.View.VISIBLE)
            setViewPadding(R.id.task_hit_box, maxOf(gutter, row.boxX - half), 0, 0, 0)
            setContentDescription(R.id.task_hit, "Tick")
            setOnClickFillInIntent(
                R.id.task_hit,
                Intent().putExtra(EditorActivity.EXTRA_LINE, row.line).putExtra(EditorActivity.EXTRA_TOGGLE, true)
                    .putExtra(EditorActivity.EXTRA_SOURCE, row.source),
            )
        } else {
            setViewVisibility(R.id.task_hit_box, android.view.View.GONE)
        }
        // The gutter over a fold arrow folds or unfolds, rather than opening the editor.
        if (row.fold >= 0) {
            setViewVisibility(R.id.fold_hit, android.view.View.VISIBLE)
            setContentDescription(R.id.fold_hit, if (row.folded) "Unfold" else "Fold")
            setOnClickFillInIntent(R.id.fold_hit, Intent().putExtra(TapActivity.EXTRA_FOLD, row.fold))
        } else {
            setViewVisibility(R.id.fold_hit, android.view.View.GONE)
        }
    }

    /**
     * Stable ids from what each row shows, so rows that didn't change (say, everything above a
     * fold just toggled) keep their id and the launcher can keep them rather than reload them.
     * Identical rows (blank lines) are told apart by how many came before.
     */
    fun ids(rows: List<Row>): LongArray {
        val seen = HashMap<Int, Int>()
        return LongArray(rows.size) { i ->
            val r = rows[i]
            val hash = 31 * r.image.contentHashCode() + r.fold * 2 + (if (r.folded) 1 else 0)
            val n = seen.merge(hash, 1, Int::plus)!!
            (hash.toLong() shl 20) xor n.toLong()
        }
    }

    /**
     * A see-through stand-in for rows still loading, the same height as a real one, in place of
     * Android's "Loading…" text, which flashed down the side of the list after a fold.
     */
    fun placeholder(ctx: Context, row: Row?): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_item)
        val size = row?.let {
            android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { o -> android.graphics.BitmapFactory.decodeByteArray(it.image, 0, it.image.size, o) }
        }
        if (size != null && size.outWidth > 0) {
            // Same shape as a strip, so it scales to exactly a strip's height; small, since it's empty.
            val scale = 8
            val bmp = Bitmap.createBitmap(maxOf(1, size.outWidth / scale), maxOf(1, size.outHeight / scale), Bitmap.Config.ALPHA_8)
            views.setImageViewBitmap(R.id.item_img, bmp)
        }
        views.setViewVisibility(R.id.fold_hit, android.view.View.GONE)
        views.setViewVisibility(R.id.task_hit_box, android.view.View.GONE)
        return views
    }

    /** Lossless WebP where available (about half the size of PNG for text). */
    private fun compress(bmp: Bitmap): ByteArray {
        // A fold redraws the whole note, but most strips come out the same as before (everything
        // above it, and below it when whole lines were hidden); those reuse their bytes.
        val pixels = java.nio.ByteBuffer.allocate(bmp.byteCount).also { bmp.copyPixelsToBuffer(it) }.array()
        val crc = java.util.zip.CRC32().apply { update(pixels) }.value
        val hash = (crc shl 32) xor (pixels.contentHashCode().toLong() and 0xffffffffL) xor (bmp.width.toLong() * 31 + bmp.height)
        synchronized(strips) { strips[hash] }?.let { bmp.recycle(); return it }
        val bytes = ByteArrayOutputStream().use { out ->
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            bmp.compress(format, 75, out)
            out.toByteArray()
        }
        bmp.recycle()
        synchronized(strips) { strips[hash] = bytes }
        return bytes
    }

    /** Compressed strips by their pixels, for [compress]. */
    private val strips = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 1500
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}

/** Serves the widget's rows one by one, for Android before 12 and notes too big to send at once. */
class NoteListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class Factory(val ctx: Context, val id: Int) : RemoteViewsFactory {
        private var rows: List<NoteRows.Row> = emptyList()
        private var night: List<NoteRows.Row>? = null
        private var ids = LongArray(0)
        private var reading = false

        override fun onDataSetChanged() {
            reading = WidgetStore.load(ctx, id)?.readOnly == true
            val built = NoteRows.build(ctx, id)
            rows = built?.rows.orEmpty()
            night = built?.night
            ids = NoteRows.ids(rows)
        }

        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.let { NoteRows.view(ctx, it, night?.getOrNull(position), reading) }
                ?: RemoteViews(ctx.packageName, R.layout.widget_item)

        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews = NoteRows.placeholder(ctx, rows.firstOrNull())
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = ids.getOrElse(position) { position.toLong() }
        // Stable ids made the list show rows from further down for a frame after a fold.
        override fun hasStableIds() = false
        override fun onCreate() = Unit
        override fun onDestroy() = Unit
    }
}
