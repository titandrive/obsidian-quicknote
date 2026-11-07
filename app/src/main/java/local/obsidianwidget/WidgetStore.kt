package local.obsidianwidget

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import org.json.JSONObject

/** A widget's settings; every widget has its own. [mode] is one of "vault", "system", "dark", "light". */
data class WidgetConfig(
    val noteUri: String,
    val notePath: String,
    val theme: String = ObsidianTheme.THEME_VAULT,
    val mode: String = "vault",
    val opacity: Int = 100,
    val textSize: Float = 14f,
    val colors: ThemeColors? = null,
    /** Whether the widget shows its title and buttons above the note. */
    val showHeader: Boolean = true,
    /** The vault this widget's note is in: its tree URI, and its name (for obsidian:// links). */
    val vault: String = "",
    val vaultName: String = "",
    /** Minutes between checks for changes made in Obsidian; 0 is off. */
    val checkMinutes: Int = 30,
    /** Whether the editor shows its formatting toolbar for this widget's note. */
    val showToolbar: Boolean = true,
    /** Reading mode: taps on the widget's lines don't open the editor; tasks still tick and folds still fold. */
    val readOnly: Boolean = false,
    /** Whether the settings cog shows on the widget and its popup (settings are still a long-press away). */
    val showSettings: Boolean = true,
    /** Whether the popup shows the button for source mode, and whether it's in source mode (raw markdown). */
    val showSourceButton: Boolean = true,
    val sourceMode: Boolean = false,
    /**
     * The widget's notes, up to [MAX_SWAP], the shown one among them. With more than one, tapping
     * the note's name shows the next, in order, instead of picking one.
     */
    val swapNotes: List<SwapNote> = emptyList(),
) {
    /** Whether tapping the note's name swaps notes. */
    val swaps: Boolean get() = swapNotes.size >= 2

    /** The note after the shown one in [swapNotes], round to the first again. */
    fun next(): SwapNote {
        val at = swapNotes.indexOfFirst { it.uri == noteUri }
        return swapNotes[(at + 1) % swapNotes.size]
    }

    /** The same widget showing the next of its notes. */
    fun swapped() = next().let { copy(noteUri = it.uri, notePath = it.path) }

    val vaultTree: Uri get() = Uri.parse(vault)

    /** The vault's font, copied into app storage, or null for the system font. */
    val fontFile: String? get() = colors?.font?.takeIf { it.isNotEmpty() }

    val uri: Uri get() = Uri.parse(noteUri)
    val title: String get() = notePath.substringAfterLast('/').removeSuffix(".md")

    /** "dark", "light" or "system", with "like the vault" worked out. */
    val effectiveMode: String get() = if (mode == "vault") colors?.vaultMode ?: "system" else mode

    /**
     * Follows the phone's dark mode. On Android 12+ the widget then carries both looks and the
     * launcher switches between them itself, the moment dark mode changes.
     */
    val followsSystem: Boolean get() = effectiveMode == "system"

    fun palette(ctx: Context, dark: Boolean): Palette =
        (if (dark) colors?.dark else colors?.light) ?: if (dark) Palette.FALLBACK_DARK else Palette.FALLBACK_LIGHT

    fun isDark(ctx: Context): Boolean = when (effectiveMode) {
        "dark" -> true
        "light" -> false
        else -> ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(ctx: Context): Palette = palette(ctx, isDark(ctx))

    fun toJson(): JSONObject = JSONObject()
        .put("noteUri", noteUri).put("notePath", notePath).put("theme", theme).put("mode", mode)
        .put("opacity", opacity).put("textSize", textSize.toDouble()).put("showHeader", showHeader)
        .put("vault", vault).put("vaultName", vaultName).put("checkMinutes", checkMinutes).put("showToolbar", showToolbar)
        .put("readOnly", readOnly).put("showSettings", showSettings)
        .put("showSourceButton", showSourceButton).put("sourceMode", sourceMode)
        .put("notes", org.json.JSONArray().apply { swapNotes.forEach { put(JSONObject().put("uri", it.uri).put("path", it.path)) } })
        .apply {
            colors?.let {
                put("dark", it.dark.toJson()).put("light", it.light.toJson()).put("vaultMode", it.vaultMode).put("vaultFont", it.font)
            }
        }

    companion object {
        const val MAX_SWAP = 5

        /**
         * The widget's notes. Saved while swapping was a switch of its own, they're the ones it
         * swapped between if it was on; before that, the note and its other one.
         */
        private fun swapNotes(o: JSONObject): List<SwapNote> {
            val list = o.optJSONArray("notes") ?: o.optJSONArray("swapNotes")?.takeIf { o.optBoolean("switchNotes") }
            list?.let { a ->
                return (0 until a.length()).map { a.getJSONObject(it) }.map { SwapNote(it.getString("uri"), it.getString("path")) }
            }
            val alt = o.optString("altNoteUri").takeIf { o.optBoolean("switchNotes") }.orEmpty()
            return if (alt.isEmpty()) emptyList()
            else listOf(SwapNote(o.getString("noteUri"), o.getString("notePath")), SwapNote(alt, o.optString("altNotePath")))
        }

        fun fromJson(o: JSONObject) = WidgetConfig(
            o.getString("noteUri"), o.getString("notePath"), o.optString("theme", ObsidianTheme.THEME_VAULT),
            o.optString("mode", "vault"), o.optInt("opacity", 100), o.optDouble("textSize", 14.0).toFloat(),
            if (o.has("dark")) ThemeColors(
                Palette.fromJson(o.getJSONObject("dark")), Palette.fromJson(o.getJSONObject("light")), o.optString("vaultMode", "system"),
                o.optString("vaultFont"),
            ) else null,
            o.optBoolean("showHeader", true),
            o.optString("vault"), o.optString("vaultName"),
            o.optInt("checkMinutes", 30), o.optBoolean("showToolbar", true), o.optBoolean("readOnly", false),
            o.optBoolean("showSettings", true),
            o.optBoolean("showSourceButton", true), o.optBoolean("sourceMode", false),
            swapNotes(o),
        )
    }
}

/** One of the notes a widget swaps between. */
data class SwapNote(val uri: String, val path: String) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

object WidgetStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun load(ctx: Context, id: Int): WidgetConfig? {
        val cfg = prefs(ctx).getString("w$id", null)?.let { runCatching { WidgetConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: return null
        if (cfg.vault.isNotEmpty()) return cfg
        // Saved before widgets had their own vaults: it's the one the app had then.
        val tree = Vault.defaultTree(ctx) ?: return cfg
        return cfg.copy(vault = tree.toString(), vaultName = Vault.defaultName(ctx)).also { save(ctx, id, it) }
    }

    fun save(ctx: Context, id: Int, cfg: WidgetConfig) {
        prefs(ctx).edit().putString("w$id", cfg.toJson().toString()).apply()
    }

    /** Forgets widget [id]'s settings, keeping them as [lastRemoved] for the next widget added. */
    fun delete(ctx: Context, id: Int) {
        val p = prefs(ctx)
        p.edit().apply {
            p.getString("w$id", null)?.let { putString("removed", it) }
            remove("w$id")
        }.apply()
    }

    /** The settings of the widget removed last, so one put back in its place starts as it was. */
    fun lastRemoved(ctx: Context): WidgetConfig? =
        prefs(ctx).getString("removed", null)?.let { runCatching { WidgetConfig.fromJson(JSONObject(it)) }.getOrNull() }
}
