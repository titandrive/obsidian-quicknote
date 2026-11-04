package local.obsidianwidget

import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONObject
import java.io.File

/**
 * Finds the font file Obsidian itself uses for note text, in the order Obsidian would apply it:
 * the Custom Font Loader plugin, then @font-face rules in the theme or enabled snippets, then a
 * file in .obsidian/fonts named after the font in Appearance settings.
 */
object Fonts {
    private const val CONFIG = ".obsidian"
    private val cache = HashMap<String, Typeface>()

    /** Copies the vault's font into app storage and returns its path, or null to use the system font. */
    /** [sheets] is the CSS in effect: the theme and enabled snippets. */
    fun load(ctx: Context, app: JSONObject?, sheets: List<String>): String? {
        val family = app?.optString("textFontFamily").orEmpty()
            .split(',').firstOrNull()?.trim()?.trim('"', '\'').orEmpty()
        val bytes = fromFontLoader(ctx)
            ?: fromFontFace(sheets, family)
            ?: fromFontsFolder(ctx, family)
            ?: return null
        val file = File(ctx.filesDir, "fonts/${Integer.toHexString(bytes.contentHashCode())}-${bytes.size}.ttf")
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
        }
        return file.path
    }

    fun typeface(path: String?): Typeface {
        if (path == null) return Typeface.DEFAULT
        return synchronized(cache) {
            cache.getOrPut(path) { runCatching { Typeface.createFromFile(path) }.getOrDefault(Typeface.DEFAULT) }
        }
    }

    fun compose(path: String?): FontFamily =
        path?.let { File(it) }?.takeIf { it.exists() }?.let { FontFamily(Font(it)) } ?: FontFamily.Default

    private fun fromFontLoader(ctx: Context): ByteArray? {
        val enabled = Vault.readPath(ctx, "$CONFIG/community-plugins.json") ?: return null
        if (!enabled.contains("\"custom-font-loader\"")) return null
        val data = Vault.readPath(ctx, "$CONFIG/plugins/custom-font-loader/data.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val folder = data.optString("font_folder", "$CONFIG/fonts/").trim('/')
        val font = data.optString("font").takeIf { it.isNotEmpty() } ?: return null
        return Vault.find(ctx, "$folder/$font")?.let { Vault.readBytes(ctx, it) }?.takeIf(::usable)
    }

    private fun fromFontFace(sheets: List<String>, family: String): ByteArray? {
        for (css in sheets.asReversed()) {
            for (face in FONT_FACE.findAll(css)) {
                val body = face.groupValues[1]
                val name = FAMILY.find(body)?.groupValues?.get(1)?.trim().orEmpty()
                if (family.isNotEmpty() && !name.equals(family, ignoreCase = true)) continue
                val data = DATA_URL.find(body)?.groupValues?.get(1) ?: continue
                val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
                if (usable(bytes)) return bytes
            }
        }
        return null
    }

    private fun fromFontsFolder(ctx: Context, family: String): ByteArray? {
        if (family.isEmpty()) return null
        val want = normalize(family)
        val name = Vault.list(ctx, "$CONFIG/fonts")
            .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
            .filter { normalize(it.substringBeforeLast('.')).startsWith(want) }
            .minByOrNull { if ("regular" in it.lowercase()) 0 else it.length } ?: return null
        return Vault.find(ctx, "$CONFIG/fonts/$name")?.let { Vault.readBytes(ctx, it) }?.takeIf(::usable)
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** TrueType or OpenType; Android can't load the WOFF files some themes embed. */
    private fun usable(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        return tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    private val FONT_FACE = Regex("@font-face\\s*\\{([^}]*)\\}")
    private val FAMILY = Regex("font-family\\s*:\\s*['\"]?([^;'\"]+)")
    private val DATA_URL = Regex("url\\(\\s*['\"]?data:[^,]*;base64,([A-Za-z0-9+/=\\s]+)")
}
