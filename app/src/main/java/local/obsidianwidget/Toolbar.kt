package local.obsidianwidget

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONObject

/**
 * The editor's toolbar: the same buttons as Obsidian's mobile toolbar, in the order the vault's
 * .obsidian/app.json lists them ("mobileToolbarCommands"). Commands the editor can't do here,
 * such as attaching a file or configuring the toolbar, are left out.
 */
object Toolbar {
    class Command(val id: String, val label: String, val icon: Int)

    private val COMMANDS = listOf(
        Command("editor:undo", "Undo", R.drawable.ic_tb_undo),
        Command("editor:redo", "Redo", R.drawable.ic_tb_redo),
        Command("editor:unindent-list", "Outdent", R.drawable.ic_tb_outdent),
        Command("editor:indent-list", "Indent", R.drawable.ic_tb_indent),
        Command("editor:toggle-bullet-list", "Bullet list", R.drawable.ic_tb_bullets),
        Command("editor:toggle-numbered-list", "Numbered list", R.drawable.ic_tb_numbers),
        Command("editor:toggle-checklist-status", "Checkbox", R.drawable.ic_tb_checkbox),
        Command("editor:toggle-bold", "Bold", R.drawable.ic_tb_bold),
        Command("editor:toggle-italics", "Italic", R.drawable.ic_tb_italic),
        Command("editor:toggle-strikethrough", "Strikethrough", R.drawable.ic_tb_strike),
        Command("editor:toggle-highlight", "Highlight", R.drawable.ic_tb_highlight),
        Command("editor:toggle-code", "Code", R.drawable.ic_tb_code),
        Command("editor:toggle-blockquote", "Quote", R.drawable.ic_tb_quote),
        Command("editor:insert-link", "Link", R.drawable.ic_tb_link),
        Command("editor:insert-wikilink", "Internal link", R.drawable.ic_tb_wikilink),
        Command("editor:insert-tag", "Tag", R.drawable.ic_tb_tag),
        Command("editor:toggle-heading", "Heading", R.drawable.ic_tb_heading),
        Command("editor:insert-horizontal-rule", "Horizontal rule", R.drawable.ic_tb_rule),
        Command("editor:insert-codeblock", "Code block", R.drawable.ic_tb_codeblock),
        Command("editor:insert-callout", "Callout", R.drawable.ic_tb_callout),
    ).associateBy { it.id }

    /** Obsidian's own default, for vaults that never customised the toolbar. */
    private val DEFAULT = listOf(
        "editor:undo", "editor:redo", "editor:toggle-bold", "editor:toggle-italics", "editor:insert-wikilink",
        "editor:insert-tag", "editor:toggle-checklist-status", "editor:toggle-bullet-list", "editor:indent-list",
        "editor:unindent-list", "editor:toggle-highlight", "editor:toggle-code", "editor:toggle-blockquote",
    )

