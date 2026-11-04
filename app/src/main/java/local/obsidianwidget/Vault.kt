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
 * The vault folder the user picked through the Storage Access Framework. Works the same for
 * Obsidian Sync, Syncthing or any other folder, since it only reads and writes plain files.
 */
object Vault {
    private const val PREFS = "vault"
    private const val CONFIG_DIR = ".obsidian"

    private class Entry(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long = 0)

    fun tree(ctx: Context): Uri? =
        prefs(ctx).getString("tree", null)?.let(Uri::parse)

    fun name(ctx: Context): String = prefs(ctx).getString("name", null).orEmpty()

    fun set(ctx: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(tree, flags)
        // Drop the grant for a previously chosen vault so they don't pile up.
        tree(ctx)?.takeIf { it != tree }?.let { old ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = ctx.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: tree.lastPathSegment.orEmpty().substringAfterLast(':').substringAfterLast('/')
        prefs(ctx).edit().putString("tree", tree.toString()).putString("name", name).apply()
    }

    /** Every markdown note in the vault, skipping hidden folders such as .obsidian and .trash. */
    fun notes(ctx: Context): List<Note> {
        val tree = tree(ctx) ?: return emptyList()
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
    fun themes(ctx: Context): List<String> {
        val dir = configChild(ctx, "themes") ?: return emptyList()
        val tree = tree(ctx)!!
        return children(ctx, tree, dir.id).mapNotNull {
            when {
                it.isDir -> it.name
                it.name.endsWith(".css") -> it.name.removeSuffix(".css")
                else -> null
            }
        }.distinct().sortedBy { it.lowercase() }
    }

    /** CSS of an installed theme; newer themes are a folder with theme.css, older ones a single .css file. */
    fun themeCss(ctx: Context, name: String): String? {
        val tree = tree(ctx) ?: return null
        val dir = configChild(ctx, "themes") ?: return null
        val entries = children(ctx, tree, dir.id)
        val file = entries.firstOrNull { it.isDir && it.name == name }
            ?.let { folder -> children(ctx, tree, folder.id).firstOrNull { it.name == "theme.css" } }
            ?: entries.firstOrNull { !it.isDir && it.name == "$name.css" }
            ?: return null
        return read(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, file.id))
    }

    /** The vault's appearance.json: which theme, light or dark mode and accent colour Obsidian uses. */
    fun appearance(ctx: Context): JSONObject? {
        val file = configChild(ctx, "appearance.json") ?: return null
        val text = read(ctx, DocumentsContract.buildDocumentUriUsingTree(tree(ctx)!!, file.id)) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** A file or folder by its path from the vault root, e.g. ".obsidian/fonts/Inter.ttf". */
    fun find(ctx: Context, path: String): Uri? {
        val tree = tree(ctx) ?: return null
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
    fun stamp(ctx: Context, paths: List<String>): String {
        val tree = tree(ctx) ?: return ""
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

    fun readPath(ctx: Context, path: String): String? = find(ctx, path)?.let { read(ctx, it) }

    /** Names of the files in the folder at [path] from the vault root. */
    fun list(ctx: Context, path: String): List<String> {
        val tree = tree(ctx) ?: return emptyList()
        val dir = find(ctx, path) ?: return emptyList()
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

    /** obsidian:// link that opens [path] in the vault. */
    fun openUri(ctx: Context, path: String): Uri = Uri.parse(
        "obsidian://open?vault=${Uri.encode(name(ctx))}&file=${Uri.encode(path.removeSuffix(".md"))}"
    )

    private fun configChild(ctx: Context, name: String): Entry? {
        val tree = tree(ctx) ?: return null
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
