package dev.molasses.core.safety

import dev.molasses.core.lease.GateControls

/**
 * The way out each full-screen overlay shows, in each state. Pure.
 *
 * ## The invariant
 * A gate can always be left. That was true while every overlay sat over its
 * app, because home took the overlay down with the session. Every overlay
 * now sends its app home and outlives the session on purpose (see
 * [OverlayKind]), so pressing home no longer leaves it, and an overlay
 * without an exit of its own holds the user until it times out. The walking
 * gate did exactly that, for up to 90 seconds, to anyone who could not walk
 * or type.
 *
 * So every [OverlayKind] must answer non-null here in every state, and
 * `OverlayExitTest` walks the kinds and the states. A new kind without an
 * exit fails that test, and the `when` below will not compile without a
 * branch for it.
 */
object OverlayExit {

    enum class Control { ARCHITECTS_SPACE }

    /**
     * @param remainingMs the countdown left, for the lease gates; the other
     *   overlays have no countdown and ignore it.
     * @return the exit on screen, or null when there is none in that state.
     */
    fun shown(overlay: OverlayKind, remainingMs: Long): Control? = when (overlay) {
        OverlayKind.EXPIRED_GATE -> GateControls.visible(expired = true, remainingMs = remainingMs).exit?.let(::of)
        OverlayKind.ENTRY_GATE -> GateControls.visible(expired = false, remainingMs = remainingMs).exit?.let(::of)
        OverlayKind.WALK_GATE -> Control.ARCHITECTS_SPACE
        OverlayKind.LOCK_AT_ENTRY, OverlayKind.LOCK_MID_SESSION -> Control.ARCHITECTS_SPACE
    }

    private fun of(exit: GateControls.Exit): Control = when (exit) {
        GateControls.Exit.ARCHITECTS_SPACE -> Control.ARCHITECTS_SPACE
    }
}
