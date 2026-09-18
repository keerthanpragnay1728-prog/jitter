package dev.molasses.core.lease

import dev.molasses.core.time.StampedInstant

/**
 * One granted lease. [startedAt] plus [durationMs] is the deadline.
 *
 * [takenAtAccumulatedMs] is the app's accumulated foreground total at the
 * moment the lease was granted. The lease itself does not use it; the friction
 * engine does, to know where "past the lease you took" begins in its own
 * timebase. The two systems measure different things and this is the only
 * number that crosses between them, deliberately carried on the lease rather
 * than looked up.
 */
data class Lease(
    val pkg: String,
    val startedAt: StampedInstant,
    val durationMs: Long,
    val takenAtAccumulatedMs: Long,
)

/**
 * System A: permission to be in an app, measured on the clock on the wall.
 *
 * ## Two systems, and they do not talk
 * Friction (System B, [dev.molasses.engine.FrictionEngine]) measures
 * accumulated foreground time and decides how much a scroll costs. A lease
 * measures real elapsed time and decides whether the user may be in the app at
 * all. They share no state and neither reads the other.
 *
 * The consequence to hold on to: **a lease buys no friction relief**. Taking
 * fifteen minutes does not slow the curve, lower a tier, or pause
 * accumulation. It buys fifteen minutes without the launch gate, and the
 * scrolling inside them costs exactly what it would have cost anyway. A lease
 * that also bought friction would be the one purchase that makes the ladder
 * negotiable, and there would then be no reason for a user not to buy it every
 * time.
 *
 * ## A lease survives leaving the app, including a swipe from Recents
 * A lease is not revoked for leaving. Stepping out to answer a message and
 * coming back inside the fifteen minutes must not cost a second gate, or the
 * gate becomes a punishment for switching apps rather than a toll for
 * entering one.
 *
 * That has a consequence which reads as a bug and is not one, so it is
 * written down here rather than rediscovered: **open a target, take a lease,
 * swipe the task away from Recents, reopen, and no gate fires.** The lease is
 * still live on the wall clock, and the wall clock is the only thing it
 * measures.
 *
 * It was considered and declined, in that order.
 *
 * The swipe itself is not observable. `packageNames` is scoped to the targets
 * plus our own package, so nothing from the Recents surface reaches the
 * service; the user has already left the app to get there, so the exit has
 * fired already and the swipe adds no event on top of it; `ForegroundProbe`
 * filters to `ACTIVITY_RESUMED` and a task removal produces none; and
 * `getWindows()` is empty under this profile, so there is no enumeration to
 * notice a task leaving.
 *
 * A cold start is not distinguishable from a warm resume either. The only
 * signal available is the class name on the first `WINDOW_STATE_CHANGED`
 * after a return, and a warm resume to the app's front screen carries the
 * same launcher activity a cold start does. So a test built on it fires on a
 * return the lease was sold to cover, which is the one case this whole
 * section exists to protect, and "the gate fires on a return you already paid
 * for" is how an app earns a force-quit rather than a habit.
 *
 * So the trade is taken deliberately: a swipe is leaving with extra steps,
 * and it buys exactly one gate at the cost of a deliberate multi-step
 * gesture. Accumulation and the penalty ratchet are untouched throughout, so
 * the friction the user came here for still runs. That is a trade worth
 * losing.
 *
 * The corollary is that **there is no per-package eviction and there must not
 * be one.** A `revoke(pkg)` existed for a while with no caller, and its own
 * doc claimed the rollover used it while the rollover went through
 * `clearLeases` and a fresh manager. It is deleted. Anything that brings it
 * back has to answer the paragraph above first, and has to leave
 * `leasesTaken` alone when it does, or an eviction that also reset the ladder
 * would make the bypass cheaper than the gate it avoids.
 *
 * ## Immutable
 * Every mutator returns a new manager, for the same reason as
 * [dev.molasses.core.lock.LockRegistry]: it is read on the accessibility
 * callback thread and written from a coroutine, and a shared mutable map
 * across those two is a data race on the hottest path in the app.
 *
 * ## The clock
 * A lease is **relief**, so its failure mode must be expiring early rather
 * than late. That rules out a bare wall clock outright: winding the clock back
 * an hour would hold a five minute lease open forever.
 *
 * `ClockTamperClamp.Direction.RELIEF` (credit `max(wall, elapsed)`) would be
 * enough, but this goes further and drops the wall clock entirely, measuring
 * on `elapsedRealtime` alone exactly as
 * [dev.molasses.core.safety.PauseWindow] does. `elapsedRealtime` is monotonic
 * within a boot, counts sleep, and cannot be set, so there is nothing to
 * clamp against.
 *
 * The cost is that a reboot ends every lease, and that is the correct
 * behaviour rather than a limitation. CLAUDE.md states the rule: dropping the
 * wall clock is the right default for any relief short enough that a reboot
 * outlasts it, and the longest lease on offer is fifteen minutes. It also
 * settles B5's requirement directly: a lease survives process death, because
 * `elapsedRealtime` and the boot id both survive it, and killing the launcher
 * cannot resurrect an expired lease, because expiry is recomputed from the
 * clock on every read rather than stored as a flag.
 *
 * Pure; no Android imports. Unit-tested in `LeaseManagerTest`.
 */
