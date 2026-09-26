package dev.molasses.core.safety

/**
 * Which full-screen overlays silence audio: take transient audio focus and
 * send the media pause key. One decision for both, so they cannot drift
 * apart. Pure.
 *
 * ## Only over a session already in progress
 * The LEASE EXPIRED gate, the walking gate, and a lock screen raised
 * mid-session (the scroll backstop, or a block chosen on the expired gate).
 * There the playing audio is most likely the target app's own, and a
 * countdown over it is a delay rather than an interruption.
 *
 * ## Not at entry, and what that costs
 * Neither the entry gate nor a lock screen raised as the app opens takes
 * focus or sends the key. At entry, audio from the target app playing behind
 * the gate is not silenced. This is accepted, because the alternative
 * interrupted the user's own background audio and did not reliably give it
 * back.
 *
 * The history, both halves found on hardware: the pause key reaches whichever
 * app holds the active media session, which at entry is usually music the
 * user was already playing, and nothing ever sends play, so it stayed paused
 * after a lease. Dropping the key at entry (4b7faf4) left the transient
 * focus request, and that alone still paused the user's music on some
 * entries (opening Instagram, not LinkedIn) and did not reliably resume it
 * when focus was given back.
 */
object OverlayAudio {

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

    /**
     * Whether [overlay] takes audio focus and sends the pause key. Both or
     * neither. At entry, neither: see the class doc for what that leaves
     * playing and why it is accepted.
     */
    fun silences(overlay: Overlay): Boolean = when (overlay) {
        Overlay.EXPIRED_GATE, Overlay.WALK_GATE, Overlay.LOCK_MID_SESSION -> true
        Overlay.ENTRY_GATE, Overlay.LOCK_AT_ENTRY -> false
    }

    /** The lease gate's kind, from the flag it is shown with. */
    fun leaseGate(expired: Boolean): Overlay = if (expired) Overlay.EXPIRED_GATE else Overlay.ENTRY_GATE

    /** The lock screen's kind, from whether it was raised on entry. */
    fun lock(atEntry: Boolean): Overlay = if (atEntry) Overlay.LOCK_AT_ENTRY else Overlay.LOCK_MID_SESSION
}
