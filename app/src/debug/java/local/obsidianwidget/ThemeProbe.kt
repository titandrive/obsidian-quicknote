package local.obsidianwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Debug aid: resolves every installed theme and writes the palettes to files/probe.json. */
class ThemeProbe : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        thread {
            try {
                val out = JSONObject()
                for (name in listOf(ObsidianTheme.THEME_DEFAULT) + Vault.themes(ctx)) {
                    val start = System.currentTimeMillis()
                    val entry = runCatching {
                        val t = ObsidianTheme.load(ctx, name)
                        JSONObject().put("dark", t.dark.toJson()).put("light", t.light.toJson()).put("font", t.font)
                    }.getOrElse { JSONObject().put("error", it.toString()) }
                    out.put(name.ifEmpty { "(default)" }, entry.put("ms", System.currentTimeMillis() - start))
                }
                File(ctx.filesDir, "probe.json").writeText(out.toString(1))
            } finally {
                result.finish()
            }
        }
    }
}
