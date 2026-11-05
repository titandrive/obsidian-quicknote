package local.obsidianwidget

import android.content.Context

/**
 * Folding, as in Obsidian: headings (down to the next heading of the same or a higher level),
 * list items with nested items, and callouts written foldable ("[!note]-" starts folded,
 * "[!note]+" open). Each widget keeps its own folds, by the line each folded region starts on.
 */
object Folds {
    enum class Kind { HEADING, LIST, CALLOUT }

    /** A foldable stretch of lines: [from] is the line that stays visible, through [to]. */
    data class Region(val from: Int, val to: Int, val kind: Kind)

    private val HEADING = Regex("^(#{1,6})\\s")
    private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s")
    private val CALLOUT = Regex("^\\s*>\\s?\\[!\\w+]([+-])")

    /** Every foldable region in [text], by the line it starts on. */
    fun regions(text: String): Map<Int, Region> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val out = HashMap<Int, Region>()
        var fence = false
        for ((i, line) in lines.withIndex()) {
            if (Markdown.FENCE.containsMatchIn(line)) fence = !fence
            if (fence) continue
            val heading = HEADING.find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                var end = lines.size - 1
                for (j in i + 1 until lines.size) {
                    val h = HEADING.find(lines[j])
                    if (h != null && h.groupValues[1].length <= level) { end = j - 1; break }
                }
                // Blank lines at the end of a section stay out of the fold, as in Obsidian.
                while (end > i && lines[end].isBlank()) end--
                if (end > i) out[i] = Region(i, end, Kind.HEADING)
                continue
            }
            LIST_ITEM.find(line)?.let { m ->
                val indent = width(m.groupValues[1])
                var last = i
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || width(lines[j].takeWhile { it == ' ' || it == '\t' }) > indent)) {
                    if (lines[j].isNotBlank()) last = j
                    j++
                }
                if (last > i) out[i] = Region(i, last, Kind.LIST)
            }
            if (CALLOUT.containsMatchIn(line)) {
                var last = i
                while (last + 1 < lines.size && lines[last + 1].trimStart().startsWith(">")) last++
                if (last > i) out[i] = Region(i, last, Kind.CALLOUT)
            }
        }
        return out
    }

    private fun width(indent: String) = indent.sumOf { if (it == '\t') 4 else 1 }

    /**
     * The regions of [text] currently folded on widget [id]. Callouts start from their marker
     * ("-" folded); a tap on one flips it from there.
     */
    fun folded(ctx: Context, id: Int, text: String): Set<Int> {
        val regions = regions(text)
        val taps = load(ctx, id)
        val lines = text.split('\n')
        return regions.values.filter { r ->
            when (r.kind) {
                Kind.CALLOUT -> {
                    val startsFolded = CALLOUT.find(lines[r.from])!!.groupValues[1] == "-"
                    if (r.from in taps) !startsFolded else startsFolded
                }
                else -> r.from in taps
            }
        }.map { it.from }.toSet()
    }

    /** Folds or unfolds the region starting on [line] of [text], on widget [id]. */
    fun toggle(ctx: Context, id: Int, text: String, line: Int) {
        if (line !in regions(text)) return
        val taps = load(ctx, id).toMutableSet()
        if (!taps.add(line)) taps.remove(line)
        save(ctx, id, taps)
    }

    /**
     * After the note was edited: moves folds to wherever their lines went, so a folded heading
     * stays folded when lines are added or removed above it.
     */
    fun remap(ctx: Context, id: Int, old: String, new: String) {
        val taps = load(ctx, id)
        if (taps.isEmpty() || old == new) return
        save(ctx, id, moved(taps, old, new))
    }

    /** Fold lines [lines] of [old], moved to where those same lines are in [new]. */
    fun moved(lines: Set<Int>, old: String, new: String): Set<Int> {
        if (lines.isEmpty() || old == new) return lines
        val oldLines = old.split('\n')
        val newLines = new.split('\n')
        val starts = regions(new).keys
        return lines.mapNotNull { from ->
            val content = oldLines.getOrNull(from) ?: return@mapNotNull null
            // The same line, nearest to where it was.
            starts.filter { newLines[it] == content }.minByOrNull { kotlin.math.abs(it - from) }
        }.toSet()
    }

    /** Saves [folded] (fold lines of [text]) as widget [id]'s folds, e.g. from the editor. */
    fun set(ctx: Context, id: Int, text: String, folded: Set<Int>) {
        val regions = regions(text)
        val lines = text.split('\n')
        // Callouts are stored as flips of their marker; everything else as folded lines.
        save(ctx, id, regions.values.filter { r ->
            val isFolded = r.from in folded
            if (r.kind == Kind.CALLOUT) isFolded != (CALLOUT.find(lines[r.from])!!.groupValues[1] == "-") else isFolded
        }.map { it.from }.toSet())
    }

    /** The lines of [text] hidden inside the [folded] regions. */
    fun hiddenLines(text: String, folded: Set<Int>, regions: Map<Int, Region> = regions(text)): Set<Int> =
        folded.flatMap { from -> regions[from]?.let { (it.from + 1)..it.to } ?: IntRange.EMPTY }.toSet()

    /** Forgets widget [id]'s folds, e.g. when it's removed or shows another note. */
    fun forget(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("w$id").apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("folds", Context.MODE_PRIVATE)

    private fun load(ctx: Context, id: Int): Set<Int> =
        prefs(ctx).getString("w$id", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    private fun save(ctx: Context, id: Int, lines: Set<Int>) {
        prefs(ctx).edit().putString("w$id", lines.joinToString(",")).apply()
    }
}
