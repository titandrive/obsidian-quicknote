package local.obsidianwidget

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the Style Settings plugin does to the page: themes declare their options in
 * `/* @settings */` comments, the plugin saves the user's choices in its data.json, and then
 * adds classes to body (class-toggle, class-select) or sets CSS variables (variable-*).
 */
object StyleSettings {
    private const val PLUGIN = "obsidian-style-settings"

    private class Setting(val section: String, val fields: Map<String, String>) {
        val id get() = fields["id"].orEmpty()
        val type get() = fields["type"].orEmpty()
        val key get() = "$section@@$id"
    }

    class Applied(private val classes: Set<String>, private val vars: Map<String, String>, private val dark: Map<String, String>, private val light: Map<String, String>) {
        fun classes(@Suppress("UNUSED_PARAMETER") isDark: Boolean) = classes
        fun vars(isDark: Boolean) = vars + if (isDark) dark else light
    }

    /** Null when the plugin isn't enabled in the vault. */
    fun read(ctx: Context, tree: Uri, sheets: List<String>): Applied? {
        val enabled = Vault.readPath(ctx, tree, ".obsidian/community-plugins.json") ?: return null
        if (!enabled.contains("\"$PLUGIN\"")) return null
        val data = Vault.readPath(ctx, tree, ".obsidian/plugins/$PLUGIN/data.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        return apply(sheets.flatMap(::parse), data)
    }

    private fun apply(settings: List<Setting>, data: JSONObject): Applied {
        val classes = HashSet<String>()
        val vars = HashMap<String, String>()
        val dark = HashMap<String, String>()
        val light = HashMap<String, String>()
        fun saved(key: String): String? = data.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()

        for (s in settings) {
            val value = saved(s.key) ?: s.fields["default"]
            when (s.type) {
                "class-toggle" -> if (value == "true") classes.add(s.id)
                "class-select" -> if (!value.isNullOrEmpty() && value != "none") classes.add(value)
                "variable-text", "variable-select" -> value?.let { vars["--${s.id}"] = it }
                "variable-number", "variable-number-slider" -> value?.let { vars["--${s.id}"] = it + s.fields["format"].orEmpty() }
                "variable-color" -> value?.let { color(s.id, it, s.fields["format"], vars) }
                "variable-themed-color" -> {
                    (saved("${s.key}@@dark") ?: s.fields["default-dark"])?.let { color(s.id, it, s.fields["format"], dark) }
                    (saved("${s.key}@@light") ?: s.fields["default-light"])?.let { color(s.id, it, s.fields["format"], light) }
                }
            }
        }
        return Applied(classes, vars, dark, light)
    }

    /** Writes a colour setting the way Style Settings does for each of its formats. */
    private fun color(id: String, value: String, format: String?, out: MutableMap<String, String>) {
        val c = ObsidianTheme.parseColor(value) ?: return
        val (r, g, b) = Triple(Color.red(c), Color.green(c), Color.blue(c))
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }
        val h = hsl[0].roundToInt()
        val sp = (hsl[1] * 100).roundToInt()
        val lp = (hsl[2] * 100).roundToInt()
        when (format) {
            "rgb" -> out["--$id"] = "rgb($r, $g, $b)"
            "rgb-values" -> out["--$id"] = "$r, $g, $b"
            "rgb-split" -> { out["--$id-r"] = "$r"; out["--$id-g"] = "$g"; out["--$id-b"] = "$b" }
            "hsl" -> out["--$id"] = "hsl($h, $sp%, $lp%)"
            "hsl-values" -> out["--$id"] = "$h, $sp%, $lp%"
            "hsl-split" -> { out["--$id-h"] = "$h"; out["--$id-s"] = "$sp%"; out["--$id-l"] = "$lp%" }
            "hsl-split-decimal" -> { out["--$id-h"] = "$h"; out["--$id-s"] = "${hsl[1]}"; out["--$id-l"] = "${hsl[2]}" }
            else -> out["--$id"] = value
        }
    }

    /**
     * Reads the settings out of each `/* @settings */` block. The blocks are YAML; this only needs
     * the section id and each setting's scalar fields, so it goes by indentation.
     */
    private fun parse(css: String): List<Setting> {
        val out = ArrayList<Setting>()
        for (block in BLOCK.findAll(css)) {
            val lines = block.groupValues[1].lines()
            val section = lines.firstNotNullOfOrNull { Regex("^id:\\s*(.+)$").find(it)?.groupValues?.get(1)?.unquote() } ?: continue
            val start = lines.indexOfFirst { it.trimEnd() == "settings:" }
            if (start < 0) continue
            var dashIndent = -1
            var keyIndent = -1
            var fields: HashMap<String, String>? = null
            fun flush() { fields?.let { if (it.containsKey("id")) out.add(Setting(section, it)) } }
            for (line in lines.drop(start + 1)) {
                if (line.isBlank()) continue
                val indent = line.length - line.trimStart().length
                val t = line.trim()
                if (t == "-" || t.startsWith("- ")) {
                    if (dashIndent < 0) dashIndent = indent
                    if (indent == dashIndent) {
                        flush()
                        fields = HashMap()
                        keyIndent = -1
                        val rest = t.removePrefix("-").trim()
                        if (rest.isNotEmpty()) {
                            keyIndent = indent + 2
                            field(rest)?.let { (k, v) -> fields!![k] = v }
                        }
                    }
                    continue
                }
                if (fields == null) continue
                if (indent < dashIndent) break
                if (keyIndent < 0) keyIndent = indent
                if (indent == keyIndent) field(t)?.let { (k, v) -> fields!![k] = v }
            }
            flush()
        }
        return out
    }

    private fun field(t: String): Pair<String, String>? {
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        return t.substring(0, colon).trim() to t.substring(colon + 1).trim().unquote()
    }

    private fun String.unquote() = trim().removeSurrounding("\"").removeSurrounding("'")

    private val BLOCK = Regex("/\\*\\s*@settings(.*?)\\*/", RegexOption.DOT_MATCHES_ALL)
}
