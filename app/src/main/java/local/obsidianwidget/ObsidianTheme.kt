package local.obsidianwidget

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The handful of Obsidian colours the widget draws with. */
data class Palette(
    val background: Int,
    val backgroundAlt: Int,
    val text: Int,
    val muted: Int,
    val faint: Int,
    val accent: Int,
    val link: Int,
    val tag: Int,
    val bold: Int,
    val italic: Int,
    val code: Int,
    val codeBg: Int,
    val highlight: Int,
    val quote: Int,
    val checkbox: Int,
    val headings: List<Int>,
    /** The note title above the text (Obsidian's inline title). */
    val title: Int = headings[0],
    /** List bullets and numbers. */
    val marker: Int = faint,
    /** Heading sizes relative to body text, h1 to h6. */
    val headingSizes: List<Float> = DEFAULT_HEADING_SIZES,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("background", background).put("backgroundAlt", backgroundAlt)
        .put("text", text).put("muted", muted).put("faint", faint)
        .put("accent", accent).put("link", link).put("tag", tag)
        .put("bold", bold).put("italic", italic).put("code", code).put("codeBg", codeBg)
        .put("highlight", highlight).put("quote", quote).put("checkbox", checkbox)
        .put("headings", JSONArray(headings))
        .put("title", title).put("marker", marker)
        .put("headingSizes", JSONArray(headingSizes.map { it.toDouble() }))

    companion object {
        val DEFAULT_HEADING_SIZES = listOf(1.802f, 1.602f, 1.424f, 1.266f, 1.125f, 1f)

        fun fromJson(o: JSONObject): Palette {
            val headings = o.getJSONArray("headings").let { a -> List(a.length()) { a.getInt(it) } }
            return Palette(
                o.getInt("background"), o.getInt("backgroundAlt"),
                o.getInt("text"), o.getInt("muted"), o.getInt("faint"),
                o.getInt("accent"), o.getInt("link"), o.getInt("tag"),
                o.getInt("bold"), o.getInt("italic"), o.getInt("code"), o.getInt("codeBg"),
                o.getInt("highlight"), o.getInt("quote"), o.getInt("checkbox"),
                headings,
                o.optInt("title", headings[0]),
                o.optInt("marker", o.getInt("faint")),
                o.optJSONArray("headingSizes")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } } ?: DEFAULT_HEADING_SIZES,
            )
        }

        // Last resort for anything a theme leaves unresolvable; roughly Obsidian's stock colours.
        val FALLBACK_DARK = Palette(
            0xFF1E1E1E.toInt(), 0xFF262626.toInt(), 0xFFDADADA.toInt(), 0xFFBABABA.toInt(), 0xFF666666.toInt(),
            0xFFA88BFA.toInt(), 0xFFA88BFA.toInt(), 0xFFA88BFA.toInt(), 0xFFDADADA.toInt(), 0xFFDADADA.toInt(),
            0xFFDADADA.toInt(), 0xFF242424.toInt(), 0x66FFD000, 0xFFA88BFA.toInt(), 0xFFA88BFA.toInt(),
            List(6) { 0xFFDADADA.toInt() },
        )
        val FALLBACK_LIGHT = Palette(
            0xFFFFFFFF.toInt(), 0xFFF6F6F6.toInt(), 0xFF222222.toInt(), 0xFF5C5C5C.toInt(), 0xFFABABAB.toInt(),
            0xFF8A5CF5.toInt(), 0xFF8A5CF5.toInt(), 0xFF8A5CF5.toInt(), 0xFF222222.toInt(), 0xFF222222.toInt(),
            0xFF222222.toInt(), 0xFFFAFAFA.toInt(), 0x66FFD000, 0xFF8A5CF5.toInt(), 0xFF8A5CF5.toInt(),
            List(6) { 0xFF222222.toInt() },
        )
    }
}

/**
 * Light and dark palettes for one theme, plus the mode the vault itself uses and the path of its
 * font file copied into app storage ("" for the system font).
 */
data class ThemeColors(val dark: Palette, val light: Palette, val vaultMode: String, val font: String = "")

/**
 * Pulls colours out of an Obsidian theme's CSS. Themes are written as CSS variables layered on
 * top of Obsidian's own defaults, so this seeds those defaults, then applies every rule that would
 * match a note on Obsidian's page (taking the classes Style Settings adds into account) in
 * specificity order, then resolves var(), calc(), hsl() and rgb() down to colours.
 */
