package dev.molasses.core.time

import dev.molasses.core.model.CycleResetPolicy

/**
 * An instant stamped on both clocks, plus the boot it was taken in.
 *
 * A wall-clock stamp on its own is worthless for deciding when a cycle ends.
 * Moving the system clock forward six hours is the cheapest bypass in the
 * whole app, it needs no root and no tooling, and a pure wall-clock deadline
 * cannot tell it from six hours of genuine waiting. `elapsedRealtime` can,
 * because it is not settable, but it restarts at zero on boot and so cannot
 * span one. Carrying all three together is what lets [CycleWindow] pick the
 * right comparison for the situation it is actually in.
 *
 * [wallMs] doubles as the "is this set" flag. A real anchor is always a
 * Unix timestamp in the trillions, so zero is unambiguous.
 */
data class StampedInstant(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootId: Int,
) {
    val isSet: Boolean get() = wallMs > 0L

    companion object {
        val UNSET = StampedInstant(0L, 0L, 0)
    }
}

/**
 * The six-hour cycle window, in pure arithmetic.
 *
 * The whole of the tamper question is delegated to [ClockTamperClamp], which
 * already implements reconciler steps 3 and 4 and is already tested. This
 * type exists so that the same rule is applied everywhere a deadline is
 * evaluated (engine tick, foreground entry, and reconciliation on connect)
 * rather than being re-derived at each site, which is how the three of them
 * came to disagree in the first place.
 *
 * Pure; no Android imports. Unit-tested in `CycleWindowTest`.
 */
object CycleWindow {

    /**
     * Real time since [stamp], measured so that the wall clock alone can never
     * inflate it within a boot.
     *
     * Same boot, clocks agreeing: the monotonic delta.
     * Same boot, clocks disagreeing by more than the clamp tolerance:
     * `min(wall delta, monotonic delta)`, so a clock jump credits nothing.
     * Across a boot: the wall delta floored at zero, because there is no
     * monotonic reading that spans the reboot to check it against.
     *
     * Returns 0 for an unset stamp, which reads as "no time has passed since
     * an anchor that does not exist" and keeps every caller total.
     */
    fun ageMs(stamp: StampedInstant, now: StampedInstant): Long {
        if (!stamp.isSet) return 0L
        return ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = stamp.wallMs,
                lastSeenElapsedMs = stamp.elapsedMs,
                nowWallMs = now.wallMs,
                nowElapsedMs = now.elapsedMs,
                bootIdChanged = stamp.bootId != now.bootId,
            ),
        ).creditedMs
    }

    /** True once [stamp] is at least [windowMs] old. Never true when unset. */
    fun isDue(
        stamp: StampedInstant,
        now: StampedInstant,
        windowMs: Long = CycleResetPolicy.WINDOW_MS,
    ): Boolean = stamp.isSet && ageMs(stamp, now) >= windowMs

    /**
     * What the status header shows as RESETS IN. Clamped into
     * `[0, windowMs]`: never negative, and never longer than a full window
     * even if the wall clock has been set backwards.
     *
     * An unset anchor reports a full window, because that is what the next
     * foreground entry will start.
     */
    fun remainingMs(
        anchor: StampedInstant,
        now: StampedInstant,
        windowMs: Long = CycleResetPolicy.WINDOW_MS,
    ): Long {
        if (!anchor.isSet) return windowMs
        return (windowMs - ageMs(anchor, now)).coerceIn(0L, windowMs)
    }
}
