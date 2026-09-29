package dev.molasses.core.time

/**
 * Reconciler steps 3 and 4: decide how much time really passed across a gap in
 * which this process was not running.
 *
 * The attack this defends against is trivial and effective: accumulate 19
 * minutes, background the app, set the system clock forward seven hours, and
 * come back to a fresh cycle. Wall clock alone cannot tell that from seven
 * hours of genuine abstinence. `elapsedRealtime` can -- it is not settable --
 * but it resets on reboot, so it cannot span a boot.
 *
 * ## Two directions, because the safe answer is not the same for both
 * Every deadline in this app is one of two kinds, and they want opposite
 * clamps. The single principle is: **always err toward more friction.**
 *
 * A **restriction** (cycle accumulation, a lock) ends when enough time has
 * been credited, and ending it early is the bypass. The attack is a *forward*
 * clock jump, so credit `min(wall delta, elapsed delta)`: the jump inflates
 * the wall delta, the monotonic delta does not move, and a thirty day lock
 * holds instead of evaporating.
 *
 * A **relief** (a pause, `$ allow`) also ends when enough time has been
 * credited, but here ending it *late* is the bypass. The attack is a
 * *backward* wind, so credit `max(wall delta, elapsed delta)`: winding the
 * wall clock back makes its delta small or negative, and the monotonic delta
 * still expires the relief on schedule.
 *
 * Getting this backwards is not theoretical. A pause built on the restriction
 * clamp was held open indefinitely by winding the clock back an hour, which a
 * test in `PauseWindowTest` caught before it shipped.
 *
 * Pure; no Android imports. Unit-tested in `ClockTamperClampTest`.
 */
object ClockTamperClamp {

    /**
     * Which way this deadline must fail. See the class doc.
     *
     * [RESTRICTION] is the default because it is the safe one to get wrong by
     * omission: a deadline clamped as a restriction when it should have been
     * relief lasts too long, which costs the user convenience. The reverse
     * costs them the whole mechanism.
     */
    enum class Direction { RESTRICTION, RELIEF }

    /** Disagreement above this between the two clocks means the wall clock moved. */
    const val TOLERANCE_MS = 60_000L

    /**
     * @param bootIdChanged true when `Settings.Global.BOOT_COUNT` differs from
     *   the stored value, i.e. `elapsedRealtime` has reset and cannot be
     *   compared across the gap.
     */
    data class Gap(
        val lastSeenWallMs: Long,
        val lastSeenElapsedMs: Long,
        val nowWallMs: Long,
        val nowElapsedMs: Long,
        val bootIdChanged: Boolean,
    )

    data class Verdict(
        /** Real time to credit to the gap. Never negative. */
        val creditedMs: Long,
        /** Ceiling on how far `cycle_anchor_wall_ms` may advance. */
        val maxAnchorAdvanceMs: Long,
        val tampered: Boolean,
        val reason: String,
        /**
         * True when the gap spans a reboot, so [creditedMs] rests on the wall
         * clock alone with nothing to check it against.
         *
         * A restriction accepts that: a lock has to span a reboot to be worth
         * anything, and the residual hole (wind the clock forward, then
         * reboot) costs a reboot rather than a tap in settings.
         *
         * A relief must not. There is no reading that survives the boot to
         * bound the wall delta, so a relief mechanism treats this as expiry
         * outright. `PauseWindow` does exactly that and is the reference.
         */
        val bootChanged: Boolean,
    )

    fun evaluate(
        gap: Gap,
        direction: Direction = Direction.RESTRICTION,
    ): Verdict {
        val wallDelta = gap.nowWallMs - gap.lastSeenWallMs
        val elapsedDelta = gap.nowElapsedMs - gap.lastSeenElapsedMs

        if (gap.bootIdChanged) {
            // elapsedRealtime restarted at 0, so elapsedDelta is meaningless
            // (and usually negative). Step 3: trust only the wall clock across
            // that boundary, but never credit negative time -- a user who set
            // the clock back before rebooting would otherwise bank credit.
            val credited = wallDelta.coerceAtLeast(0)
            return Verdict(
                creditedMs = credited,
                // Across a boot we have no independent measure, so the wall
                // delta is also the anchor ceiling. The row is marked
                // CLOCK_WARP so the discontinuity stays auditable.
                maxAnchorAdvanceMs = credited,
                tampered = false,
                reason = "boot boundary: wall clock only",
                bootChanged = true,
            )
        }

        // Same boot: both clocks should agree. Sleep/doze affects neither
        // (elapsedRealtime counts sleep), so any real disagreement is the
        // wall clock having been moved.
        val disagreement = wallDelta - elapsedDelta
        if (kotlin.math.abs(disagreement) > TOLERANCE_MS) {
            return Verdict(
                // A restriction takes the smaller delta so a forward jump
                // credits nothing; a relief takes the larger so a backward
                // wind credits the monotonic delta anyway. See the class doc.
                creditedMs = when (direction) {
                    Direction.RESTRICTION -> minOf(wallDelta, elapsedDelta)
                    Direction.RELIEF -> maxOf(wallDelta, elapsedDelta)
                }.coerceAtLeast(0),
                // ... and never advance the cycle anchor by more than elapsedDelta.
                maxAnchorAdvanceMs = elapsedDelta.coerceAtLeast(0),
                tampered = true,
                reason = if (disagreement > 0) {
                    "wall clock moved forward ${disagreement}ms vs monotonic"
                } else {
                    "wall clock moved backward ${-disagreement}ms vs monotonic"
                },
                bootChanged = false,
            )
        }

        return Verdict(
            creditedMs = elapsedDelta.coerceAtLeast(0),
            maxAnchorAdvanceMs = elapsedDelta.coerceAtLeast(0),
            tampered = false,
            reason = "clocks agree",
            bootChanged = false,
        )
    }
}
