package local.obsidianwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Where every tap on the widget lands. It draws no window (Theme.NoDisplay): it gives the light tick
 * a widget can't give by itself, then does what was tapped: folds or unfolds, ticks a task, opens
 * the editor at a line, refreshes, or starts the screen in [EXTRA_FORWARD] (settings, Obsidian,
 * the note chooser).
 */
class TapActivity : Activity() {
    companion object {
        /** The activity to start after the tick. */
        const val EXTRA_FORWARD = "forward"
        const val EXTRA_REFRESH = "refresh"
        /** The line of a fold arrow that was tapped. */
        const val EXTRA_FOLD = "fold"
        /** Turns reading mode on or off. */
        const val EXTRA_READ = "read"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Haptics.widgetTick(this)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        @Suppress("DEPRECATION")
        val forward = intent.getParcelableExtra<Intent>(EXTRA_FORWARD)
        val fold = intent.getIntExtra(EXTRA_FOLD, -1)
        when {
            fold >= 0 -> {
                val app = applicationContext
                thread {
                    val cfg = WidgetStore.load(app, id) ?: return@thread
                    val text = Vault.read(app, cfg.uri) ?: return@thread
                    Folds.toggle(app, id, text, fold)
                    NoteWidgetProvider.update(app, android.appwidget.AppWidgetManager.getInstance(app), id)
                }
            }
            intent.getBooleanExtra(EXTRA_READ, false) -> {
                val app = applicationContext
                thread {
                    val cfg = WidgetStore.load(app, id) ?: return@thread
                    WidgetStore.save(app, id, cfg.copy(readOnly = !cfg.readOnly))
                    NoteWidgetProvider.update(app, AppWidgetManager.getInstance(app), id)
                }
            }
            forward != null -> runCatching { startActivity(forward) }
            intent.getBooleanExtra(EXTRA_REFRESH, false) -> sendBroadcast(
                Intent(this, NoteWidgetProvider::class.java).setAction(NoteWidgetProvider.ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
            else -> {
                // A line of the note: tick a task off, or edit there (not in reading mode, which
                // still ticks tasks, as Obsidian's reading view does).
                val line = intent.getIntExtra(EditorActivity.EXTRA_LINE, -1)
                val cfg = WidgetStore.load(this, id)
                if (cfg != null && intent.getBooleanExtra(EditorActivity.EXTRA_TOGGLE, false)) {
                    val app = applicationContext
                    thread { EditorActivity.toggleTask(app, cfg, line) }
                } else if (cfg != null && !cfg.readOnly) {
                    startActivity(NoteWidgetProvider.editorIntent(this, id).putExtra(EditorActivity.EXTRA_LINE, line))
                }
            }
        }
        // A NoDisplay activity has to finish before it would be shown.
        finish()
    }
}
