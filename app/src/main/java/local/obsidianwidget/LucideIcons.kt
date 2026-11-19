package local.obsidianwidget

import android.content.Context

/**
 * Every Lucide icon Obsidian has, by name (as Task Resetter's icon: takes them), as path data in a
 * 24-unit box. Read from the app's assets the first time it's needed.
 */
object LucideIcons {
    @Volatile private var icons: Map<String, String>? = null

    /** Reads the icons, once; call before [data]. */
    fun load(ctx: Context) {
        if (icons != null) return
        synchronized(this) {
            if (icons != null) return
            icons = runCatching {
                ctx.applicationContext.assets.open("lucide-icons.tsv").bufferedReader().useLines { lines ->
                    lines.mapNotNull { l -> l.indexOf('\t').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
                }
            }.getOrDefault(emptyMap())
        }
    }

    /** An icon's path data, or null if there's no such icon (or [load] hasn't run). */
    fun data(name: String): String? = icons?.get(name.removePrefix("lucide-"))
}
