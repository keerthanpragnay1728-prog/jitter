package dev.molasses.monitor

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * The sound a reminder makes, and the whole of how it announces itself.
 *
 * No notification, so no POST_NOTIFICATIONS grant: the text waits on the
 * console instead. The tone follows the ringer. Silent plays nothing,
 * vibrate vibrates once, normal plays one short, soft beep on the
 * notification stream. A tone the user did not hear means the text is seen
 * only on the next visit to the launcher, and the README says so.
 *
 * ## One tone, soft, and awaited
 * `TONE_PROP_BEEP` at [VOLUME] of the stream's volume, capped at [TONE_MS].
 * Not `TONE_PROP_BEEP2`: that tone is itself two beeps, so the earlier
 * double play was four, and on hardware it was abrasive. [play] suspends for
 * [TOTAL_MS], the tone's cap plus [TAIL_MS], before the generator is
 * released, so the receiver finishes its PendingResult only after the tone.
 * Releasing earlier ends it, and finishing earlier lets the system reclaim
 * the process with the tone still queued. 250 ms plus a DataStore read and
 * write is far inside the 10 s a receiver has after goAsync. rescheduleAll
 * sounds it once however many reminders it fires late.
 */
object ReminderChime {

    private const val TAG = "Molasses.Reminder"
    const val TONE_MS = 150L
    const val TAIL_MS = 100L
    const val TOTAL_MS = TONE_MS + TAIL_MS

    /** Of ToneGenerator's 0 to 100, relative to the notification stream. Half, for soft. */
    const val VOLUME = 50

    suspend fun play(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)
            else -> tone()
        }
    }

    private suspend fun tone() {
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
        }.onFailure { Log.w(TAG, "reminder tone failed", it) }.getOrNull() ?: return
        try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS.toInt())
            // Released after the tone, not before: releasing ends it.
            delay(TOTAL_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "reminder tone failed", e)
        } finally {
            generator.release()
        }
    }

    private suspend fun vibrate(context: Context) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(TONE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "reminder vibration failed", it) }
        delay(TOTAL_MS)
    }
}
