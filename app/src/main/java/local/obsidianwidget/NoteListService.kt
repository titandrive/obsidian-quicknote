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
    class Row(val image: ByteArray, val line: Int, val task: Boolean, val text: CharSequence?)

    /** One block laid out on the note's tall imaginary canvas, [top] to [top] + [height]. */
    private class Piece(val block: Block, val layout: StaticLayout?, val top: Int, val height: Int, val pad: Int)

    /** The note's rows and the palette they were drawn in, or null if the widget isn't set up. */
    fun build(ctx: Context, id: Int): Pair<List<Row>, Palette>? {
        val cfg = WidgetStore.load(ctx, id) ?: return null
        val p = cfg.palette(ctx)
        val text = Vault.read(ctx, cfg.uri)
        // The list's width: the widget minus its side padding.
        val width = (TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)).coerceAtLeast(1)
        val key = cacheKey(ctx, cfg, text, width)
        cached(ctx, id, key)?.let { return it to p }
        return render(ctx, cfg, p, text, width).also { save(ctx, id, key, it) } to p
    }

    private fun render(ctx: Context, cfg: WidgetConfig, p: Palette, text: String?, width: Int): List<Row> {
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, ctx.resources.displayMetrics).toInt()
        val blocks = if (text == null) {
            listOf(Block("Couldn't read ${cfg.notePath}. It may have been moved or renamed; tap the note's name to pick it again.", 0))
        } else {
            Markdown.render(text, p, indent)
        }
        val paint = TextBitmaps.paint(ctx, Fonts.typeface(cfg.fontFile), cfg.textSize, p.text)
        fun layout(t: CharSequence, w: Int) = StaticLayout.Builder.obtain(t, 0, t.length, paint, w.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(true).build()

        // Stack the blocks: code blocks get padding and a background, rules are a thin line.
        val pieces = ArrayList<Piece>()
        var y = 0
        for (b in blocks) {
            val piece = when {
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
        val radius = dp(ctx, 6).toFloat()
        val rows = (0 until count).map { i ->
            val top = i * strip
            val bmp = Bitmap.createBitmap(width, strip, Bitmap.Config.ARGB_8888)
            bmp.density = ctx.resources.displayMetrics.densityDpi
            val canvas = Canvas(bmp)
            canvas.translate(0f, -top.toFloat())
            for (piece in pieces) {
                if (piece.top + piece.height <= top || piece.top >= top + strip) continue
                canvas.save()
                canvas.translate(0f, piece.top.toFloat())
                when {
                    piece.block.rule -> canvas.drawRect(0f, dp(ctx, 8).toFloat(), width.toFloat(), dp(ctx, 9).toFloat(), rule)
                    piece.block.code -> {
                        canvas.drawRoundRect(0f, 0f, width.toFloat(), piece.height.toFloat(), radius, radius, codeBg)
                        canvas.translate(piece.pad.toFloat(), dp(ctx, 6).toFloat())
                        piece.layout!!.draw(canvas)
                    }
                    else -> piece.layout!!.draw(canvas)
                }
                canvas.restore()
            }
            // Taps go to the block under the strip's middle.
            val mid = top + strip / 2
            val under = pieces.firstOrNull { mid < it.top + it.height } ?: pieces.last()
            val firstOfBlock = under.top >= top
            Row(compress(bmp), under.block.line, under.block.task, if (firstOfBlock) under.block.text else null)
        }
        return rows
    }

    /*
     * Rendered rows are kept, in memory and in the cache folder, keyed by everything that goes
     * into them. The launcher asks for a widget's rows again whenever it redraws it (after an
     * unlock, or coming back from an app), often after the app was frozen or closed; rendering
     * them again left the widget blank for a moment.
     */
    private val memory = HashMap<Int, Pair<String, List<Row>>>()

    private fun cacheKey(ctx: Context, cfg: WidgetConfig, text: String?, width: Int): String {
        val res = ctx.resources
        // The app's install time stands in for its version, since rendering changes with it.
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val parts = listOf(
            cfg.toJson().toString(), text.orEmpty(), width, cfg.isDark(ctx),
            res.displayMetrics.densityDpi, res.configuration.fontScale, installed,
        ).joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(ctx: Context, id: Int) = File(ctx.cacheDir, "rows-$id.bin")

    private fun cached(ctx: Context, id: Int, key: String): List<Row>? = synchronized(memory) {
        memory[id]?.takeIf { it.first == key }?.second
            ?: runCatching {
                DataInputStream(cacheFile(ctx, id).inputStream().buffered()).use { input ->
                    if (input.readUTF() != key) return@use null
                    List(input.readInt()) {
                        val line = input.readInt()
                        val task = input.readBoolean()
                        val text = if (input.readBoolean()) input.readUTF() else null
                        val image = ByteArray(input.readInt()).also(input::readFully)
                        Row(image, line, task, text)
                    }
                }
            }.getOrNull()?.also { memory[id] = key to it }
    }

    private fun save(ctx: Context, id: Int, key: String, rows: List<Row>) = synchronized(memory) {
        memory[id] = key to rows
        runCatching {
            val file = cacheFile(ctx, id)
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
                }
            }
            tmp.renameTo(file)
        }
    }

    fun forget(ctx: Context, id: Int) = synchronized(memory) {
        memory.remove(id)
        cacheFile(ctx, id).delete()
    }

    fun view(ctx: Context, row: Row): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_item).apply {
        setImageViewIcon(R.id.item_img, Icon.createWithData(row.image, 0, row.image.size))
        row.text?.let { setContentDescription(R.id.item_img, it) }
        setOnClickFillInIntent(
            R.id.item,
            Intent().putExtra(EditorActivity.EXTRA_LINE, row.line).putExtra(EditorActivity.EXTRA_TOGGLE, row.task),
        )
    }

    /** Lossless WebP where available (about half the size of PNG for text). */
    private fun compress(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
        bmp.compress(format, 75, out)
        bmp.recycle()
        out.toByteArray()
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}

/** Serves the widget's rows one by one, for Android before 12 and notes too big to send at once. */
class NoteListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class Factory(val ctx: Context, val id: Int) : RemoteViewsFactory {
        private var rows: List<NoteRows.Row> = emptyList()

        override fun onDataSetChanged() {
            rows = NoteRows.build(ctx, id)?.first.orEmpty()
        }

        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.let { NoteRows.view(ctx, it) } ?: RemoteViews(ctx.packageName, R.layout.widget_item)

        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        // Rows only change when the note does, which resets the whole list.
        override fun hasStableIds() = true
        override fun onCreate() = Unit
        override fun onDestroy() = Unit
    }
}
