package dev.molasses.core.safety

/**
 * Which full-screen overlays send the target app home under themselves.
 * Pure.
 *
 * ## Why home, and why nothing else
 * On hardware a Reel played on under the LEASE EXPIRED gate. Instagram
 * plays `USAGE_MEDIA` on the music stream, took audio focus back 0.6 s after
 * the gate took it, started new players under the gate, and ignored the
 * pause key; a music-stream mute was overwritten within seconds by the
 * Bluetooth absolute-volume sync. An overlay does not pause the Activity
 * under it, so every audio lever lost to an app that is still resumed.
 *
 * Sending it home does not argue with the app. The target goes to the
 * background and stops its own playback, the overlay stays up over the
 * launcher, and the countdown carries on there. Focus, the pause key and
 * the mute are gone.
 *
 * ## Which ones
 * All five. The entry gate and a lock raised as the app opens were left out
 * once, on the reasoning that at entry nothing of the target's has started
 * yet. On device that was wrong: Instagram's first Reel autoplays with sound
 * behind the entry gate, because the app under an overlay is resumed and
 * starts its own playback. Taking no audio focus at entry did not stop that
 * and was never going to, so autoplay behind the entry gate is now stopped
 * by going home, the same as everywhere else, and not by focus.
 *
 * Nothing here touches audio, so background music from an app that is not
 * behind the overlay plays on untouched, at entry and mid-session alike.
 *
 * What home does not stop is playback the target keeps alive outside its
 * own activity: YouTube's picture-in-picture window, and Premium background
 * play. Neither is visible to this service's profile. See the README's known
 * limits.
 *
 * The `when` stays exhaustive although every branch answers true, so a new
 * overlay kind has to be decided here rather than inherit an answer.
 */
object HomeFirst {

    enum class Overlay {
        /** The lease gate on entering an app with no lease run out this cycle. */
        ENTRY_GATE,

        /** The lease gate after a lease ran out: LEASE EXPIRED. */
        EXPIRED_GATE,

        /** The movement or typing gate. */
        WALK_GATE,

        /** The lock screen raised as the locked app opens. */
        LOCK_AT_ENTRY,

        /** The lock screen raised over a session already running. */
        LOCK_MID_SESSION,
    }

    fun sendsHome(overlay: Overlay): Boolean = when (overlay) {
        Overlay.ENTRY_GATE,
        Overlay.EXPIRED_GATE,
        Overlay.WALK_GATE,
        Overlay.LOCK_AT_ENTRY,
        Overlay.LOCK_MID_SESSION,
        -> true
    }

    /** The lease gate's kind, from the flag it is shown with. */
    fun leaseGate(expired: Boolean): Overlay = if (expired) Overlay.EXPIRED_GATE else Overlay.ENTRY_GATE

    /** The lock screen's kind, from whether it was raised on entry. */
    fun lock(atEntry: Boolean): Overlay = if (atEntry) Overlay.LOCK_AT_ENTRY else Overlay.LOCK_MID_SESSION
}
