package dev.molasses.monitor

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.molasses.core.remind.ChimeWait
import kotlinx.coroutines.delay

/**
 * The sound a reminder makes, and the whole of how it announces itself.
 *
 * No notification, so no POST_NOTIFICATIONS grant: the text waits on the
 * console instead. The chime follows the ringer. Silent plays nothing,
 * vibrate vibrates once, normal plays the device's default notification
 * sound. A chime the user did not hear means the text is seen only on the
 * next visit to the launcher, and the README says so.
 *
 * ## The system's notification sound, not a sound of our own
 * Two sounds of our own were silent on hardware. A ToneGenerator beep, and
 * then a generated AudioTrack buffer that logged playState=3 at notification
 * volume 7/7, unmuted, with the interruption filter at ALL, and still made no
 * sound. Why is not known. The default notification sound, played through
 * `Ringtone` with `USAGE_NOTIFICATION_EVENT`, is the path every notification
 * on the device already takes, so it is the one most likely to be heard.
 * It is also the user's own choice of sound, which is right for something
 * they asked to be told.
 *
 * ## Awaited, and capped
 * The receiver holds its PendingResult until [play] returns, and [play]
 * returns when the sound has ended, polled through `isPlaying`, or at
 * [ChimeWait.CAP_MS], whichever is first. A sound still playing at the cap is
 * stopped. The sound is stopped in a `finally`, so every error path ends it.
 *
 * ## On a device
 * `adb logcat -s Molasses.Reminder`. Every play logs the ringer mode; the
 * sound path then logs the notification volume, mute flag and interruption
 * filter, the ringtone URI, whether play() started, and how long it waited.
 */
object ReminderChime {

    private const val TAG = "Molasses.Reminder"

    /** One vibration. */
    const val VIBRATE_MS = 300L

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
            else -> sound(context, am)
        }
    }

    private suspend fun sound(context: Context, am: AudioManager) {
        val stream = AudioManager.STREAM_NOTIFICATION
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        Log.i(
            TAG,
            "chime: notification volume=${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)} " +
                "muted=${am.isStreamMute(stream)} interruptionFilter=$filter",
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        Log.i(TAG, "chime: ringtone uri=$uri")
        if (uri == null) {
            Log.w(TAG, "chime: no default notification sound, nothing played")
            return
        }
        val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }
            .onFailure { Log.w(TAG, "chime: could not load the ringtone", it) }
            .getOrNull()
        if (ringtone == null) {
            Log.w(TAG, "chime: no ringtone for $uri, nothing played")
            return
        }
        val started = SystemClock.elapsedRealtime()
        try {
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            Log.i(TAG, "chime: play() called, isPlaying=${ringtone.isPlaying}")
            var everPlayed = false
            while (true) {
                val playing = ringtone.isPlaying
                if (playing) everPlayed = true
                val elapsed = SystemClock.elapsedRealtime() - started
                if (!ChimeWait.keepWaiting(elapsed, playing, everPlayed)) break
                delay(ChimeWait.POLL_MS)
            }
            Log.i(
                TAG,
                "chime: wait ended after ${SystemClock.elapsedRealtime() - started}ms, " +
                    "everPlayed=$everPlayed stillPlaying=${ringtone.isPlaying}",
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "chime failed", e)
        } finally {
            // Ends a sound still going at the cap, and one an error left running.
            runCatching { ringtone.stop() }
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
        delay(VIBRATE_MS)
    }
}