object ObsidianTheme {
    const val THEME_VAULT = "@vault"
    const val THEME_DEFAULT = ""

    /*
     * Working a theme out means parsing its CSS, up to a couple of seconds for big ones, so results
     * are kept in memory and in the cache folder, keyed by the dates and sizes of every vault file
     * that goes into them. Checking those is quick; anything changed in Obsidian is picked up.
     */
    private val memory = HashMap<String, Pair<String, ThemeColors>>()
    private var shared: Pair<Long, String>? = null

    /**
     * The part of a theme's key every theme shares (settings, plugins, snippets, fonts, the app's
     * version), reused for a few seconds so the theme picker's run of loads checks it once.
     */
    private fun sharedStamp(ctx: Context): String = synchronized(this) {
        val now = android.os.SystemClock.uptimeMillis()
        shared?.takeIf { now - it.first < 3000 }?.let { return it.second }
        val stamp = Vault.stamp(
            ctx,
            listOf(
                ".obsidian/appearance.json", ".obsidian/community-plugins.json",
                ".obsidian/plugins/obsidian-style-settings/data.json", ".obsidian/plugins/custom-font-loader/data.json",
                ".obsidian/snippets", ".obsidian/fonts",
            ),
        ) + "|" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        shared = now to stamp
        stamp
    }

    fun load(ctx: Context, choice: String): ThemeColors {
        val app = Vault.appearance(ctx)
        val name = if (choice == THEME_VAULT) app?.optString("cssTheme").orEmpty() else choice
        val key = sharedStamp(ctx) + "|" + Vault.stamp(ctx, listOf(".obsidian/themes/$name", ".obsidian/themes/$name.css"))
        val file = File(ctx.cacheDir, "themes/${choice.hashCode().toUInt()}.json")
        synchronized(memory) {
            memory[choice]?.takeIf { it.first == key }?.let { return it.second }
            runCatching {
                val o = JSONObject(file.readText())
                if (o.getString("key") == key) {
                    val t = ThemeColors(
                        Palette.fromJson(o.getJSONObject("dark")), Palette.fromJson(o.getJSONObject("light")),
                        o.getString("vaultMode"), o.getString("font"),
                    )
                    // The copied font file lives in app storage; if it's gone, work it out again.
                    if (t.font.isEmpty() || File(t.font).exists()) {
                        memory[choice] = key to t
                        return t
                    }
                }
            }
        }
        val t = compute(ctx, app, name)
        synchronized(memory) {
            memory[choice] = key to t
            runCatching {
                file.parentFile!!.mkdirs()
                file.writeText(
                    JSONObject().put("key", key).put("dark", t.dark.toJson()).put("light", t.light.toJson())
                        .put("vaultMode", t.vaultMode).put("font", t.font).toString(),
                )
            }
        }
        return t
    }

    private fun compute(ctx: Context, app: JSONObject?, name: String): ThemeColors {
        val css = name.takeIf { it.isNotEmpty() }?.let { Vault.themeCss(ctx, it) }
        val snippets = app?.optJSONArray("enabledCssSnippets")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            .mapNotNull { Vault.readPath(ctx, ".obsidian/snippets/$it.css") }
        // Snippets load after the theme, so they win ties.
        val sheets = listOfNotNull(css) + snippets
        val rules = sheets.flatMap(::parseRules)
        val style = StyleSettings.read(ctx, sheets)
        val accent = app?.optString("accentColor")?.takeIf { it.isNotEmpty() }
        val mode = when (app?.optString("theme")) {
            "obsidian" -> "dark"
            "moonstone" -> "light"
            else -> "system"
        }
        val font = Fonts.load(ctx, app, sheets).orEmpty()
        return ThemeColors(
            palette(rules, style, true, accent), palette(rules, style, false, accent), mode, font,
        )
    }

