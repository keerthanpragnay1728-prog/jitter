package dev.molasses.monitor

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The sound a reminder makes, and the whole of how it announces itself.
 *
 * No notification, so no POST_NOTIFICATIONS grant: the text waits on the
 * console instead. The tone follows the ringer. Silent plays nothing, vibrate
 * vibrates, normal plays a short beep on the notification stream at that
 * stream's volume. A tone the user did not hear means the text is seen only
 * on the next visit to the launcher, and the README says so.
 */
object ReminderChime {

    private const val TAG = "Molasses.Reminder"
    private const val TONE_MS = 300
    private const val VIBRATE_MS = 300L

    fun play(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)
            else -> tone()
        }
    }

    private fun tone() {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MS)
            // Released after the tone, not before: releasing ends it.
            Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, TONE_MS + 200L)
        }.onFailure { Log.w(TAG, "reminder tone failed", it) }
    }

    private fun vibrate(context: Context) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "reminder vibration failed", it) }
    }
}
