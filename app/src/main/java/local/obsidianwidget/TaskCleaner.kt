package local.obsidianwidget

import android.content.Context
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Task Cleaner plugin, done on the widget: a task ticked here is hidden (or deleted) the way
 * Task Cleaner does in Obsidian, after the same delay. Task Cleaner in Obsidian leaves changes made
 * outside Obsidian alone (they're the device's own to deal with), so the widget does it. Notes with
 * task-cleaner-ignore: true in their frontmatter are left alone.
 *
 * Task Cleaner keeps hidden tasks in its settings, which the widget can't safely change while
 * Obsidian may be holding them. So a task hidden here is added to an inbox file next to them
 * (.obsidian/plugins/task-cleaner/widget-inbox.json), which Task Cleaner takes in when Obsidian
 * comes to the front, and every minute while it's open.
 *
 * The plugin used to be called Checker: its old settings folder and ignore property still work.
 */
object TaskCleaner {
    private val data = PluginData("task-cleaner")
    private val oldData = PluginData("checker")
    private const val PLUGIN_DIR = ".obsidian/plugins/task-cleaner"
    private const val INBOX = "widget-inbox.json"

    class Settings(val hide: Boolean, val delayMs: Long, val skipResetNotes: Boolean)

    /** Task Cleaner's settings, or null if it isn't in the vault. */
    fun settings(ctx: Context, cfg: WidgetConfig): Settings? {
        val json = data.settings(ctx, cfg.vaultTree) ?: oldData.settings(ctx, cfg.vaultTree) ?: return null
        return Settings(
            hide = json.optString("action", "hide") != "delete",
            delayMs = json.optLong("delayMs", 500),
            skipResetNotes = json.optBoolean("skipResetNotes", true),
        )
    }

    private val TASK_RESETTER_MARKER = Regex("\\[reset-tasks(?=[\\s:\\]])", RegexOption.IGNORE_CASE)

    /**
     * A task was just ticked on the widget: after the delay, takes it out of the note (with what's
     * nested under it), into Task Cleaner's inbox if hiding. If it was unticked again in the meantime,
     * it stays.
     */
    fun ticked(ctx: Context, cfg: WidgetConfig, text: String, line: Int, lock: Any) {
        val settings = settings(ctx, cfg) ?: return
        if (settings.skipResetNotes && TASK_RESETTER_MARKER.containsMatchIn(text)) return
        if (isIgnored(text)) return
        val app = ctx.applicationContext
        kotlin.concurrent.thread {
            Thread.sleep(settings.delayMs)
            synchronized(lock) {
                val now = Vault.read(app, cfg.uri) ?: return@thread
                val taken = takeOut(now, text.split('\n')[line], line) ?: return@thread
                if (settings.hide && !addToInbox(app, cfg, taken)) return@thread
                Vault.write(app, cfg.uri, taken.text)
            }
            NoteWidgetProvider.refreshNote(app, cfg.noteUri)
        }
    }

    /** A task taken out of a note: the note without it, its lines, and where it was. */
    class Taken(val text: String, val lines: List<String>, val after: String?, val at: Int)

    /** [src] with the checked task [task] (the copy nearest [near]) taken out, or null if it's gone. */
    fun takeOut(src: String, task: String, near: Int): Taken? {
        val body = src.split('\n').toMutableList()
        val at = body.indices.filter { body[it] == task }.minByOrNull { kotlin.math.abs(it - near) } ?: return null
        val end = blockEnd(body, at)
        val lines = body.subList(at, end).toList()
        body.subList(at, end).clear()
        return Taken(body.joinToString("\n"), lines, if (at > 0) body[at - 1] else null, at)
    }

    /** Adds a hidden task to Task Cleaner's inbox file. False if it couldn't be written. */
    private fun addToInbox(ctx: Context, cfg: WidgetConfig, taken: Taken): Boolean = runCatching {
        val tree = cfg.vaultTree
        val file = Vault.find(ctx, tree, "$PLUGIN_DIR/$INBOX") ?: run {
            val dir = Vault.find(ctx, tree, PLUGIN_DIR) ?: return false
            DocumentsContract.createDocument(ctx.contentResolver, dir, "application/octet-stream", INBOX) ?: return false
        }
        val items = Vault.read(ctx, file)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        items.put(
            JSONObject()
                .put("path", cfg.notePath)
                .put("kind", "hidden")
                .put("after", taken.after ?: JSONObject.NULL)
                .put("at", taken.at)
                .put("lines", JSONArray(taken.lines))
                .put("hiddenAt", System.currentTimeMillis()),
        )
        Vault.write(ctx, file, items.toString())
        true
    }.getOrDefault(false)

    /** The line after a task and the lines nested (indented) under it. */
    fun blockEnd(lines: List<String>, at: Int): Int {
        val indent = indentOf(lines[at])
        var end = at + 1
        while (end < lines.size && lines[end].isNotBlank() && indentOf(lines[end]) > indent) end++
        return end
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4L else 1L }.toInt()

    private val FRONTMATTER = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---(?:\\r?\\n|$)")
    private val IGNORE = Regex("^(?:task-cleaner-ignore|checker-ignore):\\s*[\"']?(true|yes)[\"']?\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** Whether the note's frontmatter has task-cleaner-ignore: true (or the older checker-ignore). */
    fun isIgnored(text: String): Boolean = FRONTMATTER.find(text)?.let { IGNORE.containsMatchIn(it.groupValues[1]) } ?: false
}
