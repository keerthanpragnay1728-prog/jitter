package dev.molasses.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Silence whatever is playing while one of our full-screen windows is up,
 * for the windows that silence at all. Which ones is `OverlayAudio`: not the
 * entry gate and not a lock screen raised at entry.
 *
 * ## Why a countdown over continuing audio is not a gate
 * The lease gate can run for thirty seconds. Held over a feed that keeps
 * playing, it stops being an interruption and becomes a delay: the audio is
 * the session, and audio that is still running tells the user the session is
 * still running and they are merely being made to wait for it. Silence is what
 * makes the gate an interruption rather than a toll booth with the engine
 * still ticking over.
 *
 * ## A transient gain, and not the ducking variant
 * Ducking leaves the audio audible at a lower volume, which for thirty seconds
 * is worse than silence in the exact way that matters: it says the same thing
 * about the session still running, more quietly. It also leaves a countdown
 * competing with speech, and the countdown has to be readable in three
 * seconds.
 *
 * The ducking constant is deliberately not spelled out anywhere in this file.
 * `AudioFocusWiringTest` greps for its distinguishing suffix, because the two
 * constants share a prefix and a prefix check would pass for either, and a
 * doc that named the one being rejected would trip the check the same way an
 * earlier version of `check-encoding.sh` flagged itself.
 *
 * TRANSIENT rather than a permanent gain because the claim ends when the
 * window does. A permanent gain asks the other app to stop rather than pause,
 * and a user who takes a lease should get their audio back.
 *
 * ## It does not interact with [CallDetector]
 * That reads `AudioManager.getMode()`, which is set by the telephony and VoIP
 * stacks. Focus is a different subsystem: taking it cannot make the detector
 * report a call and cannot make it stop reporting one. The two are checked
 * against each other rather than assumed independent because a panic path that
 * a cosmetic change can disable is the failure this app keeps finding.
 *
 * A call takes focus for itself when it arrives, so by the time the detector
 * notices, the OS has already moved it. [release] is then giving up a claim we
 * no longer hold, which is why it must be safe to call at any time and from
 * any state.
 *
 * ## Both calls are idempotent and neither can fail a window
 * A refused focus request is logged and otherwise ignored. The gate's job is
 * to be on the glass; silence is what it would like as well, and a device that
 * declines is not a reason to leave the user unfrictioned.
 *
 * ## One class rather than three copies
 * The same reason [CallDetector] is a class. The lease gate, the walking gate
 * and the lock overlay all need this, and the lock overlay needs it most: it
 * lives until the user acts, over an app they may not use at all. Copies of a
 * lifecycle-sensitive claim are separate behaviours as soon as one is edited,
 * and the failure mode of the edited-away copy is a silent leak of the
 * device's audio focus. The walking gate was the copy that was missing: it is
 * full screen and never took focus at all, so media played on under it.
 *
 * ## Focus alone does not stop every player, so [take] can also send pause
 * Focus is a request. A player that ignores a transient loss keeps playing,
 * and more focus calls cannot change that. So [take] can also dispatch
 * `KEYCODE_MEDIA_PAUSE`, down then up, through
 * `AudioManager.dispatchMediaKeyEvent`. That needs no permission and reaches
 * whichever app holds the active media session, the same route a headset
 * button takes.
 *
 * Whichever app, which is why neither half is used everywhere. At entry the
 * active session is usually background music the user was already playing:
 * the key left it paused for good after a lease, and the focus request alone
 * still paused some players and did not reliably give them back. So the
 * caller passes `OverlayAudio.silences` for its overlay kind, and one answer
 * decides both the focus request and the key. See `OverlayAudio`.
 *
 * PAUSE, never PLAY_PAUSE. The toggle would start music that was already
 * stopped, which is the opposite of the point. And nothing is sent on
 * [release]: we interrupted, we do not resume. That also matters for the
 * focus half. Abandoning a transient claim hands focus back, and a player
 * that paused only for the focus loss may resume on getting it back. A
 * player that received the pause key treats it as the user pausing, and
 * stays paused.
 *
 * ## On a device
 * `adb logcat -s Molasses.AudioFocus`. A window at entry logs "audio focus
 * not taken and media pause not sent for ..." and nothing else. Any other
 * window that goes up logs "audio focus taken for ..." or "audio focus
 * refused for ... (result=N)", then "media pause dispatched for ...". Each
 * that held focus logs "audio focus released for ..." as it comes down. Audio
 * that stops at entry after this change is not ours: no line at all is
 * logged for it. Audio still playing after "taken" and
 * "media pause dispatched" is a player ignoring both focus and its media
 * session, which is the case only muting reaches.
 */
class AudioFocusHold(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * The live request, or null when nothing is held.
     *
     * Kept rather than rebuilt, because `abandonAudioFocusRequest` matches on
     * the instance. A rebuilt request abandons nothing and leaves the device
     * muted with no window on screen, which is the worst outcome available
     * here and is silent in every sense.
     */
    private var held: AudioFocusRequest? = null

    /**
     * Take focus. Idempotent: a second call while held does nothing.
     *
     * @param silence take focus and send the media pause key, both or
     *   neither. No default: every caller names its overlay kind through
     *   `OverlayAudio.silences`.
     */
    fun take(reason: String, silence: Boolean) {
        val am = audio ?: return
        if (!silence) {
            // At entry. Audio from the target app playing behind the window
            // is not silenced; see OverlayAudio for why that is accepted.
            Log.i(TAG, "audio focus not taken and media pause not sent for $reason")
            return
        }
        if (held != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        val result = runCatching { am.requestAudioFocus(request) }.getOrNull()
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            held = request
            // Logged on the success path too, not only on refusal.
            //
            // A window that goes up and comes down faster than a person can
            // see leaves no trace anywhere else: nothing in this path writes
            // to the ledger, and a clean-looking run is then indistinguishable
            // from one that happened and recovered. These two lines are the
            // only thing that would tell those apart in logcat.
            Log.i(TAG, "audio focus taken for $reason")
        } else {
            // Not a failure of the window. See the class doc.
            Log.w(TAG, "audio focus refused for $reason (result=$result)")
        }
        // Whatever focus answered. A refusal usually means something else
        // holds focus and may still be playing, which is exactly when the
        // pause is needed.
        pausePlayback(am, reason)
    }

    /**
     * Pause whatever has the active media session. PAUSE and not the toggle,
     * and never paired with a play later. See the class doc.
     */
    private fun pausePlayback(am: AudioManager, reason: String) {
        val now = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }
            .onSuccess { Log.i(TAG, "media pause dispatched for $reason") }
            .onFailure { Log.w(TAG, "media pause dispatch threw for $reason", it) }
    }

    /** Give it back. Idempotent, and safe when it was never taken. */
    fun release(reason: String) {
        val am = audio ?: return
        val request = held ?: return
        held = null
        Log.i(TAG, "audio focus released for $reason")
        runCatching { am.abandonAudioFocusRequest(request) }
            .onFailure { Log.w(TAG, "abandoning audio focus threw for $reason", it) }
    }

    private companion object { const val TAG = "Molasses.AudioFocus" }
}
