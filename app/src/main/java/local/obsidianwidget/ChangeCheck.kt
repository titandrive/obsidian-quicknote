package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.provider.DocumentsContract.Document
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Checks every so often whether a widget's note, theme or light/dark changed (say, edited in
 * Obsidian), and redraws only the widgets where something did. Looking is cheap: the note's date
 * and size, and the theme from its cache. How often is one setting for all widgets.
 */
object ChangeCheck {
    private const val WORK = "change-check"

    /** Minutes between checks; 0 is off. WorkManager runs periodic work at most every 15 minutes. */
    val CHOICES = listOf(0, 15, 30, 60, 180)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun minutes(ctx: Context) = prefs(ctx).getInt("checkMinutes", 30)

    fun setMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt("checkMinutes", minutes).apply()
        schedule(ctx)
    }

    /** Starts, changes or stops the periodic check to match the setting and whether there are widgets. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val minutes = minutes(ctx)
        if (minutes == 0 || NoteWidgetProvider.allIds(ctx).isEmpty()) {
            wm.cancelUniqueWork(WORK)
            return
        }
        wm.enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<Worker>(minutes.toLong(), TimeUnit.MINUTES).build(),
        )
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            check(applicationContext)
            return Result.success()
        }
    }

    fun check(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in NoteWidgetProvider.allIds(ctx)) {
            var cfg = WidgetStore.load(ctx, id) ?: continue
            val colors = runCatching { ObsidianTheme.load(ctx, cfg.theme) }.getOrNull()
            val themeChanged = colors != null && colors != cfg.colors
            if (themeChanged) {
                cfg = cfg.copy(colors = colors)
                WidgetStore.save(ctx, id, cfg)
            }
            if (themeChanged || stamp(ctx, cfg) != seen(ctx, id)) NoteWidgetProvider.update(ctx, mgr, id)
        }
    }

    /**
     * What a widget was last drawn from, as far as the check can tell: the note's date and size,
     * and light or dark unless the launcher switches that by itself.
     */
    fun stamp(ctx: Context, cfg: WidgetConfig): String {
        val file = runCatching {
            ctx.contentResolver.query(cfg.uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else null }
        }.getOrNull() ?: "missing"
        return if (NoteRows.dayAndNight(cfg)) file else "$file:${cfg.isDark(ctx)}"
    }

    private fun seen(ctx: Context, id: Int) = prefs(ctx).getString("seen$id", null)

    /** Records what the widget was just drawn from, so the next check can tell if it changed. */
    fun drawn(ctx: Context, id: Int, cfg: WidgetConfig) {
        prefs(ctx).edit().putString("seen$id", stamp(ctx, cfg)).apply()
    }

    fun forget(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("seen$id").apply()
    }
}
