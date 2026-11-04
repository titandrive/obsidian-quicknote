package local.obsidianwidget

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** The light tick Paid to Poop and PurgeBot give taps, so all the apps feel the same. */
object Haptics {
    fun tick(ctx: Context) {
        val vibrator = vibrator(ctx) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else vibrator.vibrate(VibrationEffect.createOneShot(10, 60))
        }
    }

    /**
     * The tick for a tap on the widget, which reaches the app while it's still in the background.
     * Android drops ordinary touch haptics from background apps (or not, depending on how quickly it
     * notices the app came forward), but plays ones marked as emulating a physical button. Skipped if
     * touch vibration is turned off in system settings, as the touch kind would be.
     */
    fun widgetTick(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return tick(ctx)
        if (Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val vibrator = vibrator(ctx) ?: return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION),
            )
        }
    }

    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
