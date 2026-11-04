package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.io.ByteArrayOutputStream

/**
 * The rendered rows of a widget's note. On Android 12+ they're handed to the launcher all at once
 * with the widget (see [NoteWidgetProvider.update]); otherwise [NoteListService] serves them one by one.
 */
object NoteRows {
    /**
     * One strip of a block; a tall block is several, and only the first and last get the code block's padding.
     * [png] is the strip as a compressed image, about 5 KB where the raw bitmap is about 170 KB.
     */
    class Row(val block: Block, val png: ByteArray?, val first: Boolean, val last: Boolean)

    /** The note's rows and the palette they were drawn in, or null if the widget isn't set up. */
    fun build(ctx: Context, id: Int): Pair<List<Row>, Palette>? {
        val cfg = WidgetStore.load(ctx, id) ?: return null
        val palette = cfg.palette(ctx)
        val text = Vault.read(ctx, cfg.uri)
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, ctx.resources.displayMetrics).toInt()
        val blocks = if (text == null) {
            listOf(Block("Couldn't read ${cfg.notePath}. It may have been moved or renamed; tap the note's name to pick it again.", 0))
        } else {
            Markdown.render(text, palette, indent)
        }
        val paint = TextBitmaps.paint(ctx, Fonts.typeface(cfg.fontFile), cfg.textSize, palette.text)
        // The widget minus its side padding.
        val width = TextBitmaps.widgetWidth(ctx, id) - dp(ctx, 14 + 6 + 8)
        val rows = blocks.flatMap { b ->
            if (b.rule) return@flatMap listOf(Row(b, null, true, true))
            val strips = TextBitmaps.block(ctx, b.text, width - if (b.code) dp(ctx, 16) else 0, paint)
            strips.mapIndexed { i, bmp -> Row(b, png(bmp), i == 0, i == strips.lastIndex) }
        }
        return rows to palette
    }

    fun view(ctx: Context, row: Row, palette: Palette): RemoteViews {
        val b = row.block
        return RemoteViews(ctx.packageName, R.layout.widget_item).apply {
            setViewVisibility(R.id.item_img, if (b.rule) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.item_rule, if (b.rule) View.VISIBLE else View.GONE)
            setInt(R.id.item_rule, "setBackgroundColor", palette.faint)
            row.png?.let {
                setImageViewIcon(R.id.item_img, Icon.createWithData(it, 0, it.size))
                if (row.first) setContentDescription(R.id.item_img, b.text)
                val side = if (b.code) dp(ctx, 8) else 0
                val top = if (b.code && row.first) dp(ctx, 6) else 0
                val bottom = if (b.code && row.last) dp(ctx, 6) else 0
                setViewPadding(R.id.item_img, side, top, side, bottom)
                setInt(R.id.item_img, "setBackgroundColor", if (b.code) palette.codeBg else 0)
            }
            setOnClickFillInIntent(
                R.id.item,
                Intent().putExtra(EditorActivity.EXTRA_LINE, b.line).putExtra(EditorActivity.EXTRA_TOGGLE, b.task),
            )
        }
    }

    private fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
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
        private var palette = Palette.FALLBACK_DARK

        override fun onDataSetChanged() {
            val (r, p) = NoteRows.build(ctx, id) ?: return run { rows = emptyList() }
            rows = r
            palette = p
        }

        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.let { NoteRows.view(ctx, it, palette) } ?: RemoteViews(ctx.packageName, R.layout.widget_item)

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
