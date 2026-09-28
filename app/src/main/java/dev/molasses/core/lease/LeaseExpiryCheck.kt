package dev.molasses.core.lease

/**
 * When the service checks, by itself, whether a lease has run out. Pure.
 *
 * ## Why
 * A lease is only ever judged in `maybeLaunchGate`, and that is reached only
 * from a target's window-state change or a scroll. A Reel that plays on with
 * nobody touching the screen produces neither, so a 10 minute lease ran for
 * as long as the user kept watching: the LEASE EXPIRED gate waited for the
 * next swipe. One check at the lease's deadline closes that without polling.
 *
 * ## One check, and it trusts nothing it scheduled
 * The check is set for the lease's remaining time when the lease is granted,
 * or when a session opens on a package that already holds a live lease.
 * When it fires it asks again: the package must still be the open session,
 * and the lease is re-read from the registry. Time left (a clock that ran
 * slow, a longer lease granted since) reschedules for what is left; none
 * left raises the gate through the ordinary path. A scheduled time is a
 * reminder to look, never the verdict.
 */
object LeaseExpiryCheck {

    sealed interface Plan {
        data class Schedule(val pkg: String, val delayMs: Long) : Plan
        data object None : Plan
    }

    sealed interface Fire {
        /** The lease is spent and the package is still open: gate it now. */
        data object RaiseGate : Fire

        /** Still time on the lease. Look again when it should be spent. */
        data class Reschedule(val delayMs: Long) : Fire

        /** Nothing to do, and why, for the log. */
        data class Skip(val why: String) : Fire
    }

    /**
     * A lease was granted for [pkg], or a session opened on [pkg] while it
     * holds one. [remainingMs] is the registry's answer for it now.
     */
    fun plan(pkg: String, remainingMs: Long): Plan =
        if (remainingMs > 0L) Plan.Schedule(pkg, remainingMs) else Plan.None

    /**
     * The service connected. A check is re-armed only for a package that is
     * both open and still leased; with nothing open there is nothing to gate.
     */
    fun onConnect(openPkg: String?, remainingMs: Long): Plan =
        if (openPkg == null) Plan.None else plan(openPkg, remainingMs)

    /** The check for [pkg] fired. [openPkg] and [remainingMs] are read now. */
    fun onFire(pkg: String, openPkg: String?, remainingMs: Long): Fire = when {
        openPkg != pkg -> Fire.Skip("no longer open")
        remainingMs > 0L -> Fire.Reschedule(remainingMs)
        else -> Fire.RaiseGate
    }
}
