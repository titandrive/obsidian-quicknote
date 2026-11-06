package local.obsidianwidget

/**
 * The note picker's two views of a vault: its folders as a tree, as in Obsidian's file explorer,
 * and, while searching, Obsidian's quick switcher's fuzzy matching.
 */
object NoteTree {
    /** A line of the tree: a folder (by its path) or a note, so many levels in. */
    sealed class Entry(val depth: Int)
    class Folder(val path: String, val name: String, depth: Int, val open: Boolean) : Entry(depth)
    class File(val note: Note, depth: Int) : Entry(depth)

    /**
     * The tree's lines with only the folders in [open] showing what's in them. Folders come first,
     * then notes, each by name, as Obsidian sorts them by default.
     */
    fun rows(notes: List<Note>, open: Set<String>): List<Entry> {
        val folders = HashMap<String, MutableSet<String>>()
        val files = HashMap<String, MutableList<Note>>()
        for (n in notes) {
            val parent = n.path.substringBeforeLast('/', "")
            files.getOrPut(parent) { ArrayList() }.add(n)
            // Every folder on the way down, under its own parent.
            var path = parent
            while (path.isNotEmpty()) {
                val up = path.substringBeforeLast('/', "")
                folders.getOrPut(up) { HashSet() }.add(path)
                path = up
            }
        }
        val out = ArrayList<Entry>()
        fun walk(folder: String, depth: Int) {
            for (sub in folders[folder].orEmpty().sortedBy { it.substringAfterLast('/').lowercase() }) {
                val isOpen = sub in open
                out.add(Folder(sub, sub.substringAfterLast('/'), depth, isOpen))
                if (isOpen) walk(sub, depth + 1)
            }
            for (n in files[folder].orEmpty().sortedBy { it.title.lowercase() }) out.add(File(n, depth))
        }
        walk("", 0)
        return out
    }

    /** The folders a note is in, so the tree can open onto it. */
    fun ancestors(path: String): Set<String> {
        val out = HashSet<String>()
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) {
            out.add(p)
            p = p.substringBeforeLast('/', "")
        }
        return out
    }

    /** A search result: the note, and which characters of its title matched (empty if its folder did). */
    class Hit(val note: Note, val matched: IntArray, val score: Int)

    /**
     * Notes matching [query] as Obsidian's quick switcher matches them: its letters in order
     * anywhere in the title (or failing that, the path), best matches first.
     */
    fun search(notes: List<Note>, query: String): List<Hit> {
        val q = query.lowercase().filter { !it.isWhitespace() }
        if (q.isEmpty()) return emptyList()
        return notes.mapNotNull { n ->
            match(q, n.title)?.let { (at, score) -> Hit(n, at, score + 100) }
                ?: match(q, n.path)?.let { (_, score) -> Hit(n, IntArray(0), score) }
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.note.modified })
    }

    /**
     * Where [q]'s letters fall in [text], in order, and how good a match that is: letters next to
     * each other, and at the starts of words, count for more; gaps count against.
     */
    /** Whether q's letters from [k] on all appear in [t] from [from], in order. */
    private fun follows(q: String, k: Int, t: String, from: Int): Boolean {
        var i = from
        for (j in k until q.length) {
            i = t.indexOf(q[j], i)
            if (i < 0) return false
            i++
        }
        return true
    }

    private fun match(q: String, text: String): Pair<IntArray, Int>? {
        val t = text.lowercase()
        val at = IntArray(q.length)
        var from = 0
        for ((k, ch) in q.withIndex()) {
            val plain = t.indexOf(ch, from)
            if (plain < 0) return null
            // Prefer this letter at the start of a word, if the rest still matches after it.
            val wordStart = (plain until t.length).firstOrNull { i -> t[i] == ch && (i == 0 || !t[i - 1].isLetterOrDigit()) }
                ?.takeIf { i -> follows(q, k + 1, t, i + 1) }
            at[k] = wordStart ?: plain
            from = at[k] + 1
        }
        var score = 0
        for ((k, i) in at.withIndex()) {
            if (i == 0 || !t[i - 1].isLetterOrDigit()) score += 8
            if (k > 0 && at[k - 1] == i - 1) score += 6 else if (k > 0) score -= minOf(5, i - at[k - 1] - 1)
        }
        // Shorter names that match as well come first.
        return at to score - text.length / 8
    }
}
