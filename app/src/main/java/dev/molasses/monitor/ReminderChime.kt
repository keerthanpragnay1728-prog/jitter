package dev.molasses.monitor

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.molasses.R
import dev.molasses.core.remind.ChimeWait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sound a reminder makes, and the whole of how it announces itself.
 *
 * No notification, so no POST_NOTIFICATIONS grant: the text waits on the
 * console instead. The chime follows the ringer. Silent plays nothing,
 * vibrate vibrates once, normal plays the device's default notification
 * sound. A chime the user did not hear means the text is seen only on the
 * next visit to the launcher, and the README says so.
 *
 * ## A bundled chime, with the system sound behind it
 * The chime is `res/raw/jitter_chime.wav`, made by `tools/gen-chime.py`
 * (mono, 16-bit, 44.1 kHz, 300 ms, C5 with a fifth and an octave under it,
 * each decaying on its own clock, peak -12 dBFS),
 * played through MediaPlayer with `USAGE_NOTIFICATION_EVENT`: a file the
 * media stack decodes like any other notification sound, rather than a
 * ToneGenerator beep or a raw AudioTrack buffer, both of which were silent
 * on hardware for reasons never pinned down. If MediaPlayer fails at any
 * step, the default notification sound through `Ringtone` plays instead.
 *
 * ## Awaited, and capped
 * The receiver holds its PendingResult until [play] returns. The raw path
 * returns on onCompletion or at [ChimeWait.CAP_MS]; the fallback polls
 * `isPlaying` under the same cap. Each player is stopped and released in a
 * `finally`, so every error path ends it.
 *
 * ## On a device
 * `adb logcat -s Molasses.Reminder`. Every play logs the ringer mode; the
 * sound path then logs the notification volume, mute flag and interruption
 * filter, then "chime: path=raw prepared in Nms" and "completed" or "cap
 * reached", or why it fell back and the fallback's own lines.
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
        if (playRaw(context)) return
        Log.i(TAG, "chime: path=fallback (ringtone)")
        playRingtone(context)
    }

    /** The bundled chime. False when MediaPlayer failed anywhere, so the caller falls back. */
    private suspend fun playRaw(context: Context): Boolean {
        val player = MediaPlayer()
        val started = SystemClock.elapsedRealtime()
        try {
            player.setAudioAttributes(notificationEvent())
            context.resources.openRawResourceFd(R.raw.jitter_chime).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
            val prepareMs = SystemClock.elapsedRealtime() - started
            // True on completion, false on a playback error.
            val finished = CompletableDeferred<Boolean>()
            player.setOnCompletionListener { finished.complete(true) }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "chime: raw playback error what=$what extra=$extra")
                finished.complete(false)
                true
            }
            player.start()
            Log.i(TAG, "chime: path=raw prepared in ${prepareMs}ms, started")
            val outcome = withTimeoutOrNull(ChimeWait.CAP_MS) { finished.await() }
            val elapsed = SystemClock.elapsedRealtime() - started
            return when (outcome) {
                true -> {
                    Log.i(TAG, "chime: path=raw completed after ${elapsed}ms")
                    true
                }
                null -> {
                    Log.i(TAG, "chime: path=raw cap reached after ${elapsed}ms, stopped")
                    true
                }
                false -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "chime: raw path failed", e)
            return false
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    private fun notificationEvent(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** The fallback: the device's default notification sound. */
    private suspend fun playRingtone(context: Context) {
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
            ringtone.audioAttributes = notificationEvent()
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
