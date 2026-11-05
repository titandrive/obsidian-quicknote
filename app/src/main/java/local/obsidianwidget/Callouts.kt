package local.obsidianwidget

import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * Obsidian's callouts: which colour and icon each type takes (app.css), and the icons, as drawn by
 * Obsidian (Lucide, ISC licence; a 24-unit box, stroked 2 wide, round caps and joins).
 */
object Callouts {
    /** A type's colour variable (as in "--callout-tip") and its icon. */
    class Kind(val color: String, val icon: String)

    fun kind(type: String): Kind = when (type.lowercase()) {
        "abstract", "summary", "tldr" -> Kind("summary", "clipboard-list")
        "info" -> Kind("info", "info")
        "todo" -> Kind("todo", "check-circle-2")
        "important" -> Kind("important", "flame")
        "tip", "hint" -> Kind("tip", "flame")
        "success", "check", "done" -> Kind("success", "check")
        "question", "help", "faq" -> Kind("question", "help-circle")
        "warning", "caution", "attention" -> Kind("warning", "alert-triangle")
        "failure", "fail", "missing" -> Kind("fail", "x")
        "danger", "error" -> Kind("error", "zap")
        "bug" -> Kind("bug", "bug")
        "example" -> Kind("example", "list")
        "quote", "cite" -> Kind("quote", "quote")
        // "note", and types Obsidian doesn't know.
        else -> Kind("default", "pencil")
    }

    /** Every colour variable, for the theme to work out. */
    val COLORS = listOf("default", "summary", "info", "todo", "important", "tip", "success", "question", "warning", "fail", "error", "bug", "example", "quote")

    private val paths = HashMap<String, Path>()

    /** The icon's outline in its 24-unit box. */
    fun icon(name: String): Path = synchronized(paths) {
        paths.getOrPut(name) { PathParser.createPathFromPathData(ICONS[name] ?: ICONS.getValue("pencil")) }
    }

    private val ICONS = mapOf(
        "flame" to "M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0 5 5 0 0 1 1-3 1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4",
        "pencil" to "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z m15 5 4 4",
        "clipboard-list" to "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 M12 11h4 M12 16h4 M8 11h.01 M8 16h.01",
        "info" to "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M12 16v-4 M12 8h.01",
        "check-circle-2" to "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 m9 12 2 2 4-4",
        "check" to "M20 6 9 17l-5-5",
        "help-circle" to "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 M12 17h.01",
        "alert-triangle" to "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3 M12 9v4 M12 17h.01",
        "x" to "M18 6 6 18 m6 6 12 12",
        "zap" to "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z",
        "bug" to "M12 20v-9 M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4z M14.12 3.88 16 2 M21 21a4 4 0 0 0-3.81-4 M21 5a4 4 0 0 1-3.55 3.97 M22 13h-4 M3 21a4 4 0 0 1 3.81-4 M3 5a4 4 0 0 0 3.55 3.97 M6 13H2 m8 2 1.88 1.88 M9 7.13V6a3 3 0 1 1 6 0v1.13",
        "list" to "M3 5h.01 M3 12h.01 M3 19h.01 M8 5h13 M8 12h13 M8 19h13",
        "quote" to "M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z",
    )
}
