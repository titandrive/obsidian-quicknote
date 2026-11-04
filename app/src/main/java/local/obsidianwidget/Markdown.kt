package local.obsidianwidget

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * One row of the widget's list. [line] is the 0-based source line it came from, so a tap can
 * toggle that task or open the editor there.
 */
class Block(
    val text: CharSequence,
    val line: Int,
    val task: Boolean = false,
    val code: Boolean = false,
    val rule: Boolean = false,
    /** A table: rows of styled cells, the header first. [text] then holds a plain fallback. */
    val table: Table? = null,
)

/** A markdown table: [rows] of cells (the first is the header), and each column's alignment. */
class Table(val rows: List<List<CharSequence>>, val align: List<Layout.Alignment>)

/**
 * Turns Obsidian-flavoured markdown into styled rows for a widget. Only uses framework spans,
 * since those are the ones that survive being sent to the launcher.
 */
object Markdown {
    val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    val TASK = Regex("^(\\s*)[-*+] \\[(.)] ?(.*)$")
    val BULLET = Regex("^(\\s*)[-*+] (.*)$")
    val NUMBERED = Regex("^(\\s*)(\\d+[.)]) (.*)$")
    val QUOTE = Regex("^\\s*>\\s?(.*)$")
    val CALLOUT = Regex("^\\[!(\\w+)][+-]?\\s*(.*)$")
    val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    val FENCE = Regex("^\\s*(```|~~~)")
    val TASK_BOX = Regex("^(\\s*[-*+] \\[)(.)(])")

    val INLINE = Regex(
        "(?<code>`[^`]+`)" +
            "|(?<comment>%%.*?%%)" +
            "|(?<wiki>!?\\[\\[[^\\]]+]])" +
            "|(?<link>!?\\[[^\\]]*]\\([^)]*\\))" +
            "|(?<bold>\\*\\*(?=\\S).+?(?<=\\S)\\*\\*|__(?=\\S).+?(?<=\\S)__)" +
            "|(?<strike>~~(?=\\S).+?(?<=\\S)~~)" +
            "|(?<mark>==(?=\\S).+?(?<=\\S)==)" +
            "|(?<italic>\\*(?=\\S)[^*]+?(?<=\\S)\\*|(?<!\\w)_(?=\\S)[^_]+?(?<=\\S)_(?!\\w))" +
            "|(?<url>https?://[^\\s)>\\]]+)" +
            "|(?<tag>(?<![\\w#&/])#[\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*)"
    )