    fun palette(rules: List<Rule>, style: StyleSettings.Applied?, dark: Boolean, accent: String?): Palette {
        val vars = HashMap(COMMON)
        vars.putAll(if (dark) DARK else LIGHT)
        accent?.let(::parseColor)?.let { c ->
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(c, hsl)
            vars["--accent-h"] = "${hsl[0]}"
            vars["--accent-s"] = "${hsl[1] * 100}%"
            vars["--accent-l"] = "${hsl[2] * 100}%"
        }
        overlay(rules, style?.classes(dark).orEmpty(), style?.vars(dark).orEmpty(), dark, vars)

        val r = Resolver(vars)
        val base = if (dark) Palette.FALLBACK_DARK else Palette.FALLBACK_LIGHT
        fun c(vararg names: String, fallback: Int): Int =
            names.firstNotNullOfOrNull { r.color(it) } ?: fallback

        val text = c("--text-normal", fallback = base.text)
        val accentColor = c("--text-accent", "--interactive-accent", "--color-accent", fallback = base.accent)
        val background = c("--background-primary", fallback = base.background).let { bg ->
            if (Color.alpha(bg) >= 200) bg or 0xFF000000.toInt()
            else {
                // A see-through theme (glass effects): lay it over the page's own background, like Obsidian does.
                val page = r.color(PAGE_BG)
                    ?.takeIf { Color.alpha(it) >= 200 } ?: base.background
                androidx.core.graphics.ColorUtils.compositeColors(bg, page or 0xFF000000.toInt())
            }
        }
        return Palette(
            background = background,
            backgroundAlt = c("--background-secondary", fallback = base.backgroundAlt),
            text = text,
            muted = c("--text-muted", fallback = base.muted),
            faint = c("--text-faint", fallback = base.faint),
            accent = accentColor,
            link = c("--link-color", fallback = accentColor),
            tag = c("--tag-color", fallback = accentColor),
            bold = c("@bold-color", "--bold-color", fallback = text),
            italic = c("@italic-color", "--italic-color", fallback = text),
            code = c("--code-normal", fallback = text),
            codeBg = c("--code-background", "--background-primary-alt", fallback = base.codeBg),
            // Themes with a solid highlight blend it into the text (mix-blend-mode), which a widget can't do.
            highlight = c("--text-highlight-bg", fallback = base.highlight).let { if (Color.alpha(it) > 0x99) (it and 0xFFFFFF) or 0x59000000 else it },
            quote = c("--blockquote-border-color", fallback = accentColor),
            checkbox = c("--checkbox-color", fallback = accentColor),
            headings = (1..6).map { c("@h$it-color", "--h$it-color", fallback = text) },
            title = c("@title-color", "--inline-title-color", "@h1-color", "--h1-color", fallback = text),
            marker = c("--list-marker-color", fallback = base.faint),
            headingSizes = (1..6).map { r.size("--h$it-size") ?: Palette.DEFAULT_HEADING_SIZES[it - 1] },
        )
    }

    /** One CSS rule: its comma-separated selectors and the custom properties it sets, in source order. */
    class Rule(val selectors: List<String>, val vars: List<Decl>)

    class Decl(val name: String, val value: String, val important: Boolean)

    private fun parseRules(css: String): List<Rule> = BLOCK.findAll(css.replace(COMMENT, "")).mapNotNull { m ->
        val vars = m.groupValues[2].split(';').mapNotNull { decl ->
            val colon = decl.indexOf(':')
            val name = if (colon < 0) "" else decl.substring(0, colon).trim()
            val value = if (colon < 0) "" else decl.substring(colon + 1)
            val important = value.contains("!important")
            when {
                name.startsWith("--") -> Decl(name, value.replace("!important", "").trim(), important)
                // Kept as a pseudo-variable: what shows through when a theme's panes are see-through.
                name == "background-color" || name == "background" -> Decl(PAGE_BG, value.replace("!important", "").trim(), important)
                else -> null
            }
        }
        // Statements like @charset "UTF-8"; or @import end up in front of the selector; drop them.
        val selector = m.groupValues[1].substringAfterLast(';')
        val selectors = selector.split(',').map { it.trim() }
        // A heading's own background isn't the page's.
        val kept = if (selectors.any { sel -> SIMPLE.findAll(sel).any { it.value in ELEMENT_TAGS } }) vars.filter { it.name != PAGE_BG } else vars
        listOfNotNull(if (kept.isEmpty()) null else Rule(selectors, kept)) + elementColors(selectors, m.groupValues[2])
    }.flatten().toList()

