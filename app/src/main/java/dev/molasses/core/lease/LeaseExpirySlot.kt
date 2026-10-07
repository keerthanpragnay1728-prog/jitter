package dev.molasses.core.lease

/**
 * The one pending lease-expiry check: whose it is, and every decision to set,
 * keep or drop it. Pure. The service holds one, applies each [Action] to its
 * Handler, and logs each one with its reason. See [LeaseExpiryCheck] for what
 * a check does when it fires.
 *
 * ## Why it is its own object
 * J1 armed the check from the grant and from the enter path, and dropped it
 * on every leave, for any package. That was verified before home-first. Now
 * a lease is chosen on a gate drawn over the launcher, with no session open,
 * and the app is relaunched after. Every step between the grant and the
 * user's first minute back in the app could undo the check: a leave of the
 * session that ended before the grant arriving late, a leave of some other
 * package, an enter that found the app's own gate still drawn and returned
 * before re-arming. The symptom was a lease that ran out during use with no
 * gate until the user left.
 *
 * So the rules are written down here, where they can be tested in order:
 *  - A grant always sets the check, whatever is open. It is the deadline.
 *  - An enter of the app the check is for keeps it and marks the app opened.
 *    It never cancels it. An enter of another app on a live lease of its
 *    own replaces it; one with no lease leaves it alone.
 *  - A leave drops it only when it is a leave of that app after the app has
 *    opened since the check was set. Any other leave keeps it, and says why.
 *  - A new grant, a rollover and teardown drop it, as before.
 *
 * A kept check that turns out to be stale costs nothing: when it fires it
 * re-reads the open session and the lease, and does nothing unless that app
 * is open with its lease spent.
 */
class LeaseExpirySlot {

    data class Pending(val pkg: String, val opened: Boolean)

    sealed interface Action {
        val why: String

        /** Post a check for [pkg] in [delayMs], replacing any pending one. */
        data class Schedule(val pkg: String, val delayMs: Long, override val why: String) : Action

        /** Remove the pending check for [pkg]. */
        data class Cancel(val pkg: String, override val why: String) : Action

        /** Leave the Handler as it is. Logged, so a kept check is as visible as a dropped one. */
        data class Keep(override val why: String) : Action
    }

    var pending: Pending? = null
        private set

    /** A lease was granted for [pkg]. [openPkg] is the open session now, if any. */
    fun grant(pkg: String, openPkg: String?, remainingMs: Long): Action {
        val plan = LeaseExpiryCheck.plan(pkg, remainingMs)
        if (plan is LeaseExpiryCheck.Plan.Schedule) {
            pending = Pending(pkg, opened = openPkg == pkg)
            return Action.Schedule(pkg, plan.delayMs, "new grant, open=${openPkg ?: "none"}")
        }
        return clear("new grant with no time on it")
    }

    /** A session opened on [pkg], let through. [remainingMs] is its lease now. */
    fun enter(pkg: String, remainingMs: Long): Action {
        val p = pending
        if (p?.pkg == pkg) {
            pending = p.copy(opened = true)
            return Action.Keep("enter $pkg: its check stays, set for its deadline")
        }
        val plan = LeaseExpiryCheck.plan(pkg, remainingMs)
        if (plan is LeaseExpiryCheck.Plan.Schedule) {
            pending = Pending(pkg, opened = true)
            return Action.Schedule(pkg, plan.delayMs, "enter on a live lease")
        }
        return Action.Keep("enter $pkg: no lease" + (p?.let { ", check for ${it.pkg} stays" } ?: ""))
    }

    /** The session on [pkg] closed. */
    fun leave(pkg: String, reason: String): Action {
        val p = pending ?: return Action.Keep("left $pkg ($reason): nothing pending")
        return when {
            p.pkg != pkg -> Action.Keep("left $pkg ($reason): the check is for ${p.pkg}")
            !p.opened -> Action.Keep("left $pkg ($reason) before it opened since its grant")
            else -> {
                pending = null
                Action.Cancel(pkg, "left $pkg ($reason)")
            }
        }
    }

    /** The service connected. [openPkg] is open now, with [remainingMs] on its lease. */
    fun connect(openPkg: String?, remainingMs: Long): Action {
        val plan = LeaseExpiryCheck.onConnect(openPkg, remainingMs)
        if (plan is LeaseExpiryCheck.Plan.Schedule) {
            pending = Pending(plan.pkg, opened = true)
            return Action.Schedule(plan.pkg, plan.delayMs, "connect")
        }
        return Action.Keep("connect: nothing open on a live lease")
    }

    /**
     * The check for [pkg] fired. The verdict is [LeaseExpiryCheck.onFire]'s;
     * this only keeps the slot in step: a reschedule stays pending for the same
     * app, anything else empties it.
     */
    fun fire(pkg: String, openPkg: String?, remainingMs: Long): LeaseExpiryCheck.Fire {
        val fire = LeaseExpiryCheck.onFire(pkg, openPkg, remainingMs)
        if (fire !is LeaseExpiryCheck.Fire.Reschedule) pending = null
        return fire
    }

    /** A rollover or teardown: drop whatever is pending. */
    fun clear(why: String): Action {
        val p = pending ?: return Action.Keep("$why: nothing pending")
        pending = null
        return Action.Cancel(p.pkg, why)
    }
}
