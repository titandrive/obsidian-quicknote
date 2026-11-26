package local.obsidianwidget

import android.content.Context
import android.provider.DocumentsContract
import java.io.File
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
                offerUndo(app, cfg, taken, settings.hide)
            }
            NoteWidgetProvider.refreshNote(app, cfg.noteUri)
            // The Undo button goes once its time is up.
            Thread.sleep(UNDO_MS + 100)
            NoteWidgetProvider.refreshNote(app, cfg.noteUri)
        }
    }

    /** How long the widget offers Undo after a task is hidden or deleted, as Task Cleaner's notice does. */
    const val UNDO_MS = 5000L

    private fun undoPrefs(ctx: Context) = ctx.getSharedPreferences("task-cleaner-undo", Context.MODE_PRIVATE)

    /** Keeps what was just taken out of the note, for the widget's Undo button; tasks taken out while it's up share it. */
    private fun offerUndo(ctx: Context, cfg: WidgetConfig, taken: Taken, hide: Boolean) {
        val prefs = undoPrefs(ctx)
        val now = System.currentTimeMillis()
        val old = prefs.getString(cfg.noteUri, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val items = old?.takeIf { it.optLong("until") > now }?.optJSONArray("items") ?: JSONArray()
        items.put(JSONObject().put("lines", JSONArray(taken.lines)).put("after", taken.after ?: JSONObject.NULL).put("at", taken.at))
        prefs.edit().putString(cfg.noteUri, JSONObject().put("until", now + UNDO_MS).put("hide", hide).put("items", items).toString()).apply()
    }

    /** Whether the widget's note has tasks just taken out that Undo can put back. */
    fun canUndo(ctx: Context, noteUri: String): Boolean =
        undoPrefs(ctx).getString(noteUri, null)?.let { runCatching { JSONObject(it).optLong("until") > System.currentTimeMillis() }.getOrNull() } ?: false

    private val CHECKED = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+\\[)[xX](])")

    /**
     * The widget's Undo: puts the tasks just taken out back in the note where they were, unchecked,
     * as Task Cleaner's Undo does. Hidden ones come out of the inbox, or, if Task Cleaner has taken
     * them in already, the inbox tells it to drop them.
     */
    fun undo(ctx: Context, cfg: WidgetConfig, lock: Any) {
        val prefs = undoPrefs(ctx)
        val record = prefs.getString(cfg.noteUri, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        prefs.edit().remove(cfg.noteUri).apply()
        if (record.optLong("until") <= System.currentTimeMillis()) return
        val items = record.optJSONArray("items") ?: return
        val taken = (0 until items.length()).map { k ->
            val o = items.getJSONObject(k)
            val lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }
            Taken("", lines, if (o.isNull("after")) null else o.getString("after"), o.getInt("at"))
        }
        synchronized(lock) {
            val text = Vault.read(ctx, cfg.uri) ?: return
            val body = text.split('\n').toMutableList()
            for (t in taken.asReversed()) {
                val lines = listOf(CHECKED.replace(t.lines[0], "$1 $2")) + t.lines.drop(1)
                body.addAll(placeFor(body, t), lines)
            }
            Vault.write(ctx, cfg.uri, body.joinToString("\n"))
            if (record.optBoolean("hide")) unhide(ctx, cfg, taken)
        }
        NoteWidgetProvider.refreshNote(ctx, cfg.noteUri)
    }

    /** Where a task goes back: under its "after" line (the copy nearest where it was), or where it was if that's gone. */
    private fun placeFor(body: List<String>, t: Taken): Int {
        val after = t.after ?: return 0
        val best = body.indices.filter { body[it] == after }.minByOrNull { kotlin.math.abs(it - (t.at - 1)) }
        return if (best != null) blockEnd(body, best) else minOf(t.at, body.size)
    }

    /** Takes undone tasks out of the inbox; ones Task Cleaner already took in are listed there for it to drop. */
    private fun unhide(ctx: Context, cfg: WidgetConfig, taken: List<Taken>) = runCatching {
        val tree = cfg.vaultTree
        val file = Vault.find(ctx, tree, "$PLUGIN_DIR/$INBOX") ?: run {
            val dir = Vault.find(ctx, tree, PLUGIN_DIR) ?: return@runCatching
            DocumentsContract.createDocument(ctx.contentResolver, dir, "application/octet-stream", INBOX) ?: return@runCatching
        }
        val items = Vault.read(ctx, file)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        for (t in taken) {
            val k = (0 until items.length()).firstOrNull { k ->
                val o = items.getJSONObject(k)
                o.optString("kind") == "hidden" && o.optString("path") == cfg.notePath && o.optInt("at") == t.at &&
                    (if (o.isNull("after")) null else o.optString("after")) == t.after &&
                    o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } } == t.lines
            }
            if (k != null) items.remove(k)
            else items.put(
                JSONObject().put("path", cfg.notePath).put("kind", "unhide")
                    .put("after", t.after ?: JSONObject.NULL).put("at", t.at).put("lines", JSONArray(t.lines)),
            )
        }
        Vault.write(ctx, file, items.toString())
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

    private val INBOX_LOCK = Any()

    /**
     * With background sync on: takes the widget's inbox into Task Cleaner's settings (data.json), as
     * Task Cleaner itself does when Obsidian opens. Sync carries data.json but not the inbox, so
     * otherwise tasks hidden on the widget would reach the other devices' hidden lists only once
     * Obsidian had been opened on the phone. Each note it changes gets a new "updated" time, so Task
     * Cleaner elsewhere (merging note by note, the newer winning) takes these lists. "unhide" items
     * (undone on the widget after being taken in) come off the list. [vaultPath] is the vault folder.
     */
    fun takeInInbox(vaultPath: String) = synchronized(INBOX_LOCK) {
        val dir = File(vaultPath, PLUGIN_DIR)
        val inbox = File(dir, INBOX)
        val data = File(dir, "data.json")
        if (!inbox.exists() || !data.exists()) return@synchronized
        val items = runCatching { JSONArray(inbox.readText()) }.getOrNull() ?: return@synchronized
        val settings = runCatching { JSONObject(data.readText()) }.getOrNull() ?: return@synchronized
        val hidden = settings.optJSONObject("hidden") ?: JSONObject().also { settings.put("hidden", it) }
        val updated = settings.optJSONObject("updated") ?: JSONObject().also { settings.put("updated", it) }
        val now = System.currentTimeMillis()
        fun lines(o: JSONObject) = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        for (k in 0 until items.length()) {
            val item = items.optJSONObject(k) ?: continue
            val path = item.optString("path").ifEmpty { continue }
            val taken = lines(item) ?: continue
            when (item.optString("kind")) {
                "hidden" -> {
                    val entry = JSONObject(item.toString()).apply {
                        remove("path")
                        if (!has("hiddenAt")) put("hiddenAt", now)
                    }
                    (hidden.optJSONArray(path) ?: JSONArray().also { hidden.put(path, it) }).put(entry)
                }
                "unhide" -> {
                    val list = hidden.optJSONArray(path) ?: continue
                    val at = (0 until list.length()).firstOrNull { i ->
                        val e = list.optJSONObject(i) ?: return@firstOrNull false
                        e.optString("kind") == "hidden" && e.optInt("at") == item.optInt("at") &&
                            e.opt("after").toString() == item.opt("after").toString() && lines(e) == taken
                    } ?: continue
                    list.remove(at)
                    if (list.length() == 0) hidden.remove(path)
                }
                else -> continue
            }
            updated.put(path, now)
        }
        // Written whole and swapped in, so Sync and Obsidian never see half a file.
        val tmp = File(dir, "data.json.tmp")
        tmp.writeText(settings.toString(2))
        if (tmp.renameTo(data)) inbox.delete() else tmp.delete()
    }

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
