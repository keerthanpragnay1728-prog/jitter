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
 * console instead. The tone follows the ringer. Silent plays nothing, vibrate
 * vibrates twice, normal plays two short beeps on the notification stream at
 * that stream's volume. A tone the user did not hear means the text is seen
 * only on the next visit to the launcher, and the README says so.
 *
 * ## Twice, and awaited
 * Two pulses of [PULSE_MS] with [GAP_MS] between them, then [TAIL_MS] before
 * the tone generator is released: [TOTAL_MS], 900 ms. [play] suspends for
 * all of it, so the receiver finishes its PendingResult only after the
 * second pulse. Finishing earlier lets the system reclaim the process with
 * the tone still queued. 900 ms plus a DataStore read and write is far
 * inside the 10 s a receiver has after goAsync, the shortest window any
 * broadcast gets. rescheduleAll sounds it once however many reminders it
 * fires late, so a boot with a backlog does not stack it.
 */
object ReminderChime {

    private const val TAG = "Molasses.Reminder"
    const val PULSE_MS = 300L
    const val GAP_MS = 200L
    const val TAIL_MS = 100L
    const val TOTAL_MS = PULSE_MS + GAP_MS + PULSE_MS + TAIL_MS

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
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
        }.onFailure { Log.w(TAG, "reminder tone failed", it) }.getOrNull() ?: return
        try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, PULSE_MS.toInt())
            delay(PULSE_MS + GAP_MS)
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, PULSE_MS.toInt())
            // Released after the second pulse, not before: releasing ends it.
            delay(PULSE_MS + TAIL_MS)
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
            // Off, on, off, on: two pulses, no repeat.
            val pattern = longArrayOf(0L, PULSE_MS, GAP_MS, PULSE_MS)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Log.w(TAG, "reminder vibration failed", it) }
        delay(TOTAL_MS)
    }
}
