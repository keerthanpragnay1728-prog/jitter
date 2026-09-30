package dev.molasses.core.safety

import dev.molasses.core.lease.GateControls

/**
 * The way out each full-screen overlay shows, in each state. Pure.
 *
 * ## The invariant
 * A gate can always be left. That was true while every overlay sat over its
 * app, because home took the overlay down with the session. A home-first
 * overlay outlives the session on purpose (see [HomeFirst]), so pressing home
 * no longer leaves it, and an overlay on that list without an exit of its own
 * holds the user until it times out. The walking gate did exactly that, for
 * up to 90 seconds, to anyone who could not walk or type.
 *
 * So every overlay on the home-first list must answer non-null here in every
 * state, and `OverlayExitTest` walks the list and the states. A new
 * home-first overlay without an exit fails that test, and the `when` below
 * will not compile without a branch for it.
 */
object OverlayExit {

    enum class Control { ARCHITECTS_SPACE }

    /**
     * @param remainingMs the countdown left, for the lease gates; the other
     *   overlays have no countdown and ignore it.
     * @return the exit on screen, or null when there is none in that state.
     */
    fun shown(overlay: HomeFirst.Overlay, remainingMs: Long): Control? = when (overlay) {
        HomeFirst.Overlay.EXPIRED_GATE -> GateControls.visible(expired = true, remainingMs = remainingMs).exit?.let(::of)
        HomeFirst.Overlay.ENTRY_GATE -> GateControls.visible(expired = false, remainingMs = remainingMs).exit?.let(::of)
        HomeFirst.Overlay.WALK_GATE -> Control.ARCHITECTS_SPACE
        HomeFirst.Overlay.LOCK_AT_ENTRY, HomeFirst.Overlay.LOCK_MID_SESSION -> Control.ARCHITECTS_SPACE
    }

    private fun of(exit: GateControls.Exit): Control = when (exit) {
        GateControls.Exit.ARCHITECTS_SPACE -> Control.ARCHITECTS_SPACE
    }
}
