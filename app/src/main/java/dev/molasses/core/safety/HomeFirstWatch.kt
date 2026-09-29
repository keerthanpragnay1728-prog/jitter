package dev.molasses.core.safety

/**
 * What the foreground watch does while a home-first overlay is up. Pure.
 *
 * A home-first overlay sent its app home and stays over the launcher. From
 * there, Recents can bring that same app back to the front underneath it,
 * and the app resumes and plays under the gate: the one thing going home was
 * for. So the watch sends home again. A sensitive app coming up takes every
 * overlay down, as it always has, because nothing of ours is ever drawn over
 * one. Any other app coming up changes nothing: the overlay stays and its
 * exit is visible (see [OverlayExit]).
 *
 * ## Debounced
 * At most one re-home per [REHOME_DEBOUNCE_MS], so a Recents toggle, or a
 * foreground reading that lags the actual switch, cannot turn into a loop of
 * home actions.
 */
object HomeFirstWatch {

    const val REHOME_DEBOUNCE_MS = 2_000L

    sealed interface Action {
        /** A sensitive app is in front: take every overlay down. */
        data object TearDown : Action

        /** The gated app is back in front: send it home again. */
        data object Rehome : Action

        /** It is back, but a re-home was sent under [REHOME_DEBOUNCE_MS] ago. */
        data object Debounced : Action

        /** Anything else in front. */
        data object Nothing : Action
    }

    /**
     * @param active the package in front now.
     * @param gatedPkg the package the home-first overlay is up for.
     * @param lastRehomeMs when the last re-home was sent, on the same clock
     *   as [nowMs], or null if none has been.
     */
    fun onForeground(
        active: String,
        gatedPkg: String?,
        sensitive: Boolean,
        nowMs: Long,
        lastRehomeMs: Long?,
    ): Action = when {
        sensitive -> Action.TearDown
        gatedPkg == null || active != gatedPkg -> Action.Nothing
        lastRehomeMs != null && nowMs - lastRehomeMs < REHOME_DEBOUNCE_MS -> Action.Debounced
        else -> Action.Rehome
    }
}
