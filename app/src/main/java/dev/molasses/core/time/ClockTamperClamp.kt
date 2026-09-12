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
 * Pure; no Android imports. Unit-tested in `ClockTamperClampTest`.
 */
object ClockTamperClamp {

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
    )

    fun evaluate(gap: Gap): Verdict {
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
            )
        }

        // Same boot: both clocks should agree. Sleep/doze affects neither
        // (elapsedRealtime counts sleep), so any real disagreement is the
        // wall clock having been moved.
        val disagreement = wallDelta - elapsedDelta
        if (kotlin.math.abs(disagreement) > TOLERANCE_MS) {
            return Verdict(
                // Step 4: accumulate min(wallDelta, elapsedDelta) ...
                creditedMs = minOf(wallDelta, elapsedDelta).coerceAtLeast(0),
                // ... and never advance the cycle anchor by more than elapsedDelta.
                maxAnchorAdvanceMs = elapsedDelta.coerceAtLeast(0),
                tampered = true,
                reason = if (disagreement > 0) {
                    "wall clock moved forward ${disagreement}ms vs monotonic"
                } else {
                    "wall clock moved backward ${-disagreement}ms vs monotonic"
                },
            )
        }

        return Verdict(
            creditedMs = elapsedDelta.coerceAtLeast(0),
            maxAnchorAdvanceMs = elapsedDelta.coerceAtLeast(0),
            tampered = false,
            reason = "clocks agree",
        )
    }
}
