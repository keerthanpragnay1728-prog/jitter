package dev.molasses.core.lock

import dev.molasses.core.time.ClockTamperClamp
import dev.molasses.core.time.StampedInstant

/** Why a package is locked. Recorded for the ledger and the status line. */
enum class LockReason { BLOCK, FOCUS, BEDTIME, CHECKPOINT }

/** One armed lock. [startedAt] plus [durationMs] is the deadline. */
data class Lock(
    val pkg: String,
    val startedAt: StampedInstant,
    val durationMs: Long,
    val reason: LockReason,
)

/**
 * The one lock mechanism.
 *
 * `$ block`, `$ focus`, `$ bedtime` and the timed checkpoint lockout all say
 * the same thing: a package is unreachable until a deadline. They differ only
 * in which packages and how long, so they are one registry with a [LockReason]
 * rather than four code paths that would drift apart.
 *
 * ## Immutable
 * Every mutator returns a new registry. The alternative, a mutable map read
 * from the accessibility callback thread and written from the REPL, is a data
 * race on the hottest path in the app. Copying a map of at most a handful of
 * entries costs nothing next to that.
 *
 * ## The clamp direction
 * A lock is a **restriction**, so it uses
 * [ClockTamperClamp.Direction.RESTRICTION] and credits
 * `min(wall delta, elapsed delta)`. The attack on a lock is a *forward* clock
 * jump: set the date thirty days on and a naive wall-clock deadline expires
 * instantly. Taking the smaller delta means the jump credits nothing and the
 * lock holds.
 *
 * This is the opposite of `PauseWindow`, which is relief and must expire
 * rather than persist. See `CLAUDE.md` and the `ClockTamperClamp` class doc.
 *
 * ## Why not elapsedRealtime alone
 * `PauseWindow` drops the wall clock entirely and expires across a reboot,
 * which is simpler and airtight. A lock cannot: a seven day lock has to
 * survive reboots to mean anything, and `elapsedRealtime` restarts at zero on
 * every boot. So a lock carries a wall-clock stamp and accepts one residual
 * hole, documented on [remainingMs].
 *
 * Pure; no Android imports. Unit-tested in `LockRegistryTest`.
 */
class LockRegistry private constructor(
    private val locks: Map<String, Lock>,
) {
    constructor() : this(emptyMap())

    /**
     * Real time served against [lock], clamped as a restriction.
     *
     * Across a reboot there is no monotonic reading that spans the gap, so the
     * wall delta is trusted. That leaves one bypass: wind the clock forward,
     * then reboot. It costs a reboot rather than a tap, it is visible in the
     * ledger as a CLOCK_WARP row, and closing it would need a trusted time
     * source that this app has no network permission to reach.
     */
    private fun servedMs(lock: Lock, now: StampedInstant): Long {
        if (!lock.startedAt.isSet) return 0L
        return ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = lock.startedAt.wallMs,
                lastSeenElapsedMs = lock.startedAt.elapsedMs,
                nowWallMs = now.wallMs,
                nowElapsedMs = now.elapsedMs,
                bootIdChanged = lock.startedAt.bootId != now.bootId,
            ),
            direction = ClockTamperClamp.Direction.RESTRICTION,
        ).creditedMs
    }

    /** Milliseconds left on [pkg], or 0 when it is not locked. */
    fun remainingMs(pkg: String, now: StampedInstant): Long {
        val lock = locks[pkg] ?: return 0L
        return (lock.durationMs - servedMs(lock, now)).coerceIn(0L, lock.durationMs)
    }

    fun isLocked(pkg: String, now: StampedInstant): Boolean = remainingMs(pkg, now) > 0L

    fun reasonFor(pkg: String, now: StampedInstant): LockReason? =
        if (isLocked(pkg, now)) locks[pkg]?.reason else null

    /** Every lock still standing at [now], longest remaining first. */
    fun active(now: StampedInstant): List<Lock> =
        locks.values
            .filter { remainingMs(it.pkg, now) > 0L }
            .sortedByDescending { remainingMs(it.pkg, now) }

    /**
     * Arm a lock on [pkg].
     *
     * **A lock can only ever be extended, never shortened.** If an existing
     * lock would outlast the new one, the existing one is kept untouched.
     * Without that, `$ block instagram 1m` is a one-line cancel for a thirty
     * day lock, which would make the whole mechanism decorative. It is the
     * same invariant as `MonotonicInt` on `tierIndex`, in the time domain.
     *
     * A non-positive duration is a no-op rather than an unlock, for the same
     * reason.
     */
    fun arm(
        pkg: String,
        now: StampedInstant,
        durationMs: Long,
        reason: LockReason,
    ): LockRegistry {
        if (durationMs <= 0L || pkg.isEmpty()) return this
        val existingRemaining = remainingMs(pkg, now)
        if (existingRemaining >= durationMs) return this
        return LockRegistry(locks + (pkg to Lock(pkg, now, durationMs, reason)))
    }

    /** Arm the same duration across several packages, as `$ focus` does. */
    fun armAll(
        packages: Collection<String>,
        now: StampedInstant,
        durationMs: Long,
        reason: LockReason,
    ): LockRegistry = packages.fold(this) { acc, p -> acc.arm(p, now, durationMs, reason) }

    /**
     * Drop expired entries. Housekeeping only: [isLocked] already treats an
     * expired lock as absent, so pruning changes no behaviour and exists to
     * keep the persisted map from growing without bound.
     */
    fun prune(now: StampedInstant): LockRegistry =
        LockRegistry(locks.filterValues { remainingMs(it.pkg, now) > 0L })

    /**
     * Every lock, expired ones included. For persistence and the debug screen.
     * Deliberately not a way to read through [remainingMs].
     */
    fun snapshot(): List<Lock> = locks.values.sortedBy { it.pkg }

    companion object {
        /** Rebuild from persistence. */
        fun of(locks: List<Lock>): LockRegistry =
            LockRegistry(locks.associateBy { it.pkg })
    }
}
