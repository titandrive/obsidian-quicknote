package local.obsidianwidget

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * The Task Resetter plugin's settings, read from its data.json in the vault, so its button on the
 * widget looks and behaves as it does in Obsidian (icon only when the label is hidden; also clearing
 * other marks when that's on).
 */
object TaskResetter {
    private val data = PluginData("task-resetter")

    /** The settings, or null if the plugin isn't in the vault. [onChanged] is told when they change. */
    fun settings(ctx: Context, tree: Uri, onChanged: (() -> Unit)? = null): JSONObject? = data.settings(ctx, tree, onChanged)

    fun hideLabel(ctx: Context, tree: Uri, onChanged: (() -> Unit)? = null): Boolean =
        settings(ctx, tree, onChanged)?.optBoolean("hideLabel") ?: false

    fun clearOtherMarkers(ctx: Context, tree: Uri): Boolean =
        settings(ctx, tree)?.optBoolean("clearOtherMarkers") ?: false
}

/**
 * An Obsidian plugin's settings: its data.json in the vault (.obsidian/plugins/<id>/data.json).
 *
 * Finding the file walks the vault, so where it is is remembered, on disk too; it's looked for again
 * behind the scenes after a minute, in case the plugin was installed or removed. The file itself is
 * read as last read and again behind the scenes ([Vault.recent]); [settings]' onChanged is told of a
 * change.
 */
class PluginData(private val id: String) {
    private val path = ".obsidian/plugins/$id/data.json"
    private val found = HashMap<String, Pair<Long, Uri?>>()

    /** The settings, or null if the plugin isn't in the vault (or has never saved any). */
    fun settings(ctx: Context, tree: Uri, onChanged: (() -> Unit)? = null): JSONObject? {
        val data = locate(ctx, tree) ?: return null
        val text = Vault.recent(ctx, data, onChanged) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun locate(ctx: Context, tree: Uri): Uri? {
        val key = tree.toString()
        val now = android.os.SystemClock.uptimeMillis()
        val disk = ctx.getSharedPreferences(id, Context.MODE_PRIVATE)
        fun keep(uri: Uri?) {
            synchronized(found) { found[key] = android.os.SystemClock.uptimeMillis() to uri }
            disk.edit().putString("data@$key", uri?.toString().orEmpty()).apply()
        }
        val known = synchronized(found) {
            (found[key] ?: disk.getString("data@$key", null)?.let { (now - 60_000) to it.ifEmpty { null }?.let(Uri::parse) })?.also { (at, uri) ->
                if (now - at >= 60_000) {
                    found[key] = now to uri
                    val app = ctx.applicationContext
                    kotlin.concurrent.thread { keep(Vault.find(app, tree, path)) }
                }
            }
        }
        return if (known != null) known.second else Vault.find(ctx, tree, path).also(::keep)
    }
}
