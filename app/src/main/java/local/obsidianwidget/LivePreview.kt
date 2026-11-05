package local.obsidianwidget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
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
    /** Lines inside folded regions, left out of what's shown. */
    private val collapsed: Set<Int> = emptySet(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val shown = annotate(text.text)
        val cut = removed
        return TransformedText(shown, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = toShown(cut, offset)
            override fun transformedToOriginal(offset: Int) = toText(cut, offset)
        })
    }

    /**
     * The stretches of the text left out of what's shown, folded lines, as [start, end) pairs in
     * order; from the last [annotate].
     */
    @Volatile private var removed = IntArray(0)

    /** Where [offset] in the text is in what's shown; inside folded lines, where they were. */
    fun toShown(offset: Int) = toShown(removed, offset)

    /** Where [offset] in what's shown is in the text. */
    fun toText(offset: Int) = toText(removed, offset)

    private fun toShown(cut: IntArray, offset: Int): Int {
        var shift = 0
        for (k in cut.indices step 2) {
            if (offset >= cut[k + 1]) shift += cut[k + 1] - cut[k]
            else if (offset > cut[k]) return cut[k] - shift
            else break
        }
        return offset - shift
    }

    private fun toText(cut: IntArray, offset: Int): Int {
        var shift = 0
        for (k in cut.indices step 2) {
            if (offset > cut[k] - shift) shift += cut[k + 1] - cut[k] else break
        }
        return offset + shift
    }

    private val hidden = SpanStyle(color = Color.Transparent, fontSize = 0.01.sp, letterSpacing = 0.sp)
    private val faint = SpanStyle(color = Color(p.faint))
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, color = Color(p.code), background = Color(p.codeBg))

    /** Line-to-line state: inside a code fence, front matter, a callout or a table. */
    private data class Ctx(val fence: String?, val frontMatter: Boolean, val callout: Int?, val table: Boolean, val first: Boolean)

    /** Everything one line's styling depends on, so a styled line can be reused while none of it changes. */
    private data class Key(
        val line: String, val ctx: Ctx, val on: Boolean, val cursorCol: Int, val nextIsTableRule: Boolean,
        val p: Palette, val baseSp: Float,
    )

    private class Styled(val text: AnnotatedString, val next: Ctx)

    /**
     * Styles each line on its own, reusing lines styled before, so typing in a long note only
     * restyles the line being edited rather than the whole note on every keystroke.
     */
    fun annotate(text: String): AnnotatedString {
        val lines = text.split('\n')
        val out = AnnotatedString.Builder(text.length)
        val cut = ArrayList<Int>()
        // Each shown line's paragraph styles (callouts), applied once its line break is in, so they
        // take it in: one that stopped short left the break a paragraph of its own, an empty line.
        var pending: List<AnnotatedString.Range<ParagraphStyle>> = emptyList()
        var pendingStart = 0
        var shownAny = false
        var ctx = Ctx(null, lines.firstOrNull()?.trim() == "---", null, false, true)
        var offset = 0
        for ((i, raw) in lines.withIndex()) {
            val line = raw.removeSuffix("\r")
            val on = i in active
            val cursorCol = if (on && cursor in offset..offset + line.length) cursor - offset else -1
            val nextIsRule = lines.getOrNull(i + 1)?.let { TABLE_RULE.matches(it.removeSuffix("\r")) } == true
            val key = Key(line, ctx, on, cursorCol, nextIsRule, p, baseSp)
            val styled = synchronized(cache) { cache[key] } ?: styleLine(line, ctx, on, cursorCol, nextIsRule).also {
                synchronized(cache) { cache[key] = it }
            }
            if (i in collapsed) {
                // Folded away: left out of what's shown, with its line break (or, at the end of the
                // note, the break before it).
                val to = minOf(text.length, offset + raw.length + 1)
                if (cut.isNotEmpty() && cut.last() == offset) cut[cut.lastIndex] = to else { cut.add(offset); cut.add(to) }
                if (i == lines.lastIndex && cut[cut.lastIndex - 1] > 0) cut[cut.lastIndex - 1]--
            } else {
                // The line break before this line, which belongs to the line shown before it.
                if (shownAny) out.append('\n')
                for (ps in pending) out.addStyle(ps.item, pendingStart + ps.start, out.length)
                shownAny = true
                pendingStart = out.length
                out.append(AnnotatedString(styled.text.text, styled.text.spanStyles))
                if (raw.length > line.length) out.append("\r")
                pending = styled.text.paragraphStyles
            }
            ctx = styled.next.copy(first = false)
            offset += raw.length + 1
        }
        for (ps in pending) out.addStyle(ps.item, pendingStart + ps.start, out.length)
        removed = cut.toIntArray()
        return out.toAnnotatedString()
    }

    private fun styleLine(line: String, ctx: Ctx, on: Boolean, cursorCol: Int, nextIsTableRule: Boolean): Styled {
        val b = AnnotatedString.Builder(line)
        // What's displayed: the line with list markers swapped for bullets and boxes, same length.
        val shown = line.toCharArray()
        var fence = ctx.fence
        var callout = ctx.callout?.let { Color(it) }
        var table = ctx.table
        fun style(s: SpanStyle, from: Int = 0, to: Int = line.length) {
            if (to > from) b.addStyle(s, from, to)
        }
        fun markup(from: Int, to: Int) = style(if (on) faint else hidden, from, to)
        /** Whether the cursor is in [from, to] of this line, i.e. editing that marker. */
        fun editing(from: Int, to: Int) = cursorCol in from..to
        fun done(next: Ctx = Ctx(fence, false, callout?.let { it.toArgb() }, table, false)): Styled {
            val styled = b.toAnnotatedString()
            return Styled(AnnotatedString(String(shown), styled.spanStyles, styled.paragraphStyles), next)
        }

        if (ctx.frontMatter) {
            style(faint.copy(fontSize = (baseSp * 0.85f).sp))
            val ends = !ctx.first && line.trim() == "---"
            return done(Ctx(null, !ends, null, false, false))
        }

        val fenceMatch = Markdown.FENCE.find(line)
        if (fence != null || fenceMatch != null) {
            style(code)
            if (fenceMatch != null) {
                style(faint)
                fence = if (fence == null) fenceMatch.groupValues[1] else if (line.trimStart().startsWith(fence)) null else fence
            }
            return done()
        }

        if (line.isBlank()) {
            callout = null
            table = false
            return done()
        }

        // Tables: a row of cells with a "|---|---|" rule under the first. The rule and the pipes fade,
        // the header row is bold.
        if (table || (line.contains('|') && nextIsTableRule)) {
            if (!line.contains('|')) {
                table = false
            } else {
                when {
                    TABLE_RULE.matches(line) -> style(faint)
                    !table -> {
                        style(SpanStyle(fontWeight = FontWeight.Bold, color = Color(p.headings[0])))
                        inline(b, line, 0, 0, line.length, cursorCol)
                    }
                    else -> inline(b, line, 0, 0, line.length, cursorCol)
                }
                line.forEachIndexed { k, ch -> if (ch == '|') style(SpanStyle(color = Color(p.faint)), k, k + 1) }
                table = true
                return done()
            }
        }

        // A tab draws almost zero-width, and letter spacing doesn't apply to it. Show indenting
        // tabs as an em space, widened to a list level as Obsidian shows them; the text keeps its tab.
        line.takeWhile { it == ' ' || it == '\t' }.forEachIndexed { k, ch ->
            if (ch == '\t') {
                shown[k] = '\u2003'
                style(SpanStyle(letterSpacing = 0.8.em), k, k + 1)
            }
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
                if (callout != null) {
                    // Inside a callout's box (drawn by the editor), padded as Obsidian pads it, with
                    // room for the icon beside the title, and the box's top padding above the title.
                    val left = CALLOUT_LEFT + if (c != null) CALLOUT_ICON + CALLOUT_GAP else 0f
                    b.addStyle(
                        ParagraphStyle(
                            textIndent = TextIndent(left.em, left.em),
                            lineHeight = if (c != null) (LINE_HEIGHT + CALLOUT_PAD).em else androidx.compose.ui.unit.TextUnit.Unspecified,
                            lineHeightStyle = if (c != null) LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None) else null,
                        ),
                        0, line.length,
                    )
                    if (editing(gt, gt + 1)) markup(0, innerStart) else style(hidden, 0, innerStart)
                } else if (!editing(gt, gt + 1)) {
                    // ">" becomes a bar in the quote's colour.
                    shown[gt] = '▎'
                    style(SpanStyle(color = Color(p.quote), fontWeight = FontWeight.Bold), gt, gt + 1)
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
                        shown[typeStart] = shown[typeStart].uppercaseChar()
                        style(hidden, typeEnd, line.length)
                    } else {
                        style(hidden, innerStart, titleStart)
                    }
                } else {
                    style(SpanStyle(color = if (callout != null) Color(p.text) else Color(p.muted)), innerStart)
                    inline(b, line, 0, innerStart, line.length, cursorCol)
                }
            }
            Markdown.RULE.matches(line) -> {
                style(faint)
                // "---" is drawn as a line while the cursor is elsewhere.
                if (!on) line.forEachIndexed { k, ch -> if (!ch.isWhitespace()) shown[k] = '─' }
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
                inline(b, line, 0, textStart, line.length, cursorCol)
            }
            Markdown.TASK.matches(line) -> {
                val m = Markdown.TASK.find(line)!!
                val content = m.groups[3]!!.range.first.coerceAtMost(line.length)
                val isDone = m.groupValues[2] != " "
                val marker = m.groups[1]!!.range.last + 1
                // "- [ ] " is shown as "☐ ": the box replaces "[", the rest collapses.
                val box = line.indexOf('[', marker)
                // The cursor at the start of the task's text still shows the box; only inside the marker shows "- [ ]".
                if (box >= 0 && !editing(marker, content - 1)) {
                    shown[box] = when (m.groupValues[2]) {
                        " " -> '☐'
                        "-" -> '☒'
                        else -> '☑'
                    }
                    style(hidden, marker, box)
                    style(hidden, box + 1, box + 3)
                    style(SpanStyle(color = Color(if (isDone) p.faint else p.checkbox), fontSize = (baseSp * 1.15f).sp), box, box + 1)
                } else {
                    style(SpanStyle(color = Color(if (isDone) p.faint else p.checkbox), fontFamily = FontFamily.Monospace), marker, content)
                }
                if (m.groupValues[2] in setOf("x", "X", "-")) {
                    style(SpanStyle(color = Color(p.faint), textDecoration = TextDecoration.LineThrough), content)
                }
                inline(b, line, 0, content, line.length, cursorCol)
            }
            Markdown.BULLET.matches(line) || Markdown.NUMBERED.matches(line) -> {
                val bullet = Markdown.BULLET.find(line)
                val m = bullet ?: Markdown.NUMBERED.find(line)!!
                val content = m.groups[m.groupValues.size - 1]!!.range.first.coerceAtMost(line.length)
                val marker = m.groups[1]!!.range.last + 1
                style(SpanStyle(color = Color(p.marker), fontWeight = FontWeight.Bold), marker, content)
                if (bullet != null && !editing(marker, marker + 1)) shown[marker] = '•'
                inline(b, line, 0, content, line.length, cursorCol)
            }
            else -> inline(b, line, 0, 0, line.length, cursorCol)
        }
        return done()
    }

    /**
     * Styles bold, links, tags and the rest within [from, to) of [line], which starts at [base] in
     * the text. As in Obsidian, an element's markup ("**", "==", "[[") shows only while the cursor
     * ([cursorCol], or -1 off this line) is in it or at its edge; otherwise it's hidden, even on
     * the cursor's own line.
     */
    private fun inline(b: AnnotatedString.Builder, line: String, base: Int, from: Int, to: Int, cursorCol: Int) {
        if (to <= from) return
        val part = line.substring(from, to)
        for (m in Markdown.INLINE.findAll(part)) {
            val s = base + from + m.range.first
            val e = base + from + m.range.last + 1
            val t = m.value
            val g = m.groups
            fun add(style: SpanStyle, a: Int, z: Int) { if (z > a) b.addStyle(style, a, z) }
            // In the element, start and end included, so the markup shows while typing right after it.
            val editingThis = cursorCol in (s - base)..(e - base)
            fun markup(a: Int, z: Int) = add(if (editingThis) faint else hidden, a, z)
            /** Marks [open] and [close] chars at each end as markup and styles what's between. */
            fun wrap(open: Int, close: Int, style: SpanStyle, recurse: Boolean = true) {
                markup(s, s + open)
                markup(e - close, e)
                add(style, s + open, e - close)
                if (recurse) inline(b, line, base, from + m.range.first + open, from + m.range.last + 1 - close, cursorCol)
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
        /** The editor's line height, in ems. */
        const val LINE_HEIGHT = 1.45f
        /** A callout's padding (top, and left of its text), its icon and the gap after it, in ems: Obsidian's CSS pixels for 16px text. */
        const val CALLOUT_PAD = 0.75f
        const val CALLOUT_LEFT = 1.5f
        const val CALLOUT_ICON = 1.125f
        const val CALLOUT_GAP = 0.25f

        /** A callout in the text: its title line, its last line shown, colour and icon. */
        class CalloutBox(val from: Int, val to: Int, val color: Int, val icon: String)

        /** The callouts in [text], for the editor to draw their boxes; lines in [collapsed] don't count. */
        fun calloutBoxes(text: String, p: Palette, collapsed: Set<Int>): List<CalloutBox> {
            val lines = text.split('\n')
            val out = ArrayList<CalloutBox>()
            var i = 0
            var fence = false
            while (i < lines.size) {
                if (Markdown.FENCE.containsMatchIn(lines[i])) fence = !fence
                val title = if (fence) null else Markdown.QUOTE.find(lines[i])?.let { Markdown.CALLOUT.find(it.groupValues[1]) }
                if (title == null) {
                    i++
                    continue
                }
                val type = title.groupValues[1]
                var to = i
                var j = i + 1
                while (j < lines.size) {
                    val q = Markdown.QUOTE.find(lines[j]) ?: break
                    if (Markdown.CALLOUT.containsMatchIn(q.groupValues[1])) break
                    if (j !in collapsed) to = j
                    j++
                }
                // One inside a folded section isn't shown at all.
                if (i !in collapsed) out.add(CalloutBox(i, to, p.calloutColor(type), Callouts.kind(type).icon))
                i = j
            }
            return out
        }

        /** Styled lines kept for reuse; old ones drop off once there are this many. */
        private val cache = object : LinkedHashMap<Key, Styled>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Styled>?) = size > 5000
        }

        /** The rule under a table's header: "|---|:---:|", pipes at the ends optional. */
        val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

        private val LIST_PREFIX = Regex("^((?:[ \\t]*>[ \\t]?)*)([ \\t]*)([-*+] \\[.] |[-*+] |(\\d+)([.)]) )?")

        /**
         * Obsidian's Enter key in lists: if [new] is [old] with a newline typed after a list
         * item, quote or task, the new line gets the next marker ("- ", "- [ ] ", "2. ", "> ").
         * On an item with nothing after its marker, Enter ends the list and removes the marker.
         */
        fun continueList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
            val t = new.text
            val pos = new.selection.start
            if (!new.selection.collapsed || pos < 1 || t[pos - 1] != '\n') return new
            // Exactly one new line break; the keyboard may also autocorrect the last word as Enter is pressed.
            if (t.count { it == '\n' } != old.text.count { it == '\n' } + 1) return new
            val lineStart = t.lastIndexOf('\n', pos - 2) + 1
            val line = t.substring(lineStart, pos - 1)
            val m = LIST_PREFIX.find(line) ?: return new
            val (quote, indent, marker) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            if (quote.isEmpty() && marker.isEmpty()) return new
            if (line.substring(m.value.length).isBlank()) {
                // An empty item: end the list, leaving the line empty, instead of starting another.
                return TextFieldValue(t.removeRange(lineStart, pos), TextRange(lineStart))
            }
            val next = when {
                marker.isEmpty() -> ""
                m.groupValues[4].isNotEmpty() -> "${m.groupValues[4].toInt() + 1}${m.groupValues[5]} "
                marker.length > 2 -> "${marker[0]} [ ] "
                else -> marker
            }
            val prefix = quote + indent + next
            return TextFieldValue(t.substring(0, pos) + prefix + t.substring(pos), TextRange(pos + prefix.length))
        }

        /** The 0-based lines touched by the selection [start, end] in [text]. */
        fun activeLines(text: String, start: Int, end: Int): IntRange {
            fun lineOf(i: Int) = text.subSequence(0, i.coerceIn(0, text.length)).count { it == '\n' }
            return lineOf(minOf(start, end))..lineOf(maxOf(start, end))
        }
    }
}