    /**
     * Colours a theme puts straight on headings, bold, italic or the title (`h1 { color: ... }`)
     * instead of through Obsidian's variables, as pseudo-variables like "@h1-color". Gradient text
     * (background-clip: text) is approximated by the gradient's first colour.
     */
    private fun elementColors(selectors: List<String>, body: String): List<Rule> {
        val decls = body.split(';')
        fun prop(name: String) = decls.lastOrNull { it.substringBefore(':').trim() == name }?.substringAfter(':')
        val color = prop("color")
        val gradient = if (prop("background-clip")?.contains("text") == true || prop("-webkit-background-clip")?.contains("text") == true)
            (prop("background-image") ?: prop("background"))?.let { GRADIENT_STOP.find(it)?.value } else null
        val value = gradient ?: color ?: return emptyList()
        return selectors.mapNotNull { sel ->
            // Bold text inside italic (or the reverse) is its own style; only plain bold/italic is wanted.
            val tokens = SIMPLE.findAll(sel).map { it.value }.toSet()
            if (tokens.any { it in BOLD } && tokens.any { it in ITALIC }) return@mapNotNull null
            // The element the rule styles is the last compound selector, e.g. "h1" in ".markdown-rendered h1".
            val subject = SIMPLE.findAll(sel.trim().split(Regex("[\\s>+~]+")).last()).map { it.value }.toList()
            val level = subject.firstNotNullOfOrNull { HEADING.matchEntire(it)?.groupValues?.drop(1)?.first { g -> g.isNotEmpty() } }
            val target = when {
                level != null -> "@h$level-color"
                subject.any { it in BOLD } -> "@bold-color"
                subject.any { it in ITALIC } -> "@italic-color"
                ".inline-title" in subject -> "@title-color"
                else -> return@mapNotNull null
            }
            Rule(listOf(sel), listOf(Decl(target, value.replace("!important", "").trim(), value.contains("!important"))))
        }
    }

