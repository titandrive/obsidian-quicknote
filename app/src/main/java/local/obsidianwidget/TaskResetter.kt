package local.obsidianwidget

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * The Task Resetter plugin's settings, read from its data.json in the vault, so its button on the
 * widget looks and behaves as it does in Obsidian (icon only when the label is hidden; also clearing
 * other marks when that's on).
 *
 * Finding the file walks the vault, so where it is is remembered, on disk too; it's looked for again
 * behind the scenes after a minute, in case the plugin was installed or removed. The file itself is
 * read as last read and again behind the scenes ([Vault.recent]); a change redraws the widget.
 */
object TaskResetter {
    private const val PATH = ".obsidian/plugins/task-resetter/data.json"
    private val found = HashMap<String, Pair<Long, Uri?>>()

    /** The settings, or null if the plugin isn't in the vault. [onChanged] is told when they change. */
    fun settings(ctx: Context, tree: Uri, onChanged: (() -> Unit)? = null): JSONObject? {
        val data = locate(ctx, tree) ?: return null
        val text = Vault.recent(ctx, data, onChanged) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun hideLabel(ctx: Context, tree: Uri, onChanged: (() -> Unit)? = null): Boolean =
        settings(ctx, tree, onChanged)?.optBoolean("hideLabel") ?: false

    /** The drawings of the icons buttons use (icon: in a marker), by name, as the plugin keeps them. */
    fun iconPaths(ctx: Context, tree: Uri): Map<String, String> {
        val o = settings(ctx, tree)?.optJSONObject("iconPaths") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
    }

    fun clearOtherMarkers(ctx: Context, tree: Uri): Boolean =
        settings(ctx, tree)?.optBoolean("clearOtherMarkers") ?: false

    private fun locate(ctx: Context, tree: Uri): Uri? {
        val key = tree.toString()
        val now = android.os.SystemClock.uptimeMillis()
        val disk = ctx.getSharedPreferences("task-resetter", Context.MODE_PRIVATE)
        fun keep(uri: Uri?) {
            synchronized(found) { found[key] = android.os.SystemClock.uptimeMillis() to uri }
            disk.edit().putString("data@$key", uri?.toString().orEmpty()).apply()
        }
        val known = synchronized(found) {
            (found[key] ?: disk.getString("data@$key", null)?.let { (now - 60_000) to it.ifEmpty { null }?.let(Uri::parse) })?.also { (at, uri) ->
                if (now - at >= 60_000) {
                    found[key] = now to uri
                    val app = ctx.applicationContext
                    kotlin.concurrent.thread { keep(Vault.find(app, tree, PATH)) }
                }
            }
        }
        return if (known != null) known.second else Vault.find(ctx, tree, PATH).also(::keep)
    }
}
