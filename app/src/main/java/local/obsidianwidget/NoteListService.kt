package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Feeds the widget's scrolling list with the rendered rows of its note. */
class NoteListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    /** One strip of a block; a tall block is several, and only the first and last get the code block's padding. */
    private class Row(val block: Block, val image: Bitmap?, val first: Boolean, val last: Boolean)

    private class Factory(val ctx: Context, val id: Int) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        private var palette = Palette.FALLBACK_DARK

        override fun onDataSetChanged() {
            val cfg = WidgetStore.load(ctx, id) ?: return run { rows = emptyList() }
            palette = cfg.palette(ctx)
            val text = Vault.read(ctx, cfg.uri)
            val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, ctx.resources.displayMetrics).toInt()
            val blocks = if (text == null) {
                listOf(Block("Couldn't read ${cfg.notePath}. It may have been moved or renamed; tap ⇄ to pick it again.", 0))
            } else {
                Markdown.render(text, palette, indent)
            }
            val paint = TextBitmaps.paint(ctx, Fonts.typeface(cfg.fontFile), cfg.textSize, palette.text)
            // The widget minus its side padding.
            val width = TextBitmaps.widgetWidth(ctx, id) - dp(14 + 6 + 8)
            rows = blocks.flatMap { b ->
                if (b.rule) return@flatMap listOf(Row(b, null, true, true))
                val strips = TextBitmaps.block(ctx, b.text, width - if (b.code) dp(16) else 0, paint)
                strips.mapIndexed { i, bmp -> Row(b, bmp, i == 0, i == strips.lastIndex) }
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_item)
            val b = row.block
            return RemoteViews(ctx.packageName, R.layout.widget_item).apply {
                setViewVisibility(R.id.item_img, if (b.rule) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.item_rule, if (b.rule) View.VISIBLE else View.GONE)
                setInt(R.id.item_rule, "setBackgroundColor", palette.faint)
                row.image?.let {
                    setImageViewBitmap(R.id.item_img, it)
                    if (row.first) setContentDescription(R.id.item_img, b.text)
                    val side = if (b.code) dp(8) else 0
                    val top = if (b.code && row.first) dp(6) else 0
                    val bottom = if (b.code && row.last) dp(6) else 0
                    setViewPadding(R.id.item_img, side, top, side, bottom)
                    setInt(R.id.item_img, "setBackgroundColor", if (b.code) palette.codeBg else 0)
                }
                setOnClickFillInIntent(
                    R.id.item,
                    Intent().putExtra(EditorActivity.EXTRA_LINE, b.line).putExtra(EditorActivity.EXTRA_TOGGLE, b.task),
                )
            }
        }

        private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun onCreate() = Unit
        override fun onDestroy() = Unit
    }
}
