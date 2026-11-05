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
        // Runs when a widget is added and after the app is updated; regular checks are ChangeCheck's.
        refresh(ctx, ids)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        // Resized: redraw the rows at the new width.
        val result = goAsync()
        thread {
            try {
                update(ctx, mgr, id)
            } finally {
                result.finish()
            }
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) {
            WidgetStore.delete(ctx, id)
            NoteRows.forget(ctx, id)
            ChangeCheck.cancel(ctx, id)
            Folds.forget(ctx, id)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> refresh(ctx, intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)), spin = true)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ChangeCheck.scheduleAll(ctx)
                refresh(ctx, allIds(ctx))
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    /**
     * Re-reads the theme and font from the vault, then redraws the widgets and their notes. With
     * [spin], the refresh button turns until it's done (asked for by tapping it).
     */
    private fun refresh(ctx: Context, ids: IntArray, spin: Boolean = false) {
        val result = goAsync()
        thread {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val started = android.os.SystemClock.uptimeMillis()
                if (spin) for (id in ids) mgr.partiallyUpdateAppWidget(id, spinner(ctx, id, true))
                for (id in ids) {
                    WidgetStore.load(ctx, id)?.let { cfg ->
                        runCatching { ObsidianTheme.load(ctx, cfg.vaultTree, cfg.theme) }.onSuccess {
                            WidgetStore.save(ctx, id, cfg.copy(colors = it))
                        }
                    }
                    // Long enough to see it turn, even when the refresh is quick.
                    if (spin) android.os.SystemClock.sleep((MIN_SPIN - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
                    update(ctx, mgr, id)
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "local.obsidianwidget.REFRESH"
        private const val MIN_SPIN = 600L

        /** Swaps the refresh button for its spinning version, or back. */
        private fun spinner(ctx: Context, id: Int, on: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_note).also {
            applySpinner(it, looks(ctx, WidgetStore.load(ctx, id)), on)
        }

        /**
         * The palettes a widget is drawn in: [day] always, and [night] too when it follows the
         * phone's dark mode on Android 12+, for the launcher to switch to by itself.
         */
        private class Looks(val day: Palette, val night: Palette?)

        private fun looks(ctx: Context, cfg: WidgetConfig?): Looks = when {
            cfg == null -> Looks(Palette.FALLBACK_DARK, null)
            NoteRows.dayAndNight(cfg) -> Looks(cfg.palette(ctx, false), cfg.palette(ctx, true))
            else -> Looks(cfg.palette(ctx), null)
        }

        /** Calls [method] with a colour, or with a light and a dark one for the launcher to pick from. */
        private fun color(views: RemoteViews, viewId: Int, method: String, looks: Looks, pick: (Palette) -> Int) {
            val night = looks.night
            if (night != null && android.os.Build.VERSION.SDK_INT >= 31) views.setColorInt(viewId, method, pick(looks.day), pick(night))
            else views.setInt(viewId, method, pick(looks.day))
        }

        private fun colorList(views: RemoteViews, viewId: Int, method: String, looks: Looks, pick: (Palette) -> Int) {
            if (android.os.Build.VERSION.SDK_INT < 31) return
            val day = android.content.res.ColorStateList.valueOf(pick(looks.day))
            val night = looks.night
            if (night != null) views.setColorStateList(viewId, method, day, android.content.res.ColorStateList.valueOf(pick(night)))
            else views.setColorStateList(viewId, method, day)
        }

        private fun applySpinner(views: RemoteViews, looks: Looks, on: Boolean) {
            views.setViewVisibility(R.id.btn_refresh, if (on) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(R.id.refresh_spin, if (on) android.view.View.VISIBLE else android.view.View.GONE)
            colorList(views, R.id.refresh_spin, "setIndeterminateTintList", looks) { it.muted }
        }

        /**
         * Most a note's rows may add up to and still be sent with the widget. The system passes the
         * widget on to the launcher in a one-way call, which fails quietly past about 512 KB.
         */
        private const val INLINE_LIMIT = 450_000

        /** Redraws the widget, note and all. Reads and renders the note, so call it off the main thread. */
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val cfg = WidgetStore.load(ctx, id)
            val views = RemoteViews(ctx.packageName, R.layout.widget_note)
            val looks = looks(ctx, cfg)
            val p = looks.day

            color(views, R.id.bg, "setColorFilter", looks) { it.background }
            views.setInt(R.id.bg, "setImageAlpha", ((cfg?.opacity ?: 100) * 255 / 100))
            if (looks.night != null) colorList(views, R.id.empty, "setTextColor", looks) { it.faint } else views.setTextColor(R.id.empty, p.faint)
            for (button in listOf(R.id.btn_refresh, R.id.btn_edit, R.id.btn_open, R.id.btn_settings)) {
                color(views, button, "setColorFilter", looks) { it.muted }
            }
            // A launcher may reapply this onto the existing views, so set the still icon back explicitly.
            applySpinner(views, looks, false)
            // The drawn title replaces the layout's plain placeholder.
            views.setViewVisibility(R.id.title, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.title_text, android.view.View.GONE)

            if (cfg == null) {
                views.setImageViewBitmap(R.id.title, title(ctx, id, null, ctx.getString(R.string.app_name), p))
                views.setTextViewText(R.id.empty, "Tap to choose a note")
                val setup = activity(ctx, id, 0, configIntent(ctx, id, pickOnly = false))
                views.setOnClickPendingIntent(R.id.empty, setup)
                views.setOnClickPendingIntent(R.id.title, setup)
                views.setOnClickPendingIntent(R.id.btn_settings, setup)
                mgr.updateAppWidget(id, views)
                return
            }

            views.setViewVisibility(R.id.header, if (cfg.showHeader) android.view.View.VISIBLE else android.view.View.GONE)
            // Without the header the list starts at the top edge; give it the header's top padding back.
            views.setViewPadding(R.id.list, 0, if (cfg.showHeader) 0 else dp(ctx, 6), 0, 0)
            val night = looks.night
            if (night != null && android.os.Build.VERSION.SDK_INT >= 31) {
                views.setIcon(
                    R.id.title, "setImageIcon",
                    android.graphics.drawable.Icon.createWithBitmap(title(ctx, id, cfg, cfg.title, p)),
                    android.graphics.drawable.Icon.createWithBitmap(title(ctx, id, cfg, cfg.title, night)),
                )
            } else {
                views.setImageViewBitmap(R.id.title, title(ctx, id, cfg, cfg.title, p))
            }
            views.setContentDescription(R.id.title, cfg.title)
            views.setTextViewText(R.id.empty, ctx.getString(R.string.empty_note))

            // On Android 12+ every row goes with the widget, so the launcher never fetches rows while
            // scrolling (fetching showed placeholders that jumped to the real rows' heights).
            val rows = if (android.os.Build.VERSION.SDK_INT >= 31) NoteRows.build(ctx, id) else null
            val inline = rows != null && rows.rows.sumOf { it.image.size } + rows.night.orEmpty().sumOf { it.image.size } < INLINE_LIMIT
            val routes = ctx.getSharedPreferences("routes", Context.MODE_PRIVATE)
            val wasInline = routes.getBoolean("inline$id", false)
            if (inline && android.os.Build.VERSION.SDK_INT >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                rows!!.rows.forEachIndexed { i, row -> items.addItem(i.toLong(), NoteRows.view(ctx, row, rows.night?.getOrNull(i))) }
                views.setRemoteAdapter(R.id.list, items.build())
                routes.edit().putBoolean("inline$id", true).apply()
            } else {
                // A list that went from fetching rows to being handed them, and back, takes the same
                // service intent as "already connected" and keeps showing the handed-over rows (say,
                // a folded note after unfolding it). Each switch back gets a fresh intent.
                var round = routes.getInt("round$id", 0)
                if (wasInline) round++
                routes.edit().putBoolean("inline$id", false).putInt("round$id", round).apply()
                val service = Intent(ctx, NoteListService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("round", round)
                // A unique data URI per widget, or the launcher reuses one widget's list for another.
                service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.list, service)
            }
            views.setEmptyView(R.id.list, R.id.empty)

            // Row taps are filled in with the line number and whether it's a task to tick off.
            // Every tap goes through TapActivity, which gives the haptic tick a widget can't.
            // Row taps are filled in with the line number and whether it's a task to tick off.
            val rowTemplate = PendingIntent.getActivity(
                ctx, id * 10 + 1, tapIntent(ctx, id, "row"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.list, rowTemplate)

            val edit = activity(ctx, id, 2, tapIntent(ctx, id, "edit").putExtra(TapActivity.EXTRA_FORWARD, editorIntent(ctx, id)))
            views.setOnClickPendingIntent(R.id.btn_edit, edit)
            views.setOnClickPendingIntent(R.id.empty, edit)
            // The note's name switches notes; the cog has the rest of the settings.
            views.setOnClickPendingIntent(R.id.title, activity(ctx, id, 3, tapIntent(ctx, id, "pick").putExtra(TapActivity.EXTRA_FORWARD, configIntent(ctx, id, pickOnly = true))))
            views.setOnClickPendingIntent(R.id.btn_settings, activity(ctx, id, 4, tapIntent(ctx, id, "settings").putExtra(TapActivity.EXTRA_FORWARD, configIntent(ctx, id, pickOnly = false))))
            views.setOnClickPendingIntent(
                R.id.btn_open,
                activity(ctx, id, 5, tapIntent(ctx, id, "open").putExtra(
                    TapActivity.EXTRA_FORWARD,
                    Intent(Intent.ACTION_VIEW, Vault.openUri(cfg.vaultName, cfg.notePath)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )),
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, activity(ctx, id, 6, tapIntent(ctx, id, "refresh").putExtra(TapActivity.EXTRA_REFRESH, true)))
            mgr.updateAppWidget(id, views)
            if (!inline) mgr.notifyAppWidgetViewDataChanged(id, R.id.list)
            ChangeCheck.drawn(ctx, id, cfg)
        }

        private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        private fun title(ctx: Context, id: Int, cfg: WidgetConfig?, text: String, p: Palette): android.graphics.Bitmap {
            val bold = android.graphics.Typeface.create(Fonts.typeface(cfg?.fontFile), android.graphics.Typeface.BOLD)
            val d = ctx.resources.displayMetrics.density
            // Room left beside the four 34dp buttons and the widget's side padding.
            val max = TextBitmaps.widgetWidth(ctx, id) - ((14 + 6 + 4 * 34) * d).toInt()
            return TextBitmaps.line(ctx, text, max, TextBitmaps.paint(ctx, bold, 15f, p.title))
        }

        /** Redraws every widget showing [noteUri], e.g. after the editor saved it. */
        fun refreshNote(ctx: Context, noteUri: String) {
            val app = ctx.applicationContext
            thread {
                val mgr = AppWidgetManager.getInstance(app)
                for (id in allIds(app)) if (WidgetStore.load(app, id)?.noteUri == noteUri) update(app, mgr, id)
            }
        }

        fun allIds(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, NoteWidgetProvider::class.java))

        /** An intent for [TapActivity]; [what] keeps each button's PendingIntent distinct. */
        private fun tapIntent(ctx: Context, id: Int, what: String) = Intent(ctx, TapActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("widget://$id/tap/$what"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