    /** The vault's toolbar, as far as this editor can do it. */
    fun load(ctx: Context): List<Command> {
        val ids = Vault.readPath(ctx, ".obsidian/app.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("mobileToolbarCommands")
            ?.let { a -> List(a.length()) { a.optString(it) } }
            ?: DEFAULT
        return ids.mapNotNull { COMMANDS[it] }
    }

    /** Applies a toolbar [id] (other than undo and redo) to the editor's text and selection. */
    fun apply(id: String, v: TextFieldValue): TextFieldValue = when (id) {
        "editor:toggle-bold" -> wrap(v, "**")
        "editor:toggle-italics" -> wrap(v, "*")
        "editor:toggle-strikethrough" -> wrap(v, "~~")
        "editor:toggle-highlight" -> wrap(v, "==")
        "editor:toggle-code" -> wrap(v, "`")
        "editor:indent-list" -> eachLine(v) { "\t" + it }
        "editor:unindent-list" -> eachLine(v) { line ->
            when {
                line.startsWith("\t") -> line.drop(1)
                else -> line.drop(line.takeWhile { it == ' ' }.length.coerceAtMost(4))
            }
        }
        "editor:toggle-bullet-list" -> eachLine(v) { line -> listMarker(line, "- ") }
        "editor:toggle-numbered-list" -> eachLine(v) { line -> listMarker(line, "1. ") }
        "editor:toggle-checklist-status" -> eachLine(v, ::checkbox)
        "editor:toggle-blockquote" -> eachLine(v) { line -> if (line.startsWith(">")) line.removePrefix(">").removePrefix(" ") else "> $line" }
        "editor:toggle-heading" -> eachLine(v) { line ->
            val level = line.takeWhile { it == '#' }.length
            val rest = line.drop(level).trimStart()
            // Cycles none → # → ## → ### → none, like tapping the heading button repeatedly.
            if (level in 1..2) "#".repeat(level + 1) + " " + rest else if (level == 0) "# $line" else rest
        }
        "editor:insert-link" -> {
            val t = selected(v)
            replaceSelection(v, "[$t]()", cursor = if (t.isEmpty()) 1 else t.length + 3)
        }
        "editor:insert-wikilink" -> {
            val t = selected(v)
            replaceSelection(v, "[[$t]]", cursor = if (t.isEmpty()) 2 else t.length + 4)
        }
        "editor:insert-tag" -> replaceSelection(v, "#" + selected(v), cursor = selected(v).length + 1)
        "editor:insert-horizontal-rule" -> replaceSelection(v, "\n---\n", cursor = 5)
        "editor:insert-codeblock" -> {
            val t = selected(v)
            replaceSelection(v, "```\n$t\n```", cursor = 4 + t.length)
        }
        "editor:insert-callout" -> {
            val t = selected(v)
            replaceSelection(v, "> [!note]\n> $t", cursor = 12 + t.length)
        }
        else -> v
    }

    private fun selected(v: TextFieldValue) = v.text.substring(v.selection.min, v.selection.max)

    private fun replaceSelection(v: TextFieldValue, with: String, cursor: Int): TextFieldValue {
        val s = v.selection.min
        val text = v.text.substring(0, s) + with + v.text.substring(v.selection.max)
        return TextFieldValue(text, TextRange(s + cursor))
    }

    /**
     * Bold, highlight and the like: wraps the selection in [mark], or unwraps it if it's already
     * wrapped. With nothing selected, inserts a pair with the cursor between.
     */
    private fun wrap(v: TextFieldValue, mark: String): TextFieldValue {
        val t = v.text
        val s = v.selection.min
        val e = v.selection.max
        val n = mark.length
        // Already wrapped just outside the selection: remove the marks.
        if (s >= n && e + n <= t.length && t.substring(s - n, s) == mark && t.substring(e, e + n) == mark) {
            val text = t.substring(0, s - n) + t.substring(s, e) + t.substring(e + n)
            return TextFieldValue(text, TextRange(s - n, e - n))
        }
        // The selection includes the marks: remove them.
        val sel = t.substring(s, e)
        if (sel.length >= 2 * n && sel.startsWith(mark) && sel.endsWith(mark)) {
            val inner = sel.substring(n, sel.length - n)
            return TextFieldValue(t.substring(0, s) + inner + t.substring(e), TextRange(s, s + inner.length))
        }
        val text = t.substring(0, s) + mark + sel + mark + t.substring(e)
        return TextFieldValue(text, if (s == e) TextRange(s + n) else TextRange(s + n, e + n))
    }

    /** Rewrites every line the selection touches with [f], keeping the cursor on the same text. */
    private fun eachLine(v: TextFieldValue, f: (String) -> String): TextFieldValue {
        val t = v.text
        val start = t.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(-1)).let { if (v.selection.min == 0) 0 else it + 1 }
        val end = t.indexOf('\n', v.selection.max).let { if (it < 0) t.length else it }
        val lines = t.substring(start, end).split('\n')
        val changed = lines.map(f)
        val text = t.substring(0, start) + changed.joinToString("\n") + t.substring(end)
        if (lines.size == 1) {
            // One line: move the cursor by however much the line grew or shrank before it.
            val delta = changed[0].length - lines[0].length
            val shift = { i: Int -> (i + delta).coerceIn(start, start + changed[0].length) }
            return TextFieldValue(text, TextRange(shift(v.selection.start), shift(v.selection.end)))
        }
        return TextFieldValue(text, TextRange(start, start + changed.joinToString("\n").length))
    }

    private val LIST_MARK = Regex("^(\\s*)([-*+] \\[.] |[-*+] |\\d+[.)] )?")

    /** Toggles [mark] ("- " or "1. ") on a line, replacing any other list marker it had. */
    private fun listMarker(line: String, mark: String): String {
        val m = LIST_MARK.find(line)!!
        val indent = m.groupValues[1]
        val current = m.groupValues[2]
        val rest = line.substring(m.value.length)
        val isSame = if (mark == "- ") current.matches(Regex("[-*+] ")) else current.matches(Regex("\\d+[.)] "))
        return if (isSame) indent + rest else indent + mark + rest
    }

    /** Obsidian's checkbox button: makes the line a task, then ticks and unticks it. */
    private fun checkbox(line: String): String {
        val m = LIST_MARK.find(line)!!
        val indent = m.groupValues[1]
        val current = m.groupValues[2]
        val rest = line.substring(m.value.length)
        return when {
            current.matches(Regex("[-*+] \\[ ] ")) -> indent + current.replace("[ ]", "[x]") + rest
            current.matches(Regex("[-*+] \\[.] ")) -> indent + current.replace(Regex("\\[.]"), "[ ]") + rest
            current.matches(Regex("[-*+] ")) -> indent + current + "[ ] " + rest
            else -> "$indent- [ ] $rest"
        }
    }

    /**
     * Undo history. Typing is grouped into steps by pauses, like most editors, rather than one
     * step per character; toolbar actions are always a step of their own.
     */
    class History {
        private val undo = ArrayDeque<TextFieldValue>()
        private val redo = ArrayDeque<TextFieldValue>()
        private var lastTyped = 0L

        val canUndo get() = undo.isNotEmpty()
        val canRedo get() = redo.isNotEmpty()

        /** Call before [new] replaces [old]. */
        fun record(old: TextFieldValue, new: TextFieldValue, action: Boolean = false) {
            if (old.text == new.text) return
            val now = SystemClock.uptimeMillis()
            val newWord = new.text.length > old.text.length && new.text.getOrNull(new.selection.start - 1)?.isWhitespace() == true
            if (action || now - lastTyped > 1000 || undo.isEmpty() || newWord) {
                undo.addLast(old)
                if (undo.size > 200) undo.removeFirst()
            }
            lastTyped = if (action) 0L else now
            redo.clear()
        }

        fun undo(current: TextFieldValue): TextFieldValue? = undo.removeLastOrNull()?.also { redo.addLast(current); lastTyped = 0L }

        fun redo(current: TextFieldValue): TextFieldValue? = redo.removeLastOrNull()?.also { undo.addLast(current); lastTyped = 0L }
    }
}
