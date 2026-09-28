package dev.molasses.core.remind

/**
 * How long the receiver waits on the reminder chime. Pure.
 *
 * The chime is the system's default notification sound, which is any length
 * the user or the device maker chose, and `Ringtone.isPlaying` is the only
 * report of when it ends. So the receiver polls it, and this decides when to
 * stop polling:
 *
 * - never past [CAP_MS], however long the sound is, because the receiver's
 *   PendingResult is held for the whole wait and a long sound is cut rather
 *   than allowed to hold it;
 * - while the sound is playing;
 * - and for up to [START_GRACE_MS] before it has ever been seen playing,
 *   because `isPlaying` can read false for a moment after `play()`. A sound
 *   that never starts ends the wait at the grace, not at the cap.
 */
object ChimeWait {

    const val CAP_MS = 3_000L
    const val START_GRACE_MS = 500L
    const val POLL_MS = 50L

    fun keepWaiting(elapsedMs: Long, playing: Boolean, everPlayed: Boolean): Boolean = when {
        elapsedMs >= CAP_MS -> false
        playing -> true
        everPlayed -> false
        else -> elapsedMs < START_GRACE_MS
    }
}
