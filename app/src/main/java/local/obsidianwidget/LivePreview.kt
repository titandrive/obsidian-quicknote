package local.obsidianwidget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Styles markdown as it's typed, like Obsidian's Live Preview. The text itself is untouched:
 * markup on lines the cursor isn't on is shrunk to nothing, and on the cursor's lines it's shown
 * faintly so it can be edited. List markers are drawn as bullets and checkboxes by swapping single
 * characters, so every character still keeps its offset and the cursor moves normally.
 */
class LivePreview(
    private val p: Palette,
    private val baseSp: Float,
    /** Lines (0-based) the cursor or selection is on. */
    private val active: IntRange,
    /** Cursor offset; a list marker it sits in is shown as typed so it can be edited. */
    private val cursor: Int,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(annotate(text.text), OffsetMapping.Identity)

    private val hidden = SpanStyle(color = Color.Transparent, fontSize = 0.01.sp, letterSpacing = 0.sp)
    private val faint = SpanStyle(color = Color(p.faint))
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, color = Color(p.code), background = Color(p.codeBg))

    fun annotate(text: String): AnnotatedString {
        val b = AnnotatedString.Builder(text)
        // What's displayed: the text with list markers swapped for bullets and boxes, same length.
        val shown = text.toCharArray()
        val lines = text.split('\n')
        var offset = 0
        var fence: String? = null
        var frontMatter = lines.firstOrNull()?.trim() == "---"
        var callout: Color? = null

        for ((i, raw) in lines.withIndex()) {
            val line = raw.removeSuffix("\r")
            val start = offset
            offset += raw.length + 1
            val on = i in active
            fun style(s: SpanStyle, from: Int = 0, to: Int = line.length) {
                if (to > from) b.addStyle(s, start + from, start + to)
            }
            fun markup(from: Int, to: Int) = style(if (on) faint else hidden, from, to)
            /** Whether the cursor is in [from, to] of this line, i.e. editing that marker. */
            fun editing(from: Int, to: Int) = cursor in start + from..start + to

            if (frontMatter) {
                style(faint.copy(fontSize = (baseSp * 0.85f).sp))
                if (i > 0 && line.trim() == "---") frontMatter = false
                continue
            }

            val fenceMatch = Markdown.FENCE.find(line)
            if (fence != null || fenceMatch != null) {
                style(code)
                if (fenceMatch != null) {
                    style(faint)
                    fence = if (fence == null) fenceMatch.groupValues[1] else if (line.trimStart().startsWith(fence!!)) null else fence
                }
                continue
            }

            if (line.isBlank()) {
                callout = null
                continue
            }

            val quote = Markdown.QUOTE.find(line)
            if (quote == null) callout = null
            when {
                quote != null -> {
                    val innerStart = quote.groups[1]!!.range.first.coerceAtMost(line.length)
                    val gt = line.indexOf('>')
                    val inner = line.substring(innerStart)
                    val c = Markdown.CALLOUT.find(inner)
                    if (c != null) callout = Color(Markdown.calloutColor(c.groupValues[1].lowercase(), p))
                    // ">" becomes a bar in the quote's (or callout's) colour.
                    if (!editing(gt, gt + 1)) {
                        shown[start + gt] = '▎'
                        style(SpanStyle(color = callout ?: Color(p.quote), fontWeight = FontWeight.Bold), gt, gt + 1)
                    } else {
                        markup(0, innerStart)
                    }
                    if (c != null) {
                        val color = callout!!
                        val typeStart = innerStart + 2
                        val typeEnd = typeStart + c.groupValues[1].length
                        val titleStart = innerStart + c.groups[2]!!.range.first
                        style(SpanStyle(color = color, fontWeight = FontWeight.Bold), innerStart)
                        if (editing(innerStart, typeEnd + 1)) {
                            markup(innerStart, titleStart)
                        } else if (c.groupValues[2].isBlank()) {
                            // No title: Obsidian shows the type, e.g. "[!note]" reads "Note".
                            style(hidden, innerStart, typeStart)
                            shown[start + typeStart] = shown[start + typeStart].uppercaseChar()
                            style(hidden, typeEnd, line.length)
                        } else {
                            style(hidden, innerStart, titleStart)
                        }
                    } else {
                        style(SpanStyle(color = if (callout != null) Color(p.text) else Color(p.muted)), innerStart)
                        inline(b, line, start, innerStart, line.length, on)
                    }
                }
                Markdown.RULE.matches(line) -> {
                    style(faint)
                    // "---" is drawn as a line while the cursor is elsewhere.
                    if (!on) line.forEachIndexed { k, ch -> if (!ch.isWhitespace()) shown[start + k] = '─' }
                }
                Markdown.HEADING.matches(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val textStart = line.indexOfFirst { it != '#' && !it.isWhitespace() }.let { if (it < 0) line.length else it }
                    style(SpanStyle(
                        fontSize = (baseSp * p.headingSizes[level - 1]).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(p.headings[level - 1]),
                    ))
                    markup(0, textStart)
                    inline(b, line, start, textStart, line.length, on)
                }
                Markdown.TASK.matches(line) -> {
                    val m = Markdown.TASK.find(line)!!
                    val content = m.groups[3]!!.range.first.coerceAtMost(line.length)
                    val done = m.groupValues[2] != " "
                    val marker = m.groups[1]!!.range.last + 1
                    // "- [ ] " is shown as "☐ ": the box replaces "[", the rest collapses.
                    val box = line.indexOf('[', marker)
                    // The cursor at the start of the task's text still shows the box; only inside the marker shows "- [ ]".
                    if (box >= 0 && !editing(marker, content - 1)) {
                        shown[start + box] = when (m.groupValues[2]) {
                            " " -> '☐'
                            "-" -> '☒'
                            else -> '☑'
                        }
                        style(hidden, marker, box)
                        style(hidden, box + 1, box + 3)
                        style(SpanStyle(color = Color(if (done) p.faint else p.checkbox), fontSize = (baseSp * 1.15f).sp), box, box + 1)
                    } else {
                        style(SpanStyle(color = Color(if (done) p.faint else p.checkbox), fontFamily = FontFamily.Monospace), marker, content)
                    }
                    if (m.groupValues[2] in setOf("x", "X", "-")) {
                        style(SpanStyle(color = Color(p.faint), textDecoration = TextDecoration.LineThrough), content)
                    }
                    inline(b, line, start, content, line.length, on)
                }
                Markdown.BULLET.matches(line) || Markdown.NUMBERED.matches(line) -> {
                    val bullet = Markdown.BULLET.find(line)
                    val m = bullet ?: Markdown.NUMBERED.find(line)!!
                    val content = m.groups[m.groupValues.size - 1]!!.range.first.coerceAtMost(line.length)
                    val marker = m.groups[1]!!.range.last + 1
                    style(SpanStyle(color = Color(p.marker), fontWeight = FontWeight.Bold), marker, content)
                    if (bullet != null && !editing(marker, marker + 1)) shown[start + marker] = '•'
                    inline(b, line, start, content, line.length, on)
                }
                else -> inline(b, line, start, 0, line.length, on)
            }
        }
        val styled = b.toAnnotatedString()
        return AnnotatedString(String(shown), styled.spanStyles, styled.paragraphStyles)
    }

    /** Styles bold, links, tags and the rest within [from, to) of [line], which starts at [base] in the text. */
    private fun inline(b: AnnotatedString.Builder, line: String, base: Int, from: Int, to: Int, on: Boolean) {
        if (to <= from) return
        val part = line.substring(from, to)
        for (m in Markdown.INLINE.findAll(part)) {
            val s = base + from + m.range.first
            val e = base + from + m.range.last + 1
            val t = m.value
            val g = m.groups
            fun add(style: SpanStyle, a: Int, z: Int) { if (z > a) b.addStyle(style, a, z) }
            fun markup(a: Int, z: Int) = add(if (on) faint else hidden, a, z)
            /** Marks [open] and [close] chars at each end as markup and styles what's between. */
            fun wrap(open: Int, close: Int, style: SpanStyle, recurse: Boolean = true) {
                markup(s, s + open)
                markup(e - close, e)
                add(style, s + open, e - close)
                if (recurse) inline(b, line, base, from + m.range.first + open, from + m.range.last + 1 - close, on)
            }
            when {
                g["code"] != null -> wrap(1, 1, code, recurse = false)
                g["comment"] != null -> add(faint, s, e)
                g["wiki"] != null -> {
                    val open = if (t.startsWith("!")) 3 else 2
                    val bar = t.indexOf('|')
                    // [[note|alias]] shows only the alias.
                    markup(s, if (bar > 0) s + bar + 1 else s + open)
                    markup(e - 2, e)
                    add(SpanStyle(color = Color(p.link)), s, e - 2)
                }
                g["link"] != null -> {
                    val textEnd = t.lastIndexOf("](")
                    markup(s, s + t.indexOf('[') + 1)
                    markup(s + textEnd, e)
                    add(SpanStyle(color = Color(p.link)), s, s + textEnd)
                }
                g["bold"] != null -> wrap(2, 2, SpanStyle(fontWeight = FontWeight.Bold, color = Color(p.bold)))
                g["strike"] != null -> wrap(2, 2, SpanStyle(textDecoration = TextDecoration.LineThrough))
                g["mark"] != null -> wrap(2, 2, SpanStyle(background = Color(p.highlight)))
                g["italic"] != null -> wrap(1, 1, SpanStyle(fontStyle = FontStyle.Italic, color = Color(p.italic)))
                g["url"] != null -> add(SpanStyle(color = Color(p.link)), s, e)
                g["tag"] != null -> add(SpanStyle(color = Color(p.tag), background = Color(p.tag).copy(alpha = 0.15f)), s, e)
            }
        }
    }

    companion object {
        /** The 0-based lines touched by the selection [start, end] in [text]. */
        fun activeLines(text: String, start: Int, end: Int): IntRange {
            fun lineOf(i: Int) = text.subSequence(0, i.coerceIn(0, text.length)).count { it == '\n' }
            return lineOf(minOf(start, end))..lineOf(maxOf(start, end))
        }
    }
}
