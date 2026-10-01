package dev.molasses.core.safety

/**
 * Which overlays send one media PAUSE key, after home. Pure.
 *
 * ## What it is for, and only that
 * Home stops an app that plays inside its own activity, which covers
 * Instagram. It does not stop YouTube, which moves a playing video into a
 * picture-in-picture window, or keeps the sound going with Premium
 * background play, once it is sent home. No public API closes another app's
 * picture-in-picture window. A media PAUSE key does reach it: on device,
 * `input keyevent 127` paused YouTube's video and audio in picture-in-picture,
 * and left the floating window on screen, paused, for the user to dismiss.
 *
 * So one PAUSE key, down then up, after home, on the two overlays raised over
 * a session in progress: the LEASE EXPIRED gate and the walking gate. Never
 * PLAY or PLAY_PAUSE, which would start the user's own music when nothing
 * else is playing, and nothing on dismiss.
 *
 * ## Why not at entry, and not on locks
 * The pause key was removed once, after device evidence: at entry it paused
 * the user's own music, because at entry the target has not played anything
 * yet and the most recently active media session is whatever the user was
 * listening to. A lock at entry is the same case. Locks are left out
 * mid-session too: the key was re-added for the two gates, on the evidence
 * of the two gates, and nothing has been measured for a lock. Audio focus
 * stays deleted: Instagram re-took it within 0.6 s, and releasing it when
 * the gate closed let the app resume.
 *
 * ## Whose session it reaches
 * Android routes a media key to the most recently active media session. At
 * expiry that is the target app: the user has spent a whole lease in it, and
 * a video or Reel playing there started after anything playing in the
 * background. The walking gate fires on a scroll past the horizon, in the
 * same position. If the user's own music was the most recent session (the
 * target played nothing during the lease, or the user started their music
 * from the shade while in it), the key pauses that instead. That is a paused
 * song the user can resume with one tap, not anything lost.
 */
object MediaPause {

    fun sendsPause(overlay: OverlayKind): Boolean = when (overlay) {
        OverlayKind.EXPIRED_GATE, OverlayKind.WALK_GATE -> true
        OverlayKind.ENTRY_GATE, OverlayKind.LOCK_AT_ENTRY, OverlayKind.LOCK_MID_SESSION -> false
    }
}
