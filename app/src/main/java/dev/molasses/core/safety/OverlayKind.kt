package dev.molasses.core.safety

/**
 * The five full-screen overlays. Every one of them sends its app home under
 * itself after its first draw and stays up over the launcher. Pure.
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
 * launcher, and the countdown carries on there. Focus and the mute are gone.
 * One PAUSE key came back, after home and on two overlays only, for the
 * playback home cannot stop: see [MediaPause].
 *
 * ## All of them, with no switch
 * The entry gate and a lock raised as the app opens were left out once, on
 * the reasoning that at entry nothing of the target's has started yet. On
 * device that was wrong: Instagram's first Reel autoplays with sound behind
 * the entry gate, because the app under an overlay is resumed. So every
 * overlay goes home, and the decision that used to choose which (`HomeFirst`,
 * with a per-kind `sendsHome`) is deleted rather than kept answering yes to
 * every kind. A new overlay kind goes home too, and has to give an exit in
 * [OverlayExit], whose `when` will not compile without a branch for it.
 *
 * What home does not stop is playback the target keeps alive outside its
 * own activity: YouTube's picture-in-picture window, and Premium background
 * play. Neither is visible to this service's profile, and no public API
 * closes the window. [MediaPause] pauses it on the two overlays raised over
 * a session in progress; the paused window stays for the user to dismiss.
 *
 * The kinds exist for [OverlayExit] and for the log line each overlay writes
 * when it sends home.
 */
enum class OverlayKind {
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
    ;

    companion object {
        /** The lease gate's kind, from the flag it is shown with. */
        fun leaseGate(expired: Boolean): OverlayKind = if (expired) EXPIRED_GATE else ENTRY_GATE

        /** The lock screen's kind, from whether it was raised on entry. */
        fun lock(atEntry: Boolean): OverlayKind = if (atEntry) LOCK_AT_ENTRY else LOCK_MID_SESSION
    }
}
