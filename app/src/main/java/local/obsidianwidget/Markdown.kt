package local.obsidianwidget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.ReplacementSpan
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
    /** [NONE], or a foldable line, [OPEN] or [FOLDED]. */
    val fold: Int = NONE,
    /** A line of a callout, drawn in its box. */
    val callout: CalloutLine? = null,
    /** A Task Resetter button on this line: [NONE], [RESET_SECTION] or [RESET_ALL]. */
    val reset: Int = NONE,
) {
    fun copy(text: CharSequence = this.text, fold: Int = this.fold, callout: CalloutLine? = this.callout, reset: Int = this.reset) =
        Block(text, line, task, code, rule, table, fold, callout, reset)

    companion object {
        const val NONE = 0
        const val OPEN = 1
        const val FOLDED = 2
        const val RESET_SECTION = 1
        const val RESET_ALL = 2
    }
}

/**
 * Task Resetter's button ([reset-tasks] in a note), drawn as the plugin draws it:
 * an outlined button with its icon in the accent colour and a muted label. One character wide in the
 * text; [width] is how wide it's drawn, for the tap target over it.
 */
class ResetSpan(
    /** What it says: "Reset tasks", "Reset all tasks", "Reset H2 tasks", "Reset Morning". */
    val text: String, private val accent: Int, private val border: Int, private val label: Int,
    /** Just the icon, square, when the plugin is set to hide the label. */
    iconOnly: Boolean = false,
    /** The icon's name ([iconPath] is its drawing, in a 24-unit box), from icon: in the marker. */
    val icon: String = "rotate-ccw-square",
    private val iconPath: String? = null,
    /** The label was typed in the marker (label:), so it shows even when labels are hidden. */
    ownLabel: Boolean = false,
) : ReplacementSpan() {
    val iconOnly = iconOnly && !ownLabel

    /** Faded: nothing checked for it to reset (the plugin's "Dim when there's nothing to reset"). */
    var dim = false
    var width = 0
        private set

    private fun size(paint: Paint) = paint.textSize * 0.95f

    override fun getSize(paint: Paint, t: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val s = size(paint)
        val p = Paint(paint).apply { textSize = s }
        // Room for the button's height, so it doesn't run into the lines around it.
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val mid = (paint.ascent() + paint.descent()) / 2
            val h = s * 1.75f
            fm.ascent = minOf(fm.ascent, (mid - h / 2 - 1).toInt()); fm.top = minOf(fm.top, fm.ascent)
            fm.descent = maxOf(fm.descent, (mid + h / 2 + 1).toInt()); fm.bottom = maxOf(fm.bottom, fm.descent)
        }
        width = if (iconOnly) (s * 0.2f + s * 1.75f).toInt()
        else (s * 0.2f + s * 0.65f + s * 1.1f + s * 0.4f + p.measureText(text) + s * 0.75f).toInt()
        return width
    }

    override fun draw(c: Canvas, t: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val s = size(paint)
        val mid = y + (paint.ascent() + paint.descent()) / 2
        val h = s * 1.75f
        val left = x + s * 0.2f
        val box = RectF(left, mid - h / 2, x + width.toFloat(), mid + h / 2)
        val fade = if (dim) 0.6f else 1f
        fun faded(c: Int) = if (dim) android.graphics.Color.argb((android.graphics.Color.alpha(c) * fade).toInt(), android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)) else c
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.08f; color = faded(border) }
        c.drawRoundRect(box, s * 0.4f, s * 0.4f, line)
        // The icon (Lucide's rotate-ccw-square, or the marker's own), from its 24-unit box.
        val icon = if (iconOnly) s * 1.2f else s * 1.1f
        val iconLeft = if (iconOnly) left + (s * 1.75f - icon) / 2 else left + s * 0.65f
        val path = iconPath?.let { runCatching { androidx.core.graphics.PathParser.createPathFromPathData(it) }.getOrNull() }
            ?: Path(Callouts.icon("rotate-ccw-square"))
        path.transform(Matrix().apply { setScale(icon / 24f, icon / 24f); postTranslate(iconLeft, mid - icon / 2) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = icon / 12f; color = faded(accent)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        if (iconOnly) return
        val p = Paint(paint).apply { textSize = s; color = faded(label); isFakeBoldText = false; typeface = android.graphics.Typeface.create(paint.typeface, android.graphics.Typeface.NORMAL) }
        c.drawText(text, left + s * 0.65f + icon + s * 0.4f, mid - (p.ascent() + p.descent()) / 2, p)
    }
}

