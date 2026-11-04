package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.provider.DocumentsContract.Document
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Checks every so often whether a widget's note, theme or light/dark changed (say, edited in
 * Obsidian), and redraws the widget if something did. Looking is cheap: the note's date and size,
 * and the theme from its cache. Each widget has its own schedule, set in its settings.
 */
object ChangeCheck {
    /** Minutes between checks; 0 is off. WorkManager runs periodic work at most every 15 minutes. */
    val CHOICES = listOf(0, 15, 30, 60, 180)

    private fun work(id: Int) = "change-check-$id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Starts, changes or stops widget [id]'s check to match its setting. */
    fun schedule(ctx: Context, id: Int) {
        val wm = WorkManager.getInstance(ctx)
        val minutes = WidgetStore.load(ctx, id)?.checkMinutes ?: 0
        if (minutes == 0) {
            wm.cancelUniqueWork(work(id))
            return
        }
        wm.enqueueUniquePeriodicWork(
            work(id),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<Worker>(minutes.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("id" to id))
                .build(),
        )
    }

    /** Brings every widget's check in line with its setting, e.g. after an app update. */
    fun scheduleAll(ctx: Context) {
        // Before widgets had their own schedules there was one check for all of them.
        WorkManager.getInstance(ctx).cancelUniqueWork("change-check")
        for (id in NoteWidgetProvider.allIds(ctx)) schedule(ctx, id)
    }

    fun cancel(ctx: Context, id: Int) {
        WorkManager.getInstance(ctx).cancelUniqueWork(work(id))
        prefs(ctx).edit().remove("seen$id").apply()
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            check(applicationContext, inputData.getInt("id", AppWidgetManager.INVALID_APPWIDGET_ID))
            return Result.success()
        }
    }

    fun check(ctx: Context, id: Int) {
        var cfg = WidgetStore.load(ctx, id) ?: return
        val colors = runCatching { ObsidianTheme.load(ctx, cfg.vaultTree, cfg.theme) }.getOrNull()
        val themeChanged = colors != null && colors != cfg.colors
        if (themeChanged) {
            cfg = cfg.copy(colors = colors)
            WidgetStore.save(ctx, id, cfg)
        }
        if (themeChanged || stamp(ctx, cfg) != prefs(ctx).getString("seen$id", null)) {
            NoteWidgetProvider.update(ctx, AppWidgetManager.getInstance(ctx), id)
        }
    }

    /**
     * What a widget was last drawn from, as far as the check can tell: the note's date and size,
     * and light or dark unless the launcher switches that by itself.
     */
    private fun stamp(ctx: Context, cfg: WidgetConfig): String {
        val file = runCatching {
            ctx.contentResolver.query(cfg.uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else null }
        }.getOrNull() ?: "missing"
        return if (NoteRows.dayAndNight(cfg)) file else "$file:${cfg.isDark(ctx)}"
    }

    /** Records what the widget was just drawn from, so the next check can tell if it changed. */
    fun drawn(ctx: Context, id: Int, cfg: WidgetConfig) {
        prefs(ctx).edit().putString("seen$id", stamp(ctx, cfg)).apply()
    }
}
