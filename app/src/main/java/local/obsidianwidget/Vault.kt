package local.obsidianwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject

/** A markdown file in the vault. [path] is relative to the vault root, e.g. "Daily/2026-11-03.md". */
data class Note(val path: String, val uri: Uri, val modified: Long) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

/**
 * Vault folders picked through the Storage Access Framework, named by their tree URI. Works the
 * same for Obsidian Sync, Syncthing or any other folder, since it only reads and writes plain
 * files. Each widget has its own vault; the app keeps one as the default for new widgets.
 */
object Vault {
    private const val PREFS = "vault"
    private const val CONFIG_DIR = ".obsidian"

    private class Entry(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long = 0)

    /** The vault new widgets start with: the one picked in the app, or last picked for a widget. */
    /**
     * Where the vault folder is on the phone's storage, from its root, e.g. "Obsidian/notes"; on an
     * SD card or USB drive, after that volume's name.
     */
    fun displayPath(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        return if (volume == "primary") path.ifEmpty { "Internal storage" } else "$volume/$path"
    }

    fun defaultTree(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let(Uri::parse)

    fun defaultName(ctx: Context): String = prefs(ctx).getString("name", null).orEmpty()

    fun setDefault(ctx: Context, tree: Uri, name: String) {
        prefs(ctx).edit().putString("tree", tree.toString()).putString("name", name).apply()
    }

    /**
     * Keeps access to a vault the user just picked, across restarts, and returns its name. Grants
     * aren't released when a widget moves to another vault, since another widget may still use it.
     */
    fun take(ctx: Context, tree: Uri): String {
        ctx.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return ctx.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: tree.lastPathSegment.orEmpty().substringAfterLast(':').substringAfterLast('/')
    }

    /** Every markdown note in the vault, skipping hidden folders such as .obsidian and .trash. */
    /**
     * The vault's notes as last listed, kept in the cache folder: listing a big vault through the
     * storage framework takes a while, so the picker shows these at once and then the fresh list.
     */
    fun cachedNotes(ctx: Context, tree: Uri): List<Note>? = runCatching {
        val a = org.json.JSONArray(java.io.File(ctx.cacheDir, "notes-${tree.toString().hashCode().toUInt()}.json").readText())
        List(a.length()) { i -> a.getJSONObject(i).let { Note(it.getString("p"), Uri.parse(it.getString("u")), it.getLong("m")) } }
    }.getOrNull()

    /** All the vault's notes, listed afresh, and remembered for [cachedNotes]. */
    fun notes(ctx: Context, tree: Uri): List<Note> = listNotes(ctx, tree).also { notes ->
        runCatching {
            val a = org.json.JSONArray()
            for (n in notes) a.put(org.json.JSONObject().put("p", n.path).put("u", n.uri.toString()).put("m", n.modified))
            java.io.File(ctx.cacheDir, "notes-${tree.toString().hashCode().toUInt()}.json").writeText(a.toString())
        }
    }

    private fun listNotes(ctx: Context, tree: Uri): List<Note> {
        val out = ArrayList<Note>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        while (pending.isNotEmpty()) {
            val (id, prefix) = pending.removeFirst()
            for (e in children(ctx, tree, id)) {
                if (e.name.startsWith(".")) continue
                when {
                    e.isDir -> pending.add(e.id to "$prefix${e.name}/")
                    e.name.endsWith(".md", ignoreCase = true) ->
                        out.add(Note(prefix + e.name, DocumentsContract.buildDocumentUriUsingTree(tree, e.id), e.modified))
                }
            }
        }
        return out
    }

    /** Names of the community themes installed in the vault's .obsidian/themes folder. */
    fun themes(ctx: Context, tree: Uri): List<String> {
        val dir = configChild(ctx, tree, "themes") ?: return emptyList()
        return children(ctx, tree, dir.id).mapNotNull {
            when {
                it.isDir -> it.name
                it.name.endsWith(".css") -> it.name.removeSuffix(".css")
                else -> null
            }
        }.distinct().sortedBy { it.lowercase() }
    }

    /** CSS of an installed theme; newer themes are a folder with theme.css, older ones a single .css file. */
    fun themeCss(ctx: Context, tree: Uri, name: String): String? {
        val dir = configChild(ctx, tree, "themes") ?: return null
        val entries = children(ctx, tree, dir.id)
        val file = entries.firstOrNull { it.isDir && it.name == name }
            ?.let { folder -> children(ctx, tree, folder.id).firstOrNull { it.name == "theme.css" } }
            ?: entries.firstOrNull { !it.isDir && it.name == "$name.css" }
            ?: return null
        return read(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, file.id))
    }

    /** The vault's appearance.json: which theme, light or dark mode and accent colour Obsidian uses. */
    fun appearance(ctx: Context, tree: Uri): JSONObject? {
        val file = configChild(ctx, tree, "appearance.json") ?: return null
        val text = read(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, file.id)) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** A file or folder by its path from the vault root, e.g. ".obsidian/fonts/Inter.ttf". */
    fun find(ctx: Context, tree: Uri, path: String): Uri? {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            id = children(ctx, tree, id).firstOrNull { it.name == part }?.id ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    /**
     * A fingerprint of the files at [paths] (from the vault root): each one's date and size, and
     * for a folder its files'. Cheap to get, and changes whenever any of them does.
     */
    fun stamp(ctx: Context, tree: Uri, paths: List<String>): String {
        // Paths share folders (.obsidian, plugins); list each once.
        val listed = HashMap<String, List<Entry>>()
        fun list(id: String) = listed.getOrPut(id) { children(ctx, tree, id) }
        return paths.joinToString("|") { path ->
            var parent = DocumentsContract.getTreeDocumentId(tree)
            var entry: Entry? = null
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                entry = list(parent).firstOrNull { it.name == part } ?: return@joinToString "$path:-"
                parent = entry.id
            }
            val e = entry ?: return@joinToString "$path:-"
            if (e.isDir) "$path:" + list(e.id).sortedBy { it.name }.joinToString(",") { "${it.name}:${it.modified}:${it.size}" }
            else "$path:${e.modified}:${e.size}"
        }
    }

    fun readPath(ctx: Context, tree: Uri, path: String): String? = find(ctx, tree, path)?.let { read(ctx, it) }

    /** Names of the files in the folder at [path] from the vault root. */
    fun list(ctx: Context, tree: Uri, path: String): List<String> {
        val dir = find(ctx, tree, path) ?: return emptyList()
        return children(ctx, tree, DocumentsContract.getDocumentId(dir)).filter { !it.isDir }.map { it.name }
    }

    fun readBytes(ctx: Context, uri: Uri): ByteArray? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    fun read(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    fun write(ctx: Context, uri: Uri, text: String) {
        // "wt" truncates; plain "w" leaves stale bytes behind when the note gets shorter.
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) }
    }

    /** obsidian:// link that opens [path] in the vault called [vaultName]. */
    fun openUri(vaultName: String, path: String): Uri = Uri.parse(
        "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(path.removeSuffix(".md"))}"
    )

    private fun configChild(ctx: Context, tree: Uri, name: String): Entry? {
        val config = children(ctx, tree, DocumentsContract.getTreeDocumentId(tree))
            .firstOrNull { it.isDir && it.name == CONFIG_DIR } ?: return null
        return children(ctx, tree, config.id).firstOrNull { it.name == name }
    }

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        return runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Entry(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
