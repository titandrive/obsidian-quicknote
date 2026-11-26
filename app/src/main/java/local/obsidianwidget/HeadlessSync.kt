package local.obsidianwidget

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Background sync: Obsidian Headless, Obsidian's own command-line Sync client, run on Node.js in
 * this app, so notes edited on the widget sync without Obsidian being opened.
 *
 * Node (Termux's Android build, arm64) comes with the app as native libraries, see
 * tools/fetch_node.py. Headless itself isn't open source, so it isn't in the app: it's
 * downloaded from npm on setup, into the app's own storage. Its one native module, better-sqlite3,
 * is swapped for a stand-in on Node's built-in SQLite (assets/headless/better-sqlite3.js).
 *
 * Headless works on the vault folder itself, which takes All files access. It's meant to be the
 * phone's only Sync client: Sync in Obsidian on the phone has to be off, or the two clash.
 */
object HeadlessSync {
    private const val PACKAGE = "obsidian-headless"
    private const val REGISTRY = "https://registry.npmjs.org/"

    class Result(val code: Int, val output: String) {
        val ok get() = code == 0
    }

    /** Where Headless and its settings live: the app's own storage, standing in for a home folder. */
    fun home(ctx: Context) = File(ctx.filesDir, "headless").also { it.mkdirs() }

    private fun node(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libobnode.so")
    private fun cli(ctx: Context) = File(home(ctx), "node_modules/$PACKAGE/cli.js")

    /** Whether this phone can run it: Node is only built for 64-bit ARM. */
    fun available(ctx: Context) = node(ctx).exists()

    fun installed(ctx: Context) = cli(ctx).exists()

    fun loggedIn(ctx: Context) = File(home(ctx), ".obsidian-headless/auth_token").exists()

    /** Whether files can be reached by path, which Headless needs: All files access. */
    fun canReachFiles() = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

    /** The vault folder's path on the phone, from the folder picked for it (on the phone's own storage). */
    fun vaultPath(tree: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /**
     * Downloads Headless (the latest version) and the library it needs from npm, and puts in the
     * SQLite stand-in. Network and storage: not on the main thread.
     */
    fun install(ctx: Context) {
        val modules = File(home(ctx), "node_modules")
        val meta = JSONObject(URL("$REGISTRY$PACKAGE/latest").readText())
        unpack(meta.getJSONObject("dist").getString("tarball"), File(modules, PACKAGE))
        val deps = meta.optJSONObject("dependencies") ?: JSONObject()
        for (name in deps.keys()) {
            if (name == "better-sqlite3") continue
            val dep = JSONObject(URL("$REGISTRY$name/${deps.getString(name)}").readText())
            unpack(dep.getJSONObject("dist").getString("tarball"), File(modules, name))
        }
        File(modules, "better-sqlite3").apply {
            deleteRecursively()
            mkdirs()
            File(this, "package.json").writeText("""{"name":"better-sqlite3","main":"index.js"}""")
            File(this, "index.js").writeText(ctx.assets.open("headless/better-sqlite3.js").bufferedReader().readText())
        }
    }

    /** Unpacks an npm package (a .tgz with everything under "package/") into [dir]. */
    private fun unpack(url: String, dir: File) {
        dir.deleteRecursively()
        GZIPInputStream(URL(url).openStream().buffered()).use { input ->
            fun read(buf: ByteArray): Int {
                var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                return n
            }
            val header = ByteArray(512)
            while (true) {
                if (read(header) < 512 || header.all { it == 0.toByte() }) break
                fun field(from: Int, len: Int) = String(header, from, len).trimEnd('\u0000', ' ')
                val name = field(345, 155).let { if (it.isEmpty()) "" else "$it/" } + field(0, 100)
                val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val path = name.removePrefix("package/")
                val data = ByteArray(size.toInt())
                read(data)
                read(ByteArray(((512 - size % 512) % 512).toInt()))
                if ((type == '0' || type == '\u0000') && path.isNotEmpty() && !path.contains("..")) {
                    File(dir, path).apply { parentFile?.mkdirs(); writeBytes(data) }
                }
            }
        }
    }

    /** Headless's command line, ready to run with [args]. */
    fun command(ctx: Context, vararg args: String): ProcessBuilder {
        val home = home(ctx)
        return ProcessBuilder(listOf(node(ctx).path, cli(ctx).path) + args).directory(home).redirectErrorStream(true).apply {
            environment().apply {
                put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
                put("HOME", home.path)
                put("TMPDIR", ctx.cacheDir.path)
                // Node's OpenSSL looks for certificates where Termux keeps them: Android's own instead.
                put("SSL_CERT_FILE", certificates(ctx).path)
                put("NODE_OPTIONS", "--no-warnings --use-openssl-ca")
            }
        }
    }

    /**
     * The phone's trusted certificates, in one file OpenSSL can read. Android keeps them one per file,
     * named by a hash OpenSSL doesn't use, so pointing it at the folder finds none; each file is PEM,
     * so they're put together. Made again each time, so they follow system updates.
     */
    private fun certificates(ctx: Context): File {
        val out = File(home(ctx), "cacert.pem")
        val dirs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts").map(::File)
        val dir = dirs.firstOrNull { it.listFiles()?.isNotEmpty() == true } ?: return out
        runCatching {
            out.writeText(dir.listFiles().orEmpty().sortedBy { it.name }.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") })
        }
        return out
    }

    /** Runs a Headless command to the end (at most [timeoutS] seconds), with nothing to type in. */
    fun run(ctx: Context, vararg args: String, timeoutS: Long = 120): Result {
        val process = command(ctx, *args).start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(2000)
        return Result(runCatching { process.exitValue() }.getOrDefault(-1), output.toString().trim())
    }

    fun login(ctx: Context, email: String, password: String, mfa: String): Result =
        run(ctx, "login", "--email", email, "--password", password, *(if (mfa.isNotBlank()) arrayOf("--mfa", mfa.trim()) else arrayOf()))

    fun logout(ctx: Context): Result {
        prefs(ctx).edit().remove("account").remove("setupDone").apply()
        return run(ctx, "logout")
    }

    /** How a sign-in went. */
    sealed class SignIn {
        object Done : SignIn()
        /** The account has two-factor authentication: sign in again with its code. */
        object NeedsCode : SignIn()
        object WrongCode : SignIn()
        class Failed(val message: String) : SignIn()
    }

    /** Signs in; on success, keeps who's signed in ("Name (email)") for the screen to show. */
    fun signIn(ctx: Context, email: String, password: String, code: String): SignIn {
        val r = login(ctx, email, password, code)
        val who = Regex("Logged in as (.+)$", RegexOption.MULTILINE).find(r.output)?.groupValues?.get(1)?.trim()
        return when {
            who != null && loggedIn(ctx) -> { prefs(ctx).edit().putString("account", who).apply(); SignIn.Done }
            r.output.contains("2FA code is incorrect") -> SignIn.WrongCode
            r.output.contains("2FA code") -> SignIn.NeedsCode
            // The server's own message when it gave one (wrong password, say), or all Headless printed.
            else -> SignIn.Failed(
                Regex("error: ['\"](.+?)['\"]").find(r.output)?.groupValues?.get(1)
                    ?: r.output.removePrefix("Login failed:").trim().take(1500).ifEmpty { "Couldn't sign in (exit ${r.code})" },
            )
        }
    }

    /** Who's signed in, "Name (email)", as Headless said at sign-in. */
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)

    /** Whether setup was seen through to the end, after which the screen shows sync's settings. */
    fun setupDone(ctx: Context) = prefs(ctx).getBoolean("setupDone", false)
    fun setSetupDone(ctx: Context, done: Boolean) = prefs(ctx).edit().putBoolean("setupDone", done).apply()

    class RemoteVault(val id: String, val name: String)

    /** The vaults on the account, its own and shared ones. */
    fun remoteVaults(ctx: Context): Pair<List<RemoteVault>, String?> {
        val result = run(ctx, "sync-list-remote", "--json")
        val json = runCatching { JSONObject(result.output.substring(result.output.indexOf('{'))) }.getOrNull()
            ?: return emptyList<RemoteVault>() to result.output.ifBlank { "Couldn't list vaults" }
        val out = ArrayList<RemoteVault>()
        for (key in listOf("vaults", "shared")) {
            val list = json.optJSONArray(key) ?: continue
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { out.add(RemoteVault(it.optString("id"), it.optString("name"))) }
        }
        return out to null
    }

    /** Connects the vault folder at [path] to remote vault [id]; [password] for an end-to-end encrypted one. */
    fun setup(ctx: Context, id: String, path: String, password: String): Result {
        val device = "${Build.MODEL} (QuickNote)"
        return run(ctx, "sync-setup", "--vault", id, "--path", path, "--device-name", device, "--json",
            *(if (password.isNotEmpty()) arrayOf("--password", password) else arrayOf()))
    }

    fun unlink(ctx: Context, path: String) = run(ctx, "sync-unlink", "--path", path)

    /** Attachment types Sync can carry, as Headless names them, and as Obsidian's Sync settings word them. */
    val FILE_TYPES = listOf(
        "image" to "Images",
        "audio" to "Audio",
        "video" to "Videos",
        "pdf" to "PDFs",
        "unsupported" to "All other types",
    )

    /** Parts of the vault's settings folder Sync can carry, worded as in Obsidian's Sync settings. */
    val CONFIGS = listOf(
        "app" to "Main settings",
        "appearance" to "Appearance settings",
        "appearance-data" to "Themes and snippets",
        "hotkey" to "Hotkeys",
        "core-plugin" to "Active core plugin list",
        "core-plugin-data" to "Core plugin settings",
        "community-plugin" to "Active community plugin list",
        "community-plugin-data" to "Installed community plugins",
    )

    /** How the vault syncs: conflicts ("merge" or "conflict"), attachment types, settings, folders left out. */
    data class Config(val conflict: String, val fileTypes: Set<String>, val configs: Set<String>, val excluded: List<String>)

    /** Everything on, as stock Sync with all its switches on: what a vault starts with when connected. */
    val EVERYTHING = Config("merge", FILE_TYPES.map { it.first }.toSet(), CONFIGS.map { it.first }.toSet(), emptyList())

    fun readConfig(ctx: Context, path: String): Config? {
        val r = run(ctx, "sync-config", "--path", path, "--json")
        val json = runCatching { JSONObject(r.output.substring(r.output.indexOf('{'))) }.getOrNull() ?: return null
        fun list(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Config(json.optString("conflictStrategy", "merge").ifEmpty { "merge" }, list("fileTypes").toSet(), list("configs").toSet(), list("excludedFolders"))
    }

    fun writeConfig(ctx: Context, path: String, c: Config): Result = run(
        ctx, "sync-config", "--path", path,
        "--conflict-strategy", c.conflict,
        "--file-types", FILE_TYPES.map { it.first }.filter { it in c.fileTypes }.joinToString(","),
        "--configs", CONFIGS.map { it.first }.filter { it in c.configs }.joinToString(","),
        "--excluded-folders", c.excluded.joinToString(","),
    )

    // What's set up, kept by the app: the folder synced and whether sync runs.
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("headless", Context.MODE_PRIVATE)
    fun syncedPath(ctx: Context): String? = prefs(ctx).getString("path", null)
    fun syncedVault(ctx: Context): String? = prefs(ctx).getString("vault", null)
    fun setSynced(ctx: Context, path: String?, vault: String?) = prefs(ctx).edit().putString("path", path).putString("vault", vault).apply()
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    /** The last lines Headless printed while syncing in the background. */
    fun log(ctx: Context) = File(home(ctx), "sync.log")
}
