package local.obsidianwidget

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folding, as in Obsidian: headings (down to the next heading of the same or a higher level),
 * list items with nested items, and callouts written foldable ("[!note]-" starts folded,
 * "[!note]+" open). Each widget keeps its own folds, by the line each folded region starts on.
 *
 * With a fold-syncing plugin on in the vault (Live Fold Sync, or Sync Folds), heading and list
 * folds are Obsidian's instead: read from and written to the plugin's data.json, so they're the
 * same in Obsidian, the widget and its editor. Callouts fold by their marker, which Obsidian
 * doesn't keep, so those stay the widget's.
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
        val shared = Shared.of(ctx, id)?.read(ctx, text)
        val lines = text.split('\n')
        return regions.values.filter { r ->
            when (r.kind) {
                Kind.CALLOUT -> {
                    val startsFolded = CALLOUT.find(lines[r.from])!!.groupValues[1] == "-"
                    if (r.from in taps) !startsFolded else startsFolded
                }
                else -> if (shared != null) r.from in shared else r.from in taps
            }
        }.map { it.from }.toSet()
    }

    /** Folds or unfolds the region starting on [line] of [text], on widget [id]. */
    fun toggle(ctx: Context, id: Int, text: String, line: Int) {
        val regions = regions(text)
        val region = regions[line] ?: return
        if (region.kind != Kind.CALLOUT && Shared.of(ctx, id) != null) {
            // Flipped from what's showing, which is Obsidian's folds when it has them for this text.
            val now = folded(ctx, id, text)
            set(ctx, id, text, if (line in now) now - line else now + line)
            return
        }
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
        Shared.of(ctx, id)?.write(ctx, text, regions.values.filter { it.kind != Kind.CALLOUT && it.from in folded })
    }

    /**
     * A fold-syncing plugin's folds for one note: its data.json holds
     * `{"folds": "<JSON of {path: {folds: [{from, to}], lines}}>"}`, lines counted from 0, as
     * Obsidian keeps them.
     */
    class Shared(val data: Uri, private val path: String, private val id: Int) {
        /** The folded lines, or null if they were saved for another version of the note. */
        fun read(ctx: Context, text: String): Set<Int>? {
            val info = all(ctx)?.optJSONObject(path) ?: return emptySet()
            if (info.optInt("lines", -1) != text.split('\n').size) return null
            val folds = info.optJSONArray("folds") ?: return emptySet()
            return (0 until folds.length()).mapNotNull { folds.optJSONObject(it)?.optInt("from", -1)?.takeIf { f -> f >= 0 } }.toSet()
        }

        fun write(ctx: Context, text: String, folded: List<Region>) {
            synchronized(Shared) {
                val raw = Vault.read(ctx, data) ?: return
                val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val all = runCatching { JSONObject(root.optString("folds", "{}")) }.getOrNull() ?: return
                val lines = text.split('\n')
                val old = all.optJSONObject(path)
                // The same folds as Obsidian last saved needn't be written (that would only wake it).
                if (old != null && old.optInt("lines") == lines.size) {
                    val was = old.optJSONArray("folds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("from") }.toSet() }
                    if (was == folded.map { it.from }.toSet()) return
                } else if (old == null && folded.isEmpty()) return
                if (folded.isEmpty()) all.remove(path)
                else all.put(path, JSONObject().put("lines", lines.size).put("folds", JSONArray().apply {
                    for (r in folded.sortedBy { it.from }) put(JSONObject().put("from", r.from).put("to", end(lines, r)))
                }))
                root.put("folds", all.toString())
                runCatching { Vault.write(ctx, data, root.toString(2)) }
            }
        }

        // As last read, like the note; folds changed in Obsidian redraw the widget once they're in.
        private fun all(ctx: Context): JSONObject? = Vault.recent(ctx, data) { NoteWidgetProvider.update(ctx.applicationContext, android.appwidget.AppWidgetManager.getInstance(ctx), id) }
            ?.let { runCatching { JSONObject(JSONObject(it).optString("folds", "{}")) }.getOrNull() }

        /** Where Obsidian ends a fold: a heading's takes the blank lines before the next heading. */
        private fun end(lines: List<String>, r: Region): Int {
            if (r.kind != Kind.HEADING) return r.to
            var to = r.to
            while (to + 1 < lines.size && lines[to + 1].isBlank()) to++
            return to
        }

        companion object {
            private val PLUGINS = listOf("live-fold-sync", "sync-folds")

            // Where each vault's plugin data is, and when that was looked up, so a fold doesn't walk
            // the vault's folders again: that takes seconds.
            private val found = HashMap<String, Pair<Long, Uri?>>()

            /**
             * Widget [id]'s note's shared folds, or null when no fold-syncing plugin is on. Only the
             * first look ever waits for the walk; the answer is kept on disk too, since the app's
             * process often doesn't last. After a minute it's looked up again behind the scenes, in
             * case the plugin was turned on or off, and the last answer serves till then.
             */
            fun of(ctx: Context, id: Int): Shared? {
                val cfg = WidgetStore.load(ctx, id) ?: return null
                val tree = cfg.vaultTree
                val key = tree.toString()
                val now = android.os.SystemClock.uptimeMillis()
                val disk = ctx.getSharedPreferences("folds", Context.MODE_PRIVATE)
                fun keep(uri: Uri?) {
                    synchronized(found) { found[key] = android.os.SystemClock.uptimeMillis() to uri }
                    disk.edit().putString("data@$key", uri?.toString().orEmpty()).apply()
                }
                val known = synchronized(found) {
                    // From disk, it's as good as a minute old: looked up again, but not waited for.
                    (found[key] ?: disk.getString("data@$key", null)?.let { (now - 60_000) to it.ifEmpty { null }?.let(Uri::parse) })?.also { (at, uri) ->
                        // Marked as looked up now, so only one look runs at a time.
                        if (now - at >= 60_000) {
                            found[key] = now to uri
                            val app = ctx.applicationContext
                            kotlin.concurrent.thread { keep(locate(app, tree)) }
                        }
                    }
                }
                val data = (if (known != null) known.second else locate(ctx, tree).also(::keep))
                    ?: return null
                return Shared(data, cfg.notePath, id)
            }

            private fun locate(ctx: Context, tree: Uri): Uri? {
                val enabled = Vault.readPath(ctx, tree, ".obsidian/community-plugins.json")
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
                    ?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
                val plugin = PLUGINS.firstOrNull { it in enabled } ?: return null
                return Vault.find(ctx, tree, ".obsidian/plugins/$plugin/data.json")
            }
        }
    }

    /** The lines of [text] hidden inside the [folded] regions. */
    fun hiddenLines(text: String, folded: Set<Int>, regions: Map<Int, Region> = regions(text)): Set<Int> =
        folded.flatMap { from -> regions[from]?.let { (it.from + 1)..it.to } ?: IntRange.EMPTY }.toSet()

    /**
     * Widget [id] switched from note [from] to note [to] (both paths): its folds for [from] are put
     * aside, and any it had for [to] come back.
     */
    fun swap(ctx: Context, id: Int, from: String, to: String) {
        val p = prefs(ctx)
        val current = p.getString("w$id", null)
        val next = p.getString("w$id@$to", null)
        p.edit().apply {
            if (current != null) putString("w$id@$from", current) else remove("w$id@$from")
            if (next != null) putString("w$id", next) else remove("w$id")
            remove("w$id@$to")
        }.apply()
    }

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
