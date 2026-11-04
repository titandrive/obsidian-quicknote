package local.obsidianwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug aid: renders files/probe.md as widget [id] would, into cache/probe/ (strips and spoken text);
 * or, with the "check" extra, runs the background change check right away.
 */
class RenderProbe : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        thread {
            try {
                if (intent.getBooleanExtra("check", false)) {
                    ChangeCheck.check(ctx)
                    return@thread
                }
                val id = intent.getIntExtra("id", 0)
                val rows = NoteRows.renderForProbe(ctx, id, File(ctx.filesDir, "probe.md").readText())
                val dir = File(ctx.cacheDir, "probe").apply { deleteRecursively(); mkdirs() }
                rows.forEachIndexed { i, r -> File(dir, "%03d.webp".format(i)).writeBytes(r.image) }
                File(dir, "spoken.txt").writeText(rows.mapIndexed { i, r -> "$i\t${r.line}\t${r.text ?: ""}" }.joinToString("\n"))
            } finally {
                result.finish()
            }
        }
    }
}