class LeaseManager private constructor(
    private val leases: Map<String, Lease>,
) {
    constructor() : this(emptyMap())

    /**
     * Milliseconds left on [pkg]'s lease, or 0 when it holds none.
     *
     * Recomputed from the clock every time. There is no "expired" flag to go
     * stale across a process death, and no way for a stored value to outlive
     * the reading it was derived from.
     */
    fun remainingMs(pkg: String, now: StampedInstant): Long {
        val lease = leases[pkg] ?: return 0L
        if (!lease.startedAt.isSet) return 0L
        // A reboot ends it: elapsedRealtime restarted from zero, so the stored
        // reading is not comparable to this one and the safe answer, for
        // relief, is expired.
        if (lease.startedAt.bootId != now.bootId) return 0L
        val age = now.elapsedMs - lease.startedAt.elapsedMs
        // A stamp from the future within one boot can only be a corrupt read.
        // Expired, not "a very long lease".
        if (age < 0L) return 0L
        return (lease.durationMs - age).coerceIn(0L, lease.durationMs)
    }

    fun isActive(pkg: String, now: StampedInstant): Boolean = remainingMs(pkg, now) > 0L

    /** The live lease on [pkg], or null. Expired entries read as absent. */
    fun active(pkg: String, now: StampedInstant): Lease? =
        leases[pkg]?.takeIf { remainingMs(pkg, now) > 0L }

    /**
     * Grant [durationMs] on [pkg].
     *
     * **A live lease is never lengthened.** If one is already running, the
     * shorter of the two deadlines wins. In normal use this never fires: the
     * decision panel only surfaces once the countdown reaches zero, which is
     * only reachable with no lease active. It exists so that a double tap, a
     * replayed intent, or a gate that somehow shows twice cannot buy a second
     * fifteen minutes on top of the first.
     *
     * This is the mirror image of [dev.molasses.core.lock.LockRegistry.arm],
     * which can only ever extend, and for the same underlying reason: a lock
     * is a restriction and a lease is relief, so the direction that must be
     * impossible is the opposite one. Always err toward more friction.
     *
     * A non-positive duration is a no-op rather than a revocation.
     */
    fun grant(
        pkg: String,
        now: StampedInstant,
        durationMs: Long,
        accumulatedMs: Long,
    ): LeaseManager {
        if (pkg.isEmpty() || durationMs <= 0L) return this
        val standing = remainingMs(pkg, now)
        if (standing > 0L && standing <= durationMs) return this
        return LeaseManager(
            leases + (pkg to Lease(pkg, now, durationMs, accumulatedMs)),
        )
    }

    /**
     * Drop expired entries. Housekeeping only: [isActive] already treats an
     * expired lease as absent, so this changes no behaviour and exists to keep
     * the persisted map bounded.
     */
    fun prune(now: StampedInstant): LeaseManager =
        LeaseManager(leases.filterValues { remainingMs(it.pkg, now) > 0L })

    /** Every lease, expired ones included. For persistence. */
    fun snapshot(): List<Lease> = leases.values.sortedBy { it.pkg }

    companion object {
        /** Rebuild from persistence. */
        fun of(leases: List<Lease>): LeaseManager =
            LeaseManager(leases.associateBy { it.pkg })
    }
}
