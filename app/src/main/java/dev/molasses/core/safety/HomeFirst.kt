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
 * The overlays raised over a session already in progress: the LEASE EXPIRED
 * gate, the walking gate, and a lock shown mid-session. Not the entry gate
 * and not a lock raised as the app opens: at entry nothing of the target's
 * has started yet, and those two are unchanged.
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
        Overlay.EXPIRED_GATE, Overlay.WALK_GATE, Overlay.LOCK_MID_SESSION -> true
        Overlay.ENTRY_GATE, Overlay.LOCK_AT_ENTRY -> false
    }

    /** The lease gate's kind, from the flag it is shown with. */
    fun leaseGate(expired: Boolean): Overlay = if (expired) Overlay.EXPIRED_GATE else Overlay.ENTRY_GATE

    /** The lock screen's kind, from whether it was raised on entry. */
    fun lock(atEntry: Boolean): Overlay = if (atEntry) Overlay.LOCK_AT_ENTRY else Overlay.LOCK_MID_SESSION
}
