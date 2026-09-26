package dev.molasses.core.safety

/**
 * Which full-screen overlays also send the media pause key. Pure.
 *
 * ## Why not all of them
 * `AudioManager.dispatchMediaKeyEvent` reaches whichever app holds the active
 * media session, not the app under the overlay. On the entry gate that is
 * usually a player in the background: music the user was listening to before
 * opening the target app. Pausing it pauses the wrong thing, and since nothing
 * ever sends play (we interrupt, we do not resume), it stayed paused after the
 * lease was taken. Found on hardware.
 *
 * So the key goes only where the overlay comes up over a session already in
 * progress, where the playing session is most likely the target app's own:
 * the LEASE EXPIRED gate, the walking gate, and a lock screen raised
 * mid-session (the scroll backstop, or a block chosen on the expired gate).
 * Never the entry gate, and never a lock screen raised as the app opens.
 *
 * Audio focus is unaffected: every full-screen overlay still takes it. This
 * decides the key and nothing else.
 */
object MediaPause {

    enum class Overlay {
        /** The lease gate on entering an app with no lease run out this cycle. */
        ENTRY_GATE,

        /** The lease gate after a lease ran out: LEASE EXPIRED. */
        EXPIRED_GATE,

        /** The movement or typing gate at the terminal tier. */
        WALK_GATE,

        /** The lock screen raised as the locked app opens. */
        LOCK_AT_ENTRY,

        /** The lock screen raised over a session already running. */
        LOCK_MID_SESSION,
    }

    fun sendsPause(overlay: Overlay): Boolean = when (overlay) {
        Overlay.EXPIRED_GATE, Overlay.WALK_GATE, Overlay.LOCK_MID_SESSION -> true
        Overlay.ENTRY_GATE, Overlay.LOCK_AT_ENTRY -> false
    }

    /** The lease gate's kind, from the flag it is shown with. */
    fun leaseGate(expired: Boolean): Overlay = if (expired) Overlay.EXPIRED_GATE else Overlay.ENTRY_GATE

    /** The lock screen's kind, from whether it was raised on entry. */
    fun lock(atEntry: Boolean): Overlay = if (atEntry) Overlay.LOCK_AT_ENTRY else Overlay.LOCK_MID_SESSION
}
