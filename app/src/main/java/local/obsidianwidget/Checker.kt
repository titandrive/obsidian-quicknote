package local.obsidianwidget

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Checker plugin, done on the widget: a task ticked here is hidden (or deleted) the way Checker
 * does in Obsidian, after the same delay. Checker in Obsidian leaves changes made outside Obsidian
 * alone (they're the device's own to deal with), so the widget does it.
 *
 * Hidden tasks go into a comment block at the bottom of the note, which Checker reads too:
 *
 *   %% Checker: hidden items
 *   after: "- [ ] Eggs" @3
 *   - [x] Milk
 *   shown: "- [x] Call the dentist"
 *   %%
 *
 * "after:" starts a hidden item: the line it goes back under ("top" for the top of the note) and the
 * line number it was on; its lines follow. "shown:" is an item back in the note for now.
 */
object Checker {
    private val data = PluginData("checker")

    class Settings(val hide: Boolean, val delayMs: Long, val skipResetNotes: Boolean)

    /** Checker's settings, or null if it isn't in the vault. */
    fun settings(ctx: Context, cfg: WidgetConfig): Settings? {
        val json = data.settings(ctx, cfg.vaultTree) ?: return null
        return Settings(
            hide = json.optString("action", "hide") != "delete",
            delayMs = json.optLong("delayMs", 500),
            skipResetNotes = json.optBoolean("skipResetNotes", true),
        )
    }

    private val TASK_RESETTER_MARKER = Regex("\\[reset-tasks(?=[\\s:\\]])", RegexOption.IGNORE_CASE)

    /**
     * A task was just ticked on the widget: after the delay, takes it out of the note (with what's
     * nested under it), into the block if hiding. If it was unticked again in the meantime, it stays.
     */
    fun ticked(ctx: Context, cfg: WidgetConfig, text: String, line: Int, lock: Any) {
        val settings = settings(ctx, cfg) ?: return
        if (settings.skipResetNotes && TASK_RESETTER_MARKER.containsMatchIn(text)) return
        val app = ctx.applicationContext
        kotlin.concurrent.thread {
            Thread.sleep(settings.delayMs)
            synchronized(lock) {
                val now = Vault.read(app, cfg.uri) ?: return@thread
                val next = takeOut(now, text.split('\n')[line], line, settings.hide) ?: return@thread
                Vault.write(app, cfg.uri, next)
            }
            NoteWidgetProvider.refreshNote(app, cfg.noteUri)
        }
    }

    /** [src] with the checked task [task] (the copy nearest [near]) taken out, or null if it's gone. */
    fun takeOut(src: String, task: String, near: Int, keep: Boolean): String? {
        val note = parse(src)
        val at = note.body.indices.filter { note.body[it] == task }.minByOrNull { kotlin.math.abs(it - near) } ?: return null
        val end = blockEnd(note.body, at)
        val lines = note.body.subList(at, end).toMutableList()
        note.body.subList(at, end).clear()
        if (keep) note.entries.add(Entry.Hidden(if (at > 0) note.body[at - 1] else null, at, lines))
        return serialize(note)
    }

    const val BLOCK_START = "%% Checker: hidden items"
    private const val BLOCK_END = "%%"

    sealed class Entry {
        data class Hidden(val after: String?, val at: Int?, val lines: MutableList<String>) : Entry()
        data class Shown(val line: String) : Entry()
    }

    class Note(val body: MutableList<String>, val entries: MutableList<Entry>, val hadBlock: Boolean)

    private val AFTER = Regex("^after: (.*?)(?: @(\\d+))?$")
    private val SHOWN = Regex("^shown: (.*)$")

    fun parse(text: String): Note {
        val lines = text.split('\n')
        val start = lines.indexOfFirst { it.trim() == BLOCK_START }
        if (start == -1) return Note(lines.toMutableList(), mutableListOf(), false)
        var end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == BLOCK_END } ?: lines.size
        val entries = mutableListOf<Entry>()
        for (line in lines.subList(start + 1, end.coerceAtMost(lines.size))) {
            val after = AFTER.find(line)
            val shown = SHOWN.find(line)
            when {
                after != null -> entries.add(Entry.Hidden(unquote(after.groupValues[1]), after.groupValues[2].toIntOrNull(), mutableListOf()))
                shown != null -> entries.add(Entry.Shown(unquote(shown.groupValues[1]) ?: ""))
                else -> when (val last = entries.lastOrNull()) {
                    is Entry.Hidden -> last.lines.add(line)
                    else -> if (line.isNotBlank()) entries.add(Entry.Hidden(null, null, mutableListOf(line)))
                }
            }
        }
        val body = (lines.subList(0, start) + if (end + 1 <= lines.size) lines.subList(end + 1, lines.size) else emptyList()).toMutableList()
        entries.removeAll { it is Entry.Hidden && it.lines.isEmpty() }
        return Note(body, entries, true)
    }

    /** The note's text: its lines, then the block if it has items (on its own, after a blank line). */
    fun serialize(note: Note): String {
        if (note.entries.isEmpty() && !note.hadBlock) return note.body.joinToString("\n")
        val body = note.body.toMutableList()
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
        if (note.entries.isEmpty()) return body.joinToString("\n") + "\n"
        val block = note.entries.flatMap { e ->
            when (e) {
                is Entry.Hidden -> listOf("after: ${quote(e.after)}" + (e.at?.let { " @$it" } ?: "")) + e.lines
                is Entry.Shown -> listOf("shown: ${quote(e.line)}")
            }
        }
        return (body + (if (body.isNotEmpty()) listOf("") else emptyList()) + BLOCK_START + block + BLOCK_END).joinToString("\n") + "\n"
    }

    /** The line after a task and the lines nested (indented) under it. */
    fun blockEnd(lines: List<String>, at: Int): Int {
        val indent = indentOf(lines[at])
        var end = at + 1
        while (end < lines.size && lines[end].isNotBlank() && indentOf(lines[end]) > indent) end++
        return end
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4L else 1L }.toInt()

    private fun quote(line: String?) = if (line == null) "top" else JSONObject.quote(line)

    private fun unquote(text: String): String? {
        if (text == "top") return null
        return runCatching { JSONTokener(text).nextValue() as String }.getOrNull() ?: text
    }
}
