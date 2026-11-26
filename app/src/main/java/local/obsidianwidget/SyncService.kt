package local.obsidianwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps Obsidian Headless syncing the vault in the background ("ob sync --continuous"), with the
 * notification Android needs for that. If it stops (no network, a crash), it's started again after
 * a wait that grows to five minutes. What it prints goes to the end of a log the app shows.
 */
class SyncService : Service() {
    companion object {
        private const val CHANNEL = "sync"
        private const val ID = 1
        private const val LOG_LINES = 300
        /** Stands for the widget's Task Cleaner inbox among the files watched. */
        private const val INBOX = "\u0000inbox"

        fun start(ctx: Context) {
            val intent = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }

        /** Starts sync again, if it's on, so it picks up changed settings. */
        fun restart(ctx: Context) {
            if (!HeadlessSync.enabled(ctx)) return
            stop(ctx)
            start(ctx)
        }

        /** Whether the service is syncing now, for the settings screen. */
        @Volatile
        var running = false
            private set
    }

    @Volatile
    private var stopping = false
    private var process: Process? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = HeadlessSync.syncedPath(this)
        if (path == null || !HeadlessSync.enabled(this) || !HeadlessSync.installed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(HeadlessSync.syncedVault(this) ?: File(path).name)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        if (worker?.isAlive != true) {
            stopping = false
            worker = thread(name = "headless-sync") { loop(path) }
        }
        watchNotes()
        return START_STICKY
    }

    private fun loop(path: String) {
        running = true
        var wait = 10_000L
        while (!stopping) {
            val started = System.currentTimeMillis()
            log("Starting sync")
            runCatching {
                val p = HeadlessSync.command(this, "sync", "--continuous", "--path", path).start()
                process = p
                p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { log(it) }
                log("Sync stopped (exit ${p.waitFor()})")
            }.onFailure { log("Couldn't run sync: ${it.message}") }
            process = null
            if (stopping) break
            // A run that lasted a while was fine until something changed: start again soon.
            wait = if (System.currentTimeMillis() - started > 10 * 60_000L) 10_000L else minOf(wait * 2, 5 * 60_000L)
            runCatching { Thread.sleep(wait) }
        }
        running = false
    }

    private val logLock = Any()

    private fun log(line: String) = synchronized(logLock) {
        val file = HeadlessSync.log(this)
        val stamp = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (if (file.exists()) file.readLines() else emptyList()) + "$stamp  $line"
        file.writeText(lines.takeLast(LOG_LINES).joinToString("\n") + "\n")
    }

    /*
     * Widgets redraw as soon as sync rewrites their note. The widget's own Live check goes by
     * Android's index of files, which doesn't hear of files written straight to storage the way
     * Headless writes them; watching the notes' folders does, and this service keeps the app
     * running to watch. The list of notes is read again every minute, for widgets added or
     * switched to another note.
     */
    private val watchers = HashMap<String, android.os.FileObserver>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rewatch = object : Runnable {
        override fun run() {
            watchNotes()
            handler.postDelayed(this, 60_000)
        }
    }

    /** Takes the widget's inbox into Task Cleaner's settings a second after it last changed. */
    private val takeIn = Runnable {
        val vault = HeadlessSync.syncedPath(this) ?: return@Runnable
        thread { runCatching { TaskCleaner.takeInInbox(vault) } }
    }

    private fun takeInSoon() {
        handler.removeCallbacks(takeIn)
        handler.postDelayed(takeIn, 1000)
    }

    private fun watchNotes() {
        handler.removeCallbacks(rewatch)
        if (stopping) return
        // Each folder holding a widget's note, and the notes in it (file name to note).
        val wanted = HashMap<String, HashMap<String, String>>()
        for (id in NoteWidgetProvider.allIds(this)) {
            val cfg = WidgetStore.load(this, id) ?: continue
            val root = HeadlessSync.vaultPath(cfg.vaultTree) ?: continue
            val notes = (cfg.swapNotes.map { it.uri to it.path } + (cfg.noteUri to cfg.notePath)).distinct()
            for ((uri, rel) in notes) {
                val file = File(root, rel)
                wanted.getOrPut(file.parent ?: continue) { HashMap() }[file.name] = uri
            }
        }
        // Task Cleaner's folder too, for the widget's inbox of hidden tasks (see TaskCleaner.takeInInbox).
        val vault = HeadlessSync.syncedPath(this)
        val cleaner = vault?.let { File(it, ".obsidian/plugins/task-cleaner").path }
        if (cleaner != null && File(cleaner).isDirectory) wanted.getOrPut(cleaner) { HashMap() }["widget-inbox.json"] = INBOX
        if (vault != null) thread { runCatching { TaskCleaner.takeInInbox(vault) } }
        for (dir in watchers.keys - wanted.keys) watchers.remove(dir)?.stopWatching()
        for ((dir, files) in wanted) {
            watchers.remove(dir)?.stopWatching()
            @Suppress("DEPRECATION")
            val watcher = object : android.os.FileObserver(dir, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, name: String?) {
                    val uri = files[name ?: return] ?: return
                    if (uri == INBOX) takeInSoon() else NoteWidgetProvider.refreshNote(this@SyncService, uri)
                }
            }
            watcher.startWatching()
            watchers[dir] = watcher
        }
        handler.postDelayed(rewatch, 60_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(rewatch)
        handler.removeCallbacks(takeIn)
        watchers.values.forEach { it.stopWatching() }
        watchers.clear()
        stopping = true
        process?.destroy()
        worker?.interrupt()
        running = false
        super.onDestroy()
    }

    private fun notification(vault: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Obsidian Sync runs in the background"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, SyncActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Syncing $vault")
            .setContentText("Obsidian Sync, in the background")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

/** Starts background sync again after the phone restarts, if it's on. */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && HeadlessSync.enabled(ctx)) SyncService.start(ctx)
    }
}
