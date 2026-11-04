package local.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import kotlin.concurrent.thread

class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Runs every 30 minutes and after the app is updated; picks up note, theme and font changes made in Obsidian.
        refresh(ctx, ids)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        // Resized: redraw the rows at the new width.
        update(ctx, mgr, id)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.list)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) WidgetStore.delete(ctx, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return super.onReceive(ctx, intent)
        refresh(ctx, intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)))
    }

    /** Re-reads the theme and font from the vault, then redraws the widgets and their notes. */
    private fun refresh(ctx: Context, ids: IntArray) {
        val result = goAsync()
        thread {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                for (id in ids) {
                    WidgetStore.load(ctx, id)?.let { cfg ->
                        runCatching { ObsidianTheme.load(ctx, cfg.theme) }.onSuccess {
                            WidgetStore.save(ctx, id, cfg.copy(colors = it))
                        }
                    }
                    update(ctx, mgr, id)
                }
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "local.obsidianwidget.REFRESH"

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val cfg = WidgetStore.load(ctx, id)
            val views = RemoteViews(ctx.packageName, R.layout.widget_note)
            val p = cfg?.palette(ctx) ?: Palette.FALLBACK_DARK

            views.setInt(R.id.bg, "setColorFilter", p.background)
            views.setInt(R.id.bg, "setImageAlpha", ((cfg?.opacity ?: 100) * 255 / 100))
            views.setTextColor(R.id.empty, p.faint)
            for (button in listOf(R.id.btn_refresh, R.id.btn_change, R.id.btn_edit, R.id.btn_open)) {
                views.setInt(button, "setColorFilter", p.muted)
            }

            if (cfg == null) {
                views.setImageViewBitmap(R.id.title, title(ctx, id, null, ctx.getString(R.string.app_name), p))
                views.setTextViewText(R.id.empty, "Tap to choose a note")
                val setup = activity(ctx, id, 0, configIntent(ctx, id, pickOnly = false))
                views.setOnClickPendingIntent(R.id.empty, setup)
                views.setOnClickPendingIntent(R.id.title, setup)
                mgr.updateAppWidget(id, views)
                return
            }

            views.setImageViewBitmap(R.id.title, title(ctx, id, cfg, cfg.title, p))
            views.setContentDescription(R.id.title, cfg.title)
            views.setTextViewText(R.id.empty, ctx.getString(R.string.empty_note))

            val service = Intent(ctx, NoteListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // A unique data URI per widget, or the launcher reuses one widget's list for another.
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.list, service)
            views.setEmptyView(R.id.list, R.id.empty)

            // Row taps are filled in with the line number and whether it's a task to tick off.
            val rowTemplate = PendingIntent.getActivity(
                ctx, id * 10 + 1, editorIntent(ctx, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.list, rowTemplate)

            val edit = activity(ctx, id, 2, editorIntent(ctx, id))
            views.setOnClickPendingIntent(R.id.btn_edit, edit)
            views.setOnClickPendingIntent(R.id.empty, edit)
            views.setOnClickPendingIntent(R.id.btn_change, activity(ctx, id, 3, configIntent(ctx, id, pickOnly = true)))
            views.setOnClickPendingIntent(R.id.title, activity(ctx, id, 4, configIntent(ctx, id, pickOnly = false)))
            views.setOnClickPendingIntent(
                R.id.btn_open,
                activity(ctx, id, 5, Intent(Intent.ACTION_VIEW, Vault.openUri(ctx, cfg.notePath)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
            )
            views.setOnClickPendingIntent(
                R.id.btn_refresh,
                PendingIntent.getBroadcast(
                    ctx, id * 10 + 6,
                    Intent(ctx, NoteWidgetProvider::class.java).setAction(ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            mgr.updateAppWidget(id, views)
        }

        private fun title(ctx: Context, id: Int, cfg: WidgetConfig?, text: String, p: Palette): android.graphics.Bitmap {
            val bold = android.graphics.Typeface.create(Fonts.typeface(cfg?.fontFile), android.graphics.Typeface.BOLD)
            val d = ctx.resources.displayMetrics.density
            // Room left beside the four 34dp buttons and the widget's side padding.
            val max = TextBitmaps.widgetWidth(ctx, id) - ((14 + 6 + 4 * 34) * d).toInt()
            return TextBitmaps.line(ctx, text, max, TextBitmaps.paint(ctx, bold, 15f, p.title))
        }

        /** Redraws every widget showing [noteUri], e.g. after the editor saved it. */
        fun refreshNote(ctx: Context, noteUri: String) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = allIds(ctx).filter { WidgetStore.load(ctx, it)?.noteUri == noteUri }.toIntArray()
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        fun allIds(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, NoteWidgetProvider::class.java))

        fun editorIntent(ctx: Context, id: Int) = Intent(ctx, EditorActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("widget://$id/edit"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun configIntent(ctx: Context, id: Int, pickOnly: Boolean) = Intent(ctx, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(ConfigActivity.EXTRA_PICK_ONLY, pickOnly)
            .setData(Uri.parse("widget://$id/config/$pickOnly"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun activity(ctx: Context, id: Int, slot: Int, intent: Intent) = PendingIntent.getActivity(
            ctx, id * 10 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
