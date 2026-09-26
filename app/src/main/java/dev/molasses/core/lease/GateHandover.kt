package dev.molasses.core.lease

/**
 * Whose gate is on screen, and whose session is open, when a target app
 * arrives. Pure.
 *
 * ## The bug this exists for
 * A direct switch from target A to target B (a share sheet, a link, the
 * recents carousel) opened B without closing A. The service's session stayed
 * A's, A's watchdog kept running, and A's windows stayed up. And the launch
 * gate asked only whether *a* gate was showing, so A's lease gate answered
 * "already gated" for B: B was let through on a gate it never got, with A's
 * name and A's countdown on it.
 *
 * So both questions are asked per package. Entering B closes A first, as a
 * trip through the launcher would have. And a gate on screen counts for B only
 * when it is B's; anything else comes down before B gets its own decision.
 */
object GateHandover {

    /**
     * True when [openPkg]'s session must be closed before [pkg] opens: it is
     * open and it belongs to another package. Re-entering the open package is
     * not a switch; window state changes fire repeatedly inside one app.
     */
    fun mustCloseFirst(openPkg: String?, pkg: String): Boolean =
        openPkg != null && openPkg != pkg

    sealed interface Action {
        /** Nothing is on screen. Decide for this package. */
        data object Decide : Action

        /** This package's own gate is up. It is the decision; do nothing. */
        data object AlreadyOwn : Action

        /**
         * Another package's gate is up, or one whose owner is unknown. Take it
         * down, then decide for this package as if nothing had been showing.
         * An unknown owner is treated as foreign because the alternative,
         * reading it as this package's, is the stand-in this exists to stop.
         */
        data class ReleaseFirst(val owner: String?) : Action
    }

    /**
     * @param anyShowing a lease gate, walking gate or lock screen is on screen.
     * @param owner the package that window was shown for, or null if unknown.
     */
    fun forGate(anyShowing: Boolean, owner: String?, pkg: String): Action = when {
        !anyShowing -> Action.Decide
        owner == pkg -> Action.AlreadyOwn
        else -> Action.ReleaseFirst(owner)
    }
}
