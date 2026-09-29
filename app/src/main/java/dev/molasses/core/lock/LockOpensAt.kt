package dev.molasses.core.lock

import dev.molasses.core.time.StampedInstant

/**
 * The wall-clock instant a standing lock opens, for the lock screen's
 * WILL BE OPEN BY line. Pure.
 *
 * ## Derived from the stored lock, through the restriction clamp
 * It is now plus [LockRegistry.remainingMs], and that remainder is the stored
 * lock's duration less what it has served, measured with
 * `ClockTamperClamp.Direction.RESTRICTION`. It is never the requested duration
 * added to the moment of asking, and never the stored start plus the duration
 * on the wall clock alone.
 *
 * The difference shows under a forward clock jump. The lock does not open
 * early, because the clamp credits the monotonic delta, not the jump. So the
 * honest opening time moves later on the wall clock by the size of the jump,
 * and that is what this reports. A start-plus-duration reading would show a
 * time already past for a lock that is still standing, which reads as a
 * broken lock rather than a tampered clock.
 */
object LockOpensAt {

    /** Null when no lock stands on [pkg] at [now]. */
    fun wallMs(locks: LockRegistry, pkg: String, now: StampedInstant): Long? {
        val remaining = locks.remainingMs(pkg, now)
        return if (remaining > 0L) now.wallMs + remaining else null
    }
}