/**
 * A line of a callout: its colour; [icon] on the title line; [last] on the callout's last line
 * shown (the title, when it's folded or has nothing under it).
 */
class CalloutLine(val color: Int, val icon: String?, val last: Boolean = false)

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
    /** A task's box for Task Resetter, which also resets numbered tasks. */
    private val RESET_BOX = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+\\[)([^\\]])(])")
    /**
     * Task Resetter's marker: [reset-tasks], [reset-tasks all] (the whole note), [reset-tasks ##]
     * (every heading of that level) or [reset-tasks: Morning] (the heading named Morning).
     */
    val RESET_MARK = Regex("\\[reset-tasks(?=[\\s:\\]])([^\\]\n]*)]", RegexOption.IGNORE_CASE)

    /** A marker read as the plugin reads it: [all], or a heading [level], or a heading [name]; and its own [icon] and [label]. */
    class Marker(val all: Boolean, val level: Int, val name: String?, val icon: String?, val label: String?)

    /**
     * Reads a [RESET_MARK] match's inside: all or ## (a heading level), then heading:, icon: and
     * label: options in any order; [reset-tasks: Morning] is short for heading: Morning. Null if it
     * isn't one (it then stays text).
     */
    fun marker(m: MatchResult): Marker? {
        val inner = m.groupValues[1]
        val opts = Regex("\\s+(icon|label|heading):\\s*", RegexOption.IGNORE_CASE).findAll(inner).toList()
        val head = inner.substring(0, opts.firstOrNull()?.range?.first ?: inner.length)
        var icon: String? = null
        var label: String? = null
        var name: String? = null
        opts.forEachIndexed { i, o ->
            val value = inner.substring(o.range.last + 1, opts.getOrNull(i + 1)?.range?.first ?: inner.length).trim()
            if (value.isEmpty()) return@forEachIndexed
            when (o.groupValues[1].lowercase()) {
                "icon" -> icon = value.lowercase()
                "label" -> label = value
                else -> name = value
            }
        }
        val level = Regex("^ (#{1,6})$").find(head)?.groupValues?.get(1)?.length ?: 0
        val short = Regex("^:\\s*(.*\\S)\\s*$").find(head)?.groupValues?.get(1)
        val all = head.equals(" all", ignoreCase = true)
        if (head.isNotEmpty() && !all && level == 0 && short == null) return null
        return Marker(all, level, name ?: short, icon, label)
    }

    val INLINE = Regex(
        "(?<code>`[^`]+`)" +
            "|(?<reset>\\[(?i:reset-tasks)(?=[\\s:\\]])[^\\]\n]*])" +
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
    /**
     * [regions] are the note's foldable regions, of which [folded] are folded: their lines
     * after the first are left out, and the first gets a fold marker (and "…" when folded).
     */
    fun render(
        src: String, p: Palette, indentPx: Int,
        regions: Map<Int, Folds.Region> = emptyMap(), folded: Set<Int> = emptySet(),
        /** Task Resetter's buttons as just the icon (the plugin's "Hide the button label"). */
        resetIconOnly: Boolean = false,
        /** Fade Task Resetter's buttons that have nothing to reset; [resetOther] is its "Also clear other markers". */
        resetDim: Boolean = false,
        resetOther: Boolean = false,
    ): List<Block> {
        iconOnly.set(resetIconOnly)
        val lines = src.split('\n').map { it.removeSuffix("\r") }
        val out = ArrayList<Block>()
        var i = 0
        val hidden = BooleanArray(lines.size).also { h ->
            for (from in folded) regions[from]?.let { r -> for (l in r.from + 1..minOf(r.to, lines.size - 1)) h[l] = true }
        }

        // Front matter isn't shown in reading view either.
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) i = end + 1
        }

        var calloutColor: Int? = null
        while (i < lines.size) {
            if (hidden[i]) {
                i++
                continue
            }
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

            // A comment over several lines, hidden as in Obsidian: from a line starting with %% (on
            // its own, or with text after it, like Task Cleaner's "%% Task Cleaner: hidden items") that doesn't
            // close on the same line, to the next line with %% in it.
            if (trimmed.startsWith("%%") && !trimmed.substring(2).contains("%%")) {
                i++
                while (i < lines.size && !lines[i].contains("%%")) i++
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
            val resetKind = resetOn(line)
            out.add(
                when {
                    quote != null -> {
                        val inner = quote.groupValues[1]
                        val callout = CALLOUT.find(inner)
                        if (callout != null) {
                            // A callout's title: its icon (drawn with the box), then the title in the
                            // callout's colour, bold; with no title, the type's name.
                            val type = callout.groupValues[1].lowercase()
                            val color = p.calloutColor(type)
                            calloutColor = color
                            val title = callout.groupValues[2].ifEmpty { type.replaceFirstChar { it.uppercase() } }
                            val sb = inline(title, p)
                            span(sb, 0, StyleSpan(Typeface.BOLD), ForegroundColorSpan(color))
                            Block(sb, i, callout = CalloutLine(color, Callouts.kind(type).icon))
                        } else {
                            val sb = inline(inner, p)
                            if (sb.isEmpty()) sb.append(' ')
                            val color = calloutColor
                            if (color != null) {
                                Block(sb, i, callout = CalloutLine(color, null))
                            } else {
                                span(sb, 0, QuoteSpan(p.quote), ForegroundColorSpan(p.muted))
                                Block(sb, i)
                            }
                        }
                    }
                    RULE.matches(line) -> Block("", i, rule = true)
                    HEADING.matches(line) -> {
                        val m = HEADING.find(line)!!
                        val level = m.groupValues[1].length
                        val sb = inline(m.groupValues[2], p)
                        val scale = p.headingSizes[level - 1]
                        span(sb, 0, StyleSpan(Typeface.BOLD), RelativeSizeSpan(scale), ForegroundColorSpan(p.headings[level - 1]))
                        // A Task Resetter button keeps body size on a heading, as in Obsidian.
                        for (b in sb.getSpans(0, sb.length, ResetSpan::class.java)) {
                            sb.setSpan(RelativeSizeSpan(1f / scale), sb.getSpanStart(b), sb.getSpanEnd(b), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
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
                }.let { b ->
                    if (resetKind == Block.NONE) return@let b
                    if (resetDim && resetTasks(src, i, resetKind == Block.RESET_ALL, resetOther) == null) {
                        (b.text as? Spanned)?.getSpans(0, b.text.length, ResetSpan::class.java)?.forEach { it.dim = true }
                    }
                    b.copy(reset = resetKind)
                }
            )
            i++
        }
        // Drop trailing spacers so the note doesn't end in a gap.
        while (out.isNotEmpty() && out.last().text.isBlank() && !out.last().rule) out.removeAt(out.lastIndex)
        // Each callout's last line shown closes its box.
        for (k in out.indices) {
            val c = out[k].callout ?: continue
            val next = out.getOrNull(k + 1)?.callout
            if (next == null || next.icon != null) out[k] = out[k].copy(callout = CalloutLine(c.color, c.icon, last = true))
        }
        if (regions.isEmpty()) return out
        // Mark each foldable line's (first) block; a folded one ends in "…", as in Obsidian.
        val marked = HashSet<Int>()
        return out.map { b ->
            if (b.line !in regions || !marked.add(b.line) || b.table != null || b.code) return@map b
            val isFolded = b.line in folded
            val text = if (!isFolded) b.text else SpannableStringBuilder(b.text).also {
                val start = it.length
                it.append("  …")
                it.setSpan(ForegroundColorSpan(p.faint), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b.copy(text = text, fold = if (isFolded) Block.FOLDED else Block.OPEN)
        }
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
                g["reset"] != null -> {
                    val mk = RESET_MARK.find(t)?.let(::marker)
                    if (mk == null) out.append(t) else out.append(button(mk, p))
                }
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

    /** Whether the [render] running on this thread draws Task Resetter's buttons icon-only. */
    private val iconOnly = ThreadLocal.withInitial { false }

    /** Task Resetter's button, one character drawn by a [ResetSpan]. */
    private fun button(mk: Marker, p: Palette): SpannableStringBuilder {
        val path = mk.icon?.let { if (it == "rotate-ccw-square") null else LucideIcons.data(it) }
        val span = ResetSpan(
            mk.label ?: resetLabel(mk), p.accent, p.faint, p.muted, iconOnly.get() == true,
            if (path != null) mk.icon!! else "rotate-ccw-square", path, mk.label != null,
        )
        return SpannableStringBuilder("\uFFFC").also { it.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }


    /** A marker's button text, as the plugin labels it. */
    private fun resetLabel(m: Marker): String = when {
        m.all -> "Reset all tasks"
        m.level > 0 -> "Reset H${m.level} tasks"
        m.name != null -> "Reset ${m.name}"
        else -> "Reset tasks"
    }

    /** A heading's text as [reset-tasks: ...] matches it: no #s, closing #s or markers, lower case. */
    private fun headingText(line: String): String =
        line.replace(Regex("^#{1,6}\\s+"), "").replace(RESET_MARK, "").replace(Regex("\\s+#+\\s*$"), "").trim().lowercase()

    /** Whether [line] has a [reset-tasks] marker (outside `inline code`), and which kind. */
    private fun resetOn(line: String): Int {
        for (m in RESET_MARK.findAll(line)) {
            if (line.substring(0, m.range.first).count { it == '`' } % 2 == 1) continue
            val mk = marker(m) ?: continue
            return if (mk.all) Block.RESET_ALL else Block.RESET_SECTION
        }
        return Block.NONE
    }

    /**
     * Task Resetter's reset, as the plugin does it, for the button on [line] (read from the marker
     * there): [reset-tasks] resets the section of the heading it's on or under (to the next heading of
     * the same or a higher level, subheadings included), or the whole note when no heading is above
     * it; [reset-tasks all] the whole note; [reset-tasks ##] every section of that heading level;
     * [reset-tasks: Morning] each heading named Morning. [whole] is used when the line no longer has a
     * marker. Unticks [x] (and, with [other], any other mark). Code blocks are left alone. Null if
     * nothing changed.
     */
    fun resetTasks(src: String, line: Int, whole: Boolean, other: Boolean): String? {
        val lines = src.split('\n').toMutableList()
        val fenced = BooleanArray(lines.size)
        var inFence = false
        for (k in lines.indices) {
            val marker = FENCE.containsMatchIn(lines[k])
            if (marker) inFence = !inFence
            fenced[k] = inFence || marker
        }
        fun level(k: Int) = if (fenced[k]) 0 else HEADING_START.find(lines[k])?.groupValues?.get(1)?.length ?: 0
        val ranges = mutableListOf<IntRange>()
        fun section(h: Int) {
            val end = (h + 1 until lines.size).firstOrNull { level(it) in 1..level(h) } ?: lines.size
            ranges.add(h + 1 until end)
        }
        val mark = lines.getOrNull(line)?.let { l -> RESET_MARK.findAll(l).firstNotNullOfOrNull(::marker) }
        val all = mark?.all ?: whole
        val byLevel = mark?.level ?: 0
        val byName = mark?.name?.lowercase().orEmpty()
        when {
            all || lines.isEmpty() -> ranges.add(lines.indices)
            byLevel > 0 -> for (k in lines.indices) if (level(k) == byLevel) section(k)
            byName.isNotEmpty() -> for (k in lines.indices) if (level(k) > 0 && headingText(lines[k]) == byName) section(k)
            else -> {
                var own = -1
                var k = minOf(line, lines.size - 1)
                while (k >= 0 && own < 0) { if (level(k) > 0) own = k; k-- }
                if (own >= 0) section(own) else ranges.add(lines.indices)
            }
        }
        var changed = false
        for (k in ranges.flatten().toSortedSet()) {
            if (fenced[k]) continue
            val m = RESET_BOX.find(lines[k]) ?: continue
            val mark = m.groupValues[2]
            if (mark == " " || (!other && mark != "x" && mark != "X")) continue
            lines[k] = lines[k].replaceRange(m.groups[2]!!.range, " ")
            changed = true
        }
        return if (changed) lines.joinToString("\n") else null
    }

    private val HEADING_START = Regex("^(#{1,6})\\s")

    /** A table row's cells: split on pipes that aren't escaped, without the ones at the ends. */
    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|"))

    /** Flips the checkbox on [line] of [src]; returns null if that line isn't a task any more. */
    /**
     * Ticks or unticks the task on [line] of [src]. With [expected] (the task's line as the widget
     * drew it), a line that no longer matches, because lines were added or removed since, is
     * looked for nearby, ticked or not.
     */
    fun toggleTask(src: String, line: Int, expected: String? = null): String? {
        if (expected != null) {
            val lines = src.split('\n')
            fun same(l: String) = TASK_BOX.find(l) != null && unticked(l.removeSuffix("\r")) == unticked(expected)
            val at = if (lines.getOrNull(line)?.let(::same) == true) line
            else lines.indices.filter { same(lines[it]) }.minByOrNull { kotlin.math.abs(it - line) } ?: return null
            return toggleLine(src, at)
        }
        return toggleLine(src, line)
    }

    /** [line] with its checkbox cleared, to compare tasks whether ticked or not. */
    private fun unticked(line: String) = TASK_BOX.find(line)?.let { line.replaceRange(it.groups[2]!!.range, " ") } ?: line

    private fun toggleLine(src: String, line: Int): String? {
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

    /** The colour of a callout of [type], from the theme. */
    fun calloutColor(type: String, p: Palette): Int = p.calloutColor(type)

}
