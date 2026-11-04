package local.obsidianwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import kotlin.concurrent.thread

/** Debug aid: logs, for "seconds" (default 40), every change Android announces in its index of shared files. */
class IndexProbe : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        val handlerThread = HandlerThread("index-probe").apply { start() }
        val observer = object : ContentObserver(Handler(handlerThread.looper)) {
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                Log.d("OWINDEX", "change flags=$flags uris=$uris")
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        Log.d("OWINDEX", "watching")
        thread {
            Thread.sleep(intent.getIntExtra("seconds", 40) * 1000L)
            ctx.contentResolver.unregisterContentObserver(observer)
            handlerThread.quitSafely()
            Log.d("OWINDEX", "stopped")
            result.finish()
        }
    }
}
