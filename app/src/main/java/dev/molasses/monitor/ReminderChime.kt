package dev.molasses.monitor

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.molasses.core.remind.ChimeWave
import kotlinx.coroutines.delay

/**
 * The sound a reminder makes, and the whole of how it announces itself.
 *
 * No notification, so no POST_NOTIFICATIONS grant: the text waits on the
 * console instead. The chime follows the ringer. Silent plays nothing,
 * vibrate vibrates once, normal plays one short, soft chime. A chime the
 * user did not hear means the text is seen only on the next visit to the
 * launcher, and the README says so.
 *
 * ## An AudioTrack of generated PCM, not ToneGenerator
 * The ToneGenerator beep produced no sound on hardware while the receiver
 * demonstrably ran. See [ChimeWave] for the likely cause and the shape that
 * replaces it: 120 ms of silence for the output to wake into, then a 280 ms
 * sine chime at 0.35 of full scale.
 *
 * Played as `USAGE_NOTIFICATION_EVENT`, so it takes the notification
 * volume, is routed like any notification, and is silenced by Do Not
 * Disturb the way a notification would be. That last one is deliberate and
 * is why the interruption filter is logged: a chime DND swallowed is not a
 * bug.
 *
 * ## Awaited
 * [play] suspends for [TOTAL_MS], the buffer plus [TAIL_MS], before the
 * track is released, so the receiver finishes its PendingResult only after
 * the sound. Releasing earlier ends it, and finishing earlier lets the
 * system reclaim the process with the sound still queued. 500 ms plus a
 * DataStore read and write is far inside the 10 s a receiver has after
 * goAsync. rescheduleAll sounds it once however many reminders it fires
 * late.
 *
 * ## On a device
 * `adb logcat -s Molasses.Reminder`. Every play logs the ringer mode, then
 * on the tone path the notification stream's volume, its mute flag and the
 * interruption filter, then either "chime playing" or why not.
 */
object ReminderChime {

    private const val TAG = "Molasses.Reminder"
    const val TAIL_MS = 100L
    const val TOTAL_MS = ChimeWave.TOTAL_MS + TAIL_MS

    /** One vibration, the length of the audible part. */
    const val VIBRATE_MS = ChimeWave.TONE_MS.toLong()

    suspend fun play(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        if (am == null) {
            Log.w(TAG, "chime: no AudioManager, nothing played")
            return
        }
        Log.i(TAG, "chime: play reached, ringerMode=${am.ringerMode}")
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)
            else -> tone(context, am)
        }
    }

    private suspend fun tone(context: Context, am: AudioManager) {
        val stream = AudioManager.STREAM_NOTIFICATION
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        Log.i(
            TAG,
            "chime: notification volume=${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)} " +
                "muted=${am.isStreamMute(stream)} interruptionFilter=$filter",
        )
        val pcm = ChimeWave.samples()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ChimeWave.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.onFailure { Log.w(TAG, "chime: AudioTrack could not be built", it) }.getOrNull() ?: return
        try {
            val written = track.write(pcm, 0, pcm.size)
            if (written != pcm.size) {
                Log.w(TAG, "chime: wrote $written of ${pcm.size} samples, not played")
                return
            }
            track.play()
            Log.i(TAG, "chime playing: ${ChimeWave.TOTAL_MS} ms, peak=${ChimeWave.peak(pcm)}, playState=${track.playState}")
            // Released after the sound, not before: releasing ends it.
            delay(TOTAL_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "chime failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
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
            vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(TAG, "chime: vibrated ${VIBRATE_MS} ms (vibrator=${vibrator != null})")
        }.onFailure { Log.w(TAG, "reminder vibration failed", it) }
        delay(TOTAL_MS)
    }
}