    /**
     * Applies every rule that matches a note on Obsidian's page in the browser's cascade order:
     * normal declarations by specificity, then Style Settings (set on body), then !important ones.
     */
    private fun overlay(rules: List<Rule>, classes: Set<String>, styleVars: Map<String, String>, dark: Boolean, vars: MutableMap<String, String>) {
        val matched = rules.mapIndexedNotNull { i, rule ->
            rule.selectors.maxOfOrNull { specificity(it, classes, dark) }?.takeIf { it >= 0 }?.let { Triple(it, i, rule) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        for (important in listOf(false, true)) {
            for ((_, _, rule) in matched) rule.vars.forEach { if (it.important == important) vars[it.name] = it.value }
            if (!important) vars.putAll(styleVars)
        }
    }

    /**
     * The selector's specificity if it matches the page around a note, else -1. The page is
     * body with .theme-dark or .theme-light plus any Style Settings [classes], and the note's
     * reading view inside it.
     */
    private fun specificity(selector: String, classes: Set<String>, dark: Boolean): Int {
        if (selector.isEmpty() || selector.startsWith("@")) return -1
        var score = 0
        var i = 0
        for (m in SIMPLE.findAll(selector)) {
            // Anything the tokenizer skipped (attribute selectors, *, ...) means it isn't a page-wide rule.
            if (selector.substring(i, m.range.first).any { !it.isWhitespace() && it != '>' }) return -1
            i = m.range.last + 1
            val t = m.value
            when {
                t == ".theme-dark" -> if (dark) score += 10 else return -1
                t == ".theme-light" -> if (!dark) score += 10 else return -1
                t.startsWith(":not(") -> {
                    val inner = t.removePrefix(":not(").removeSuffix(")").trim()
                    // :not(.a, .b) excludes the page if it has any of them.
                    val excluded = inner.split(',').map { it.trim() }.any { cls ->
                        when {
                            cls == ".theme-dark" -> dark
                            cls == ".theme-light" -> !dark
                            cls.startsWith(".") && !cls.drop(1).contains('.') ->
                                cls.drop(1) in classes || cls.drop(1) in PAGE_CLASSES
                            else -> false
                        }
                    }
                    if (excluded) return -1
                    score += 10
                }
                t.startsWith(".") -> if (t.drop(1) in classes || t.drop(1) in PAGE_CLASSES) score += 10 else return -1
                t == ":root" -> score += 10
                t == "body" || t == "html" || t in ELEMENT_TAGS -> score += 1
                t.startsWith(".") && EDITOR_CLASS.matches(t.drop(1)) -> score += 10
                else -> return -1
            }
        }
        if (selector.substring(i).any { !it.isWhitespace() }) return -1
        return score
    }

    private class Resolver(val vars: Map<String, String>) {
        fun color(name: String): Int? = vars[name]?.let { parseColor(evalCalc(subst(it, 0))) }

        /** A size in em (or a bare number), e.g. "1.802em". */
        fun size(name: String): Float? = vars[name]?.let { evalCalc(subst(it, 0)).trim() }
            ?.let { Regex("^([0-9.]+)(em)?$").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

        private fun subst(s: String, depth: Int): String {
            if (depth > 24) return ""
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val at = s.indexOf("var(", i)
                if (at < 0) {
                    sb.append(s, i, s.length); break
                }
                sb.append(s, i, at)
                val end = matchParen(s, at + 3)
                if (end < 0) {
                    sb.append(s, at, s.length); break
                }
                val inner = s.substring(at + 4, end)
                val comma = topLevelComma(inner)
                val name = (if (comma < 0) inner else inner.substring(0, comma)).trim()
                val value = vars[name] ?: if (comma < 0) "" else inner.substring(comma + 1).trim()
                sb.append(subst(value, depth + 1))
                i = end + 1
            }
            return sb.toString()
        }
    }

    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        for (j in open until s.length) {
            if (s[j] == '(') depth++
            else if (s[j] == ')' && --depth == 0) return j
        }
        return -1
    }

    private fun topLevelComma(s: String): Int {
        var depth = 0
        s.forEachIndexed { i, ch ->
            when (ch) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) return i
            }
        }
        return -1
    }

    /** Replaces each calc(...) with its value, e.g. "calc(66% - 5%)" becomes "61%". */
    private fun evalCalc(s: String): String {
        var out = s
        while (true) {
            val at = out.indexOf("calc(")
            if (at < 0) return out
            val end = matchParen(out, at + 4)
            if (end < 0) return out
            val value = runCatching { Calc(out.substring(at + 5, end).replace("calc(", "(")).parse() }.getOrNull()
            val text = value?.let { (n, unit) -> "${if (abs(n - n.roundToInt()) < 1e-6) n.roundToInt().toString() else n.toString()}$unit" } ?: "0"
            out = out.substring(0, at) + text + out.substring(end + 1)
        }
    }

    /** Tiny recursive-descent evaluator for calc() bodies; keeps the first unit it sees. */
    private class Calc(val s: String) {
        var i = 0
        var unit = ""
        fun parse(): Pair<Double, String> = expr() to unit
        fun expr(): Double {
            var v = term()
            while (true) {
                ws()
                v = when (peek()) {
                    '+' -> { i++; v + term() }
                    '-' -> { i++; v - term() }
                    else -> return v
                }
            }
        }
        fun term(): Double {
            var v = factor()
            while (true) {
                ws()
                v = when (peek()) {
                    '*' -> { i++; v * factor() }
                    '/' -> { i++; v / factor() }
                    else -> return v
                }
            }
        }
        fun factor(): Double {
            ws()
            if (peek() == '(') {
                i++
                val v = expr()
                ws(); i++ // ')'
                return v
            }
            if (peek() == '-') { i++; return -factor() }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val n = s.substring(start, i).toDouble()
            val uStart = i
            while (i < s.length && (s[i].isLetter() || s[i] == '%')) i++
            if (unit.isEmpty()) unit = s.substring(uStart, i)
            return n
        }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek() = if (i < s.length) s[i] else '\u0000'
    }

    fun parseColor(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.startsWith("#")) {
            val h = s.drop(1)
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            val v = full.toLongOrNull(16) ?: return null
            return if (full.length == 6) (0xFF000000L or v).toInt()
            else ((v and 0xFF) shl 24 or (v shr 8)).toInt() // #rrggbbaa → argb
        }
        val fn = Regex("^(rgba?|hsla?)\\((.*)\\)$").find(s) ?: return NAMED[s]
        val args = fn.groupValues[2].split(',', '/', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (args.size < 3) return null
        fun num(a: String) = a.removeSuffix("%").removeSuffix("deg").toFloatOrNull()
        val alpha = args.getOrNull(3)?.let { a -> num(a)?.let { if (a.endsWith("%")) it / 100 else it } } ?: 1f
        return if (fn.groupValues[1].startsWith("rgb")) {
            val (r, g, b) = args.take(3).map { a -> num(a)?.let { if (a.endsWith("%")) it * 2.55f else it } ?: return null }
            Color.argb(clamp(alpha * 255), clamp(r), clamp(g), clamp(b))
        } else {
            val h = num(args[0]) ?: return null
            val sat = num(args[1]) ?: return null
            val l = num(args[2]) ?: return null
            val c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(((h % 360) + 360) % 360, (sat / 100).coerceIn(0f, 1f), (l / 100).coerceIn(0f, 1f)))
            (c and 0x00FFFFFF) or (clamp(alpha * 255) shl 24)
        }
    }

    private fun clamp(f: Float) = f.roundToInt().coerceIn(0, 255)

    private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK = Regex("([^{}]*)\\{([^{}]*)\\}")
    private const val PAGE_BG = "@page-background"

    /** One simple selector: a class, :not(...), pseudo-class, or tag. */
    private val SIMPLE = Regex(":not\\([^)]*\\)|[.:#]?[A-Za-z_][\\w-]*")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val ELEMENT_TAGS = HEADING_TAGS + setOf("strong", "b", "em", "i")
    /** Classes on headings and bold/italic text in the editor, which themes style alongside h1 / strong. */
    private val EDITOR_CLASS = Regex("cm-header(-[1-6])?|HyperMD-header(-[1-6])?|cm-strong|cm-em|cm-line|cm-content|HyperMD-list-line|markdown-rendered")
    private val BOLD = setOf("strong", "b", ".cm-strong")
    private val ITALIC = setOf("em", "i", ".cm-em")
    private val HEADING = Regex("h([1-6])|\\.cm-header-([1-6])|\\.HyperMD-header-([1-6])")
    private val GRADIENT_STOP = Regex("#[0-9a-fA-F]{3,8}\\b|(?:rgba?|hsla?)\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)|var\\(--[\\w-]+\\)")

    /** Classes always present around a note in Obsidian's mobile reading view. */
    private val PAGE_CLASSES = setOf(
        "css-settings-manager", "is-mobile", "is-phone", "app-container", "workspace", "workspace-leaf",
        "workspace-leaf-content", "view-content", "markdown-reading-view", "markdown-preview-view",
        "markdown-rendered", "markdown-preview-section", "markdown-source-view", "mod-cm6", "is-live-preview",
        "cm-s-obsidian", "inline-title", "show-inline-title", "is-readable-line-width",
    )

    private val NAMED = mapOf(
        "white" to Color.WHITE, "black" to Color.BLACK, "transparent" to Color.TRANSPARENT,
        "red" to Color.RED, "green" to 0xFF008000.toInt(), "blue" to Color.BLUE, "gray" to Color.GRAY, "grey" to Color.GRAY,
        "orange" to 0xFFFFA500.toInt(), "yellow" to Color.YELLOW, "purple" to 0xFF800080.toInt(),
    )

    // Obsidian's own variables (app.css), which themes build on.
    private val COMMON = mapOf(
        "--accent-h" to "254", "--accent-s" to "80%", "--accent-l" to "68%",
        "--color-accent" to "hsl(var(--accent-h), var(--accent-s), var(--accent-l))",
        "--background-primary" to "var(--color-base-00)",
        "--background-primary-alt" to "var(--color-base-10)",
        "--background-secondary" to "var(--color-base-20)",
        "--text-normal" to "var(--color-base-100)",
        "--text-muted" to "var(--color-base-70)",
        "--text-faint" to "var(--color-base-50)",
        "--text-accent" to "var(--color-accent)",
        "--interactive-accent" to "var(--color-accent)",
        "--link-color" to "var(--text-accent)",
        "--tag-color" to "var(--text-accent)",
        "--code-background" to "var(--background-primary-alt)",
        "--code-normal" to "var(--text-normal)",
        "--checkbox-color" to "var(--interactive-accent)",
        "--blockquote-border-color" to "var(--interactive-accent)",
        "--text-highlight-bg" to "rgba(255, 208, 0, 0.4)",
    ) + (1..6).associate { "--h$it-color" to "var(--text-normal)" }

    private val DARK = mapOf(
        "--color-base-00" to "#1e1e1e", "--color-base-05" to "#212121", "--color-base-10" to "#242424",
        "--color-base-20" to "#262626", "--color-base-25" to "#2a2a2a", "--color-base-30" to "#363636",
        "--color-base-35" to "#3f3f3f", "--color-base-40" to "#555555", "--color-base-50" to "#666666",
        "--color-base-60" to "#999999", "--color-base-70" to "#bababa", "--color-base-100" to "#dadada",
    )

    private val LIGHT = mapOf(
        "--color-base-00" to "#ffffff", "--color-base-05" to "#fcfcfc", "--color-base-10" to "#fafafa",
        "--color-base-20" to "#f6f6f6", "--color-base-25" to "#e3e3e3", "--color-base-30" to "#e0e0e0",
        "--color-base-35" to "#d4d4d4", "--color-base-40" to "#bdbdbd", "--color-base-50" to "#ababab",
        "--color-base-60" to "#707070", "--color-base-70" to "#5c5c5c", "--color-base-100" to "#222222",
    )
}