    /** [indentPx] is how far one nesting level of a list is pushed in. */
    fun render(src: String, p: Palette, indentPx: Int): List<Block> {
        val lines = src.split('\n').map { it.removeSuffix("\r") }
        val out = ArrayList<Block>()
        var i = 0

        // Front matter isn't shown in reading view either.
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) i = end + 1
        }

        var calloutColor: Int? = null
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (FENCE.containsMatchIn(line)) {
                val fence = FENCE.find(line)!!.groupValues[1]
                val start = i
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body.add(lines[i++])
                i++
                val sb = SpannableStringBuilder(body.joinToString("\n"))
                span(sb, 0, TypefaceSpan("monospace"), ForegroundColorSpan(p.code), RelativeSizeSpan(0.9f))
                out.add(Block(sb, start, code = true))
                continue
            }

            if (trimmed == "%%") {
                i++
                while (i < lines.size && lines[i].trim() != "%%") i++
                i++
                continue
            }

            if (trimmed.isEmpty()) {
                calloutColor = null
                out.add(Block(spacer(), i))
                i++
                continue
            }

            // A table: a row of cells with a "|---|---|" rule under it, then rows until one has no pipe.
            if (line.contains('|') && lines.getOrNull(i + 1)?.let { LivePreview.TABLE_RULE.matches(it) } == true) {
                val start = i
                val ruleCells = cells(lines[i + 1])
                val align = ruleCells.map { c ->
                    val t = c.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> Layout.Alignment.ALIGN_CENTER
                        t.endsWith(":") -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                }
                val raw = mutableListOf(cells(line))
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) raw.add(cells(lines[i++]))
                val columns = raw.maxOf { it.size }
                val rows = raw.mapIndexed { r, row ->
                    List(columns) { c ->
                        val cell = inline(row.getOrNull(c).orEmpty().trim(), p)
                        if (r == 0) span(cell, 0, StyleSpan(Typeface.BOLD), ForegroundColorSpan(p.headings[0]))
                        cell
                    }
                }
                val fallback = rows.joinToString("\n") { it.joinToString("  ·  ") }
                out.add(Block(fallback, start, table = Table(rows, List(columns) { align.getOrElse(it) { Layout.Alignment.ALIGN_NORMAL } })))
                continue
            }

            val quote = QUOTE.find(line)
            if (quote == null) calloutColor = null
            out.add(
                when {
                    quote != null -> {
                        val inner = quote.groupValues[1]
                        val callout = CALLOUT.find(inner)
                        if (callout != null) {
                            val type = callout.groupValues[1].lowercase()
                            val color = calloutColor(type, p)
                            calloutColor = color
                            val title = callout.groupValues[2].ifEmpty { type.replaceFirstChar { it.uppercase() } }
                            val sb = inline(title, p)
                            span(sb, 0, StyleSpan(Typeface.BOLD), ForegroundColorSpan(color), QuoteSpan(color))
                            Block(sb, i)
                        } else {
                            val sb = inline(inner, p)
                            if (sb.isEmpty()) sb.append(' ')
                            span(sb, 0, QuoteSpan(calloutColor ?: p.quote))
                            if (calloutColor == null) span(sb, 0, ForegroundColorSpan(p.muted))
                            Block(sb, i)
                        }
                    }
                    RULE.matches(line) -> Block("", i, rule = true)
                    HEADING.matches(line) -> {
                        val m = HEADING.find(line)!!
                        val level = m.groupValues[1].length
                        val sb = inline(m.groupValues[2], p)
                        span(sb, 0, StyleSpan(Typeface.BOLD), RelativeSizeSpan(p.headingSizes[level - 1]), ForegroundColorSpan(p.headings[level - 1]))
                        Block(sb, i)
                    }
                    TASK.matches(line) -> {
                        val m = TASK.find(line)!!
                        val mark = m.groupValues[2]
                        val done = mark != " "
                        val sb = SpannableStringBuilder(when (mark) { " " -> "☐ "; "-" -> "☒ "; else -> "☑ " })
                        span(sb, 0, ForegroundColorSpan(if (done) p.faint else p.checkbox))
                        val boxEnd = sb.length
                        sb.append(inline(m.groupValues[3], p))
                        if (mark == "x" || mark == "X" || mark == "-") {
                            sb.setSpan(StrikethroughSpan(), boxEnd, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(ForegroundColorSpan(p.faint), boxEnd, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        indent(sb, depth(m.groupValues[1]), indentPx)
                        Block(sb, i, task = true)
                    }
                    BULLET.matches(line) -> {
                        val m = BULLET.find(line)!!
                        val sb = SpannableStringBuilder("•  ")
                        span(sb, 0, ForegroundColorSpan(p.marker))
                        sb.append(inline(m.groupValues[2], p))
                        indent(sb, depth(m.groupValues[1]), indentPx)
                        Block(sb, i)
                    }
                    NUMBERED.matches(line) -> {
                        val m = NUMBERED.find(line)!!
                        val sb = SpannableStringBuilder(m.groupValues[2] + " ")
                        span(sb, 0, ForegroundColorSpan(p.marker))
                        sb.append(inline(m.groupValues[3], p))
                        indent(sb, depth(m.groupValues[1]), indentPx)
                        Block(sb, i)
                    }
                    else -> Block(inline(line, p), i)
                }
            )
            i++
        }
        // Drop trailing spacers so the note doesn't end in a gap.
        while (out.isNotEmpty() && out.last().text.isBlank() && !out.last().rule) out.removeAt(out.lastIndex)
        return out
    }

    fun inline(s: String, p: Palette): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var last = 0
        for (m in INLINE.findAll(s)) {
            out.append(s, last, m.range.first)
            last = m.range.last + 1
            val t = m.value
            val g = m.groups
            val start = out.length
            when {
                g["code"] != null -> {
                    out.append(t.substring(1, t.length - 1))
                    span(out, start, TypefaceSpan("monospace"), ForegroundColorSpan(p.code), BackgroundColorSpan(p.codeBg))
                }
                g["comment"] != null -> Unit
                g["wiki"] != null -> {
                    val inner = t.removePrefix("!").removePrefix("[[").removeSuffix("]]")
                    val alias = inner.substringAfter('|', "")
                    out.append(alias.ifEmpty { inner.substringBefore('|').replace("#", " › ").trim() })
                    span(out, start, ForegroundColorSpan(p.link))
                }
                g["link"] != null -> {
                    out.append(inline(t.removePrefix("!").substringAfter('[').substringBeforeLast("]("), p))
                    span(out, start, ForegroundColorSpan(p.link))
                }
                g["bold"] != null -> {
                    out.append(inline(t.substring(2, t.length - 2), p))
                    span(out, start, StyleSpan(Typeface.BOLD), ForegroundColorSpan(p.bold))
                }
                g["strike"] != null -> {
                    out.append(inline(t.substring(2, t.length - 2), p))
                    span(out, start, StrikethroughSpan())
                }
                g["mark"] != null -> {
                    out.append(inline(t.substring(2, t.length - 2), p))
                    span(out, start, BackgroundColorSpan(p.highlight))
                }
                g["italic"] != null -> {
                    out.append(inline(t.substring(1, t.length - 1), p))
                    span(out, start, StyleSpan(Typeface.ITALIC), ForegroundColorSpan(p.italic))
                }
                g["url"] != null -> {
                    out.append(t)
                    span(out, start, ForegroundColorSpan(p.link))
                }
                g["tag"] != null -> {
                    out.append(t)
                    span(out, start, ForegroundColorSpan(p.tag), BackgroundColorSpan(withAlpha(p.tag, 0x26)))
                }
            }
        }
        out.append(s, last, s.length)
        return out
    }

    /** A table row's cells: split on pipes that aren't escaped, without the ones at the ends. */
    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|"))

    /** Flips the checkbox on [line] of [src]; returns null if that line isn't a task any more. */
    fun toggleTask(src: String, line: Int): String? {
        val lines = src.split('\n').toMutableList()
        val target = lines.getOrNull(line) ?: return null
        val m = TASK_BOX.find(target) ?: return null
        val next = if (m.groupValues[2] == " ") "x" else " "
        lines[line] = target.replaceRange(m.groups[2]!!.range, next)
        return lines.joinToString("\n")
    }

    private fun spacer(): CharSequence = SpannableStringBuilder(" ").also { span(it, 0, RelativeSizeSpan(0.5f)) }

    /** Nesting level from leading whitespace, as Obsidian counts it: a tab, or up to 4 spaces, per level. */
    private fun depth(lead: String): Int = lead.count { it == '\t' } + (lead.count { it == ' ' } + 3) / 4

    private fun indent(sb: SpannableStringBuilder, depth: Int, px: Int) {
        // Hang wrapped lines under the text, past the bullet or checkbox.
        val first = depth * px
        sb.setSpan(LeadingMarginSpan.Standard(first, first + px), 0, sb.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }

    private fun span(sb: SpannableStringBuilder, start: Int, vararg spans: Any) {
        for (s in spans) sb.setSpan(s, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    // Colours of Obsidian's built-in callout types.
    fun calloutColor(type: String, p: Palette): Int = when (type) {
        "note" -> Color.rgb(68, 138, 255)
        "abstract", "summary", "tldr", "info", "todo" -> Color.rgb(0, 176, 255)
        "tip", "hint", "important" -> Color.rgb(0, 191, 165)
        "success", "check", "done" -> Color.rgb(8, 185, 78)
        "question", "help", "faq", "warning", "caution", "attention" -> Color.rgb(236, 117, 0)
        "failure", "fail", "missing", "danger", "error", "bug" -> Color.rgb(233, 49, 71)
        "example" -> Color.rgb(120, 82, 238)
        "quote", "cite" -> Color.rgb(158, 158, 158)
        else -> p.accent
    }
}
