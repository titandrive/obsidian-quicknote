package local.obsidianwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Where taps on the widget's lines land. A task is ticked off with nothing shown (the theme draws
 * no window); any other line opens the editor there. Kept apart from the editor so ticking a task
 * doesn't briefly show the editor's window.
 */
class RowTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val line = intent.getIntExtra(EditorActivity.EXTRA_LINE, -1)
        val cfg = WidgetStore.load(this, id)
        if (cfg != null) {
            if (intent.getBooleanExtra(EditorActivity.EXTRA_TOGGLE, false)) {
                val app = applicationContext
                thread { EditorActivity.toggleTask(app, cfg, line) }
            } else {
                startActivity(NoteWidgetProvider.editorIntent(this, id).putExtra(EditorActivity.EXTRA_LINE, line))
            }
        }
        // A NoDisplay activity has to finish before it would be shown.
        finish()
    }
}
