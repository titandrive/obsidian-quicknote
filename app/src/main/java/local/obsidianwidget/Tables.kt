package local.obsidianwidget

/**
 * Markdown tables as the editor edits them, like Obsidian's table editor: a cell at a time, and
 * whole rows and columns. Edits rewrite only the lines they touch, as "| a | b |".
 */
object Tables {
    /**
     * A table in the text: its header on line [start], the "|---|" rule under it, and rows through
     * line [end]. [rows] holds each row's cells as written, the header first; [rule] the rule's.
     */
    class Table(val start: Int, val end: Int, val rows: List<List<String>>, val rule: List<String>) {
        val columns get() = rule.size

        /** The text line of row [r] (0 is the header). */
        fun line(r: Int) = if (r == 0) start else start + r + 1
    }

    /** The tables in [text], other than ones in code blocks or with lines in [hidden] (folded). */
    fun find(text: String, hidden: Set<Int> = emptySet()): List<Table> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val out = ArrayList<Table>()
        var fence = false
        var i = 0
        while (i < lines.size) {
            if (Markdown.FENCE.containsMatchIn(lines[i])) fence = !fence
            if (!fence && lines[i].contains('|') && lines.getOrNull(i + 1)?.let { LivePreview.TABLE_RULE.matches(it) } == true) {
                val rule = cells(lines[i + 1])
                val rows = mutableListOf(cells(lines[i]))
                var j = i + 2
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) rows.add(cells(lines[j++]))
                val columns = rule.size
                val table = Table(i, j - 1, rows.map { r -> List(columns) { r.getOrNull(it)?.trim().orEmpty() } }, rule.map { it.trim() })
                if ((i until j).none { it in hidden }) out.add(table)
                i = j
                continue
            }
            i++
        }
        return out
    }

    /** A row's cells, split at pipes that aren't escaped ("\|"). */
    fun cells(row: String): List<String> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(Regex("(?<!\\\\)\\|"))
    }

    /** How column [c] is aligned, from its rule: "left", "center" or "right". */
    fun align(table: Table, c: Int): String {
        val r = table.rule.getOrNull(c).orEmpty()
        return when {
            r.startsWith(":") && r.endsWith(":") -> "center"
            r.endsWith(":") -> "right"
            else -> "left"
        }
    }

    /** A cell's text for editing: escaped pipes shown as plain ones. */
    fun unescape(cell: String) = cell.replace("\\|", "|")

    private fun escape(cell: String) = cell.replace("\n", " ").replace(Regex("(?<!\\\\)\\|"), "\\\\|")

    private fun rowLine(cells: List<String>) = "| " + cells.joinToString(" | ") { it.trim() } + " |"

    private fun ruleLine(rule: List<String>) = "| " + rule.joinToString(" | ") { it.ifBlank { "---" } } + " |"

    /** [text] with lines [from] through [to] replaced by [with]. */
    private fun replace(text: String, from: Int, to: Int, with: List<String>): String {
        val lines = text.split('\n').toMutableList()
        repeat(to - from + 1) { lines.removeAt(from) }
        lines.addAll(from, with)
        return lines.joinToString("\n")
    }

    /** Sets cell [c] of row [r] to [value]. */
    fun setCell(text: String, t: Table, r: Int, c: Int, value: String): String {
        val cells = t.rows[r].toMutableList()
        cells[c] = escape(value)
        return replace(text, t.line(r), t.line(r), listOf(rowLine(cells)))
    }

    /** Adds an empty row after row [r] (after the header, it becomes the first row). */
    fun addRow(text: String, t: Table, r: Int): String {
        val at = t.line(r) + if (r == 0) 2 else 1
        val lines = text.split('\n').toMutableList()
        lines.add(at, rowLine(List(t.columns) { "" }))
        return lines.joinToString("\n")
    }

    /** Removes row [r]; the header stays, since a table needs one. */
    fun deleteRow(text: String, t: Table, r: Int): String =
        if (r == 0) text else replace(text, t.line(r), t.line(r), emptyList())

    /** Adds an empty column after column [c]. */
    fun addColumn(text: String, t: Table, c: Int): String = rewrite(text, t, t.rule.toMutableList().apply { add(c + 1, "---") }) { row ->
        row.toMutableList().apply { add(c + 1, "") }
    }

    /** Removes column [c], unless it's the only one. */
    fun deleteColumn(text: String, t: Table, c: Int): String =
        if (t.columns <= 1) text else rewrite(text, t, t.rule.toMutableList().apply { removeAt(c) }) { row ->
            row.toMutableList().apply { removeAt(c) }
        }

    /** Cycles column [c]'s alignment: left, center, right. */
    fun cycleAlign(text: String, t: Table, c: Int): String {
        val next = when (align(t, c)) {
            "left" -> ":---:"
            "center" -> "---:"
            else -> "---"
        }
        return rewrite(text, t, t.rule.toMutableList().apply { set(c, next) }) { it }
    }

    private fun rewrite(text: String, t: Table, rule: List<String>, row: (List<String>) -> List<String>): String {
        val lines = listOf(rowLine(row(t.rows[0])), ruleLine(rule)) + t.rows.drop(1).map { rowLine(row(it)) }
        return replace(text, t.start, t.end, lines)
    }
}
