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
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, ctx.resources.displayMetrics).toInt()
        val blocks = if (text == null) {
            listOf(Block("Couldn't read ${cfg.notePath}. It may have been moved or renamed; tap the note's name to pick it again.", 0))
        } else {
            Markdown.render(text, p, indent)
        }
        val paint = TextBitmaps.paint(ctx, Fonts.typeface(cfg.fontFile), cfg.textSize, p.text)
        // The list's width: the widget minus its side padding.
        val width = (TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)).coerceAtLeast(1)
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
        return rows to p
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
