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
 * a session in progress, the LEASE EXPIRED gate and the walking gate, and
 * only when the gated app is a video app. Never PLAY or PLAY_PAUSE, which
 * would start the user's own music when nothing else is playing, and nothing
 * on dismiss.
 *
 * ## Only video apps
 * Android sends a media key to the most recently active media session, not
 * to the app under the gate. In an app that plays no media (X, LinkedIn, a
 * Facebook text feed) that session is the user's own music, so a key sent
 * there paused it on every expired or walking gate. A video app is one whose
 * `ApplicationInfo.category` is `CATEGORY_VIDEO`, or YouTube by name, because
 * a category is self-declared and may be missing. Everything else gets
 * home-first only. The caller reads the category; a lookup that fails counts
 * as no category, so YouTube is still a video app by name.
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
 * ## The risk that is left
 * In a video app at expiry, the most recent session is almost always that
 * app's own player: a video started during the lease began after anything
 * playing in the background. It is still possible, and rare, for the user's
 * own music to be the most recent session in a video app: they browsed
 * without playing anything, or started their music from the shade while in
 * it. Then the key pauses their music instead. That is a paused song they
 * can resume with one tap, not anything lost.
 */
object MediaPause {

    const val YOUTUBE = "com.google.android.youtube"

    /**
     * @param categoryVideo `ApplicationInfo.category == CATEGORY_VIDEO`, or
     *   false when the caller could not read it.
     */
    fun isVideoApp(pkg: String, categoryVideo: Boolean): Boolean = categoryVideo || pkg == YOUTUBE

    fun sendsPause(overlay: OverlayKind, isVideoApp: Boolean): Boolean = isVideoApp && when (overlay) {
        OverlayKind.EXPIRED_GATE, OverlayKind.WALK_GATE -> true
        OverlayKind.ENTRY_GATE, OverlayKind.LOCK_AT_ENTRY, OverlayKind.LOCK_MID_SESSION -> false
    }
}
