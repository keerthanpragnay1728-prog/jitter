package dev.molasses.core.friction

/**
 * Stall duration and probability at a given cumulative time.
 *
 * Friction has two dimensions now. A short guaranteed stall and a long
 * unlikely one are different experiences even when their expected cost per
 * scroll is identical, and the early bands lean entirely on the second.
 */
data class FrictionPoint(
    /** Commanded stall, never below the floor unless it is exactly zero. */
    val stallMs: Int,
    /** Chance this scroll stalls at all, 0.0 to 1.0. */
    val probability: Float,
) {
    val stalls: Boolean get() = stallMs > 0 && probability > 0f
}

/**
 * The 2D friction curve.
 *
 * ## Why probability, and not just shorter stalls
 * Segment D measured **406 ms p50** on hardware: a commanded stall arms about
 * that long after the scroll that triggered it. A 100 ms stall therefore arms
 * and expires inside the gap before the next swipe and is never observed. On
 * this device, anything below D p50 does not exist.
 *
 * So the low bands get their subtlety from probability instead. A 10% chance
 * of a 450 ms stall is subtler than a guaranteed 150 ms one, and unlike the
 * 150 ms one it actually happens.
 *
 * ## The floor
 * [DEFAULT_FLOOR_MS] is the one place the number lives. It is a parameter on
 * [frictionAt] rather than a constant read inside it, so it can be retuned per
 * device once segment D is measured elsewhere, and lowered once D itself is
 * optimised.
 *
 * The specified table's low bands ask for durations below the floor (150 to
 * 350 ms at 6 to 9 minutes). Those are clamped up. At the default floor of
 * 450 ms that flattens the whole 6 to 10 minute range to a constant duration,
 * which is not a defect: it is the design in B2 falling out of the arithmetic.
 * Across that range only probability moves.
 *
 * ## Monotonicity
 * Both dimensions are non-decreasing in `accumulatedMs`, asserted by a sweep
 * test at one second granularity over the whole range.
 *
 * The table as specified is **not** monotonic at its band edges: the 8 to 9
 * minute band ends at 350 ms and the 9 to 10 band starts at 300 ms, and the
 * same dips appear at 12, 14 and 18 minutes in one dimension or the other.
 * Read literally it would refund friction at four points. [KNOTS] resolves
 * each boundary to the larger of the two neighbouring values, so the curve is
 * continuous and never steps down. That is the same principle as
 * `MonotonicInt` on `tierIndex`, applied to a continuous function.
 *
 * The one discontinuity that survives is at 6 minutes, where both dimensions
 * jump from zero. Friction has to begin somewhere and a ramp from zero
 * probability would make the first minutes indistinguishable from none.
 *
 * Pure; no Android imports. Unit-tested in `FrictionCurveTest`.
 */
object FrictionCurve {

    /**
     * Measured segment D p50 on the reference device. Below this a stall arms
     * after the gesture it belonged to and is never felt.
     */
    const val MEASURED_SEGMENT_D_P50_MS = 406

    /** Default floor. See the class doc; retune when D is measured elsewhere. */
    const val DEFAULT_FLOOR_MS = 450

    const val TERMINAL_MS = 25L * 60 * 1000
    const val ONSET_MS = 6L * 60 * 1000

    /**
     * One point on the curve. Values between knots are linearly interpolated
     * in both dimensions.
     *
     * Derived from the B1 table by taking each band boundary once, resolving
     * a disagreement between the band that ends there and the band that
     * starts there in favour of the larger value.
     */
    private data class Knot(val atMs: Long, val stallMs: Int, val probability: Float)

    private fun minutes(m: Double): Long = (m * 60_000).toLong()

    private val KNOTS = listOf(
        // Nothing at all before six minutes.
        Knot(0L, 0, 0f),
        Knot(minutes(6.0), 0, 0f),
        // Onset. Duration is at the floor for the whole early range; only
        // probability separates these bands.
        Knot(minutes(6.0), 150, 0.10f),
        Knot(minutes(7.0), 150, 0.15f),
        Knot(minutes(8.0), 150, 0.20f),
        Knot(minutes(9.0), 350, 0.30f),
        Knot(minutes(10.0), 450, 0.40f),
        // 10-12 ends at 800/50, 12-14 starts at 700/60. Larger wins: 800/60.
        Knot(minutes(12.0), 800, 0.60f),
        // 12-14 ends at 1200/65, 14-18 starts at 1200/70. Larger wins.
        Knot(minutes(14.0), 1200, 0.70f),
        // 14-18 ends at 2700/90, 18-22 starts at 2500/90. Larger wins.
        Knot(minutes(18.0), 2700, 0.90f),
        // 18-22 ends at 4200/95, 22-25 starts at 4000/98. Larger wins.
        Knot(minutes(22.0), 4200, 0.98f),
        Knot(minutes(25.0), 5000, 1.00f),
    )

    /**
     * The curve at [accumulatedMs].
     *
     * @param floorMs no non-zero stall is commanded below this.
     */
    fun frictionAt(accumulatedMs: Long, floorMs: Int = DEFAULT_FLOOR_MS): FrictionPoint {
        if (accumulatedMs < ONSET_MS) return FrictionPoint(0, 0f)
        if (accumulatedMs >= TERMINAL_MS) {
            return FrictionPoint(clampFloor(5000, floorMs), 1.0f)
        }

        // The duplicate knot at six minutes makes the onset a step rather
        // than a ramp, so start from the second one.
        var lower = KNOTS[2]
        var upper = KNOTS[2]
        for (i in 2 until KNOTS.size) {
            if (KNOTS[i].atMs <= accumulatedMs) {
                lower = KNOTS[i]
                upper = KNOTS.getOrElse(i + 1) { KNOTS[i] }
            }
        }

        val span = upper.atMs - lower.atMs
        val t = if (span <= 0L) 0f else (accumulatedMs - lower.atMs).toFloat() / span

        val stall = lower.stallMs + ((upper.stallMs - lower.stallMs) * t).toInt()
        val probability = lower.probability + (upper.probability - lower.probability) * t

        return FrictionPoint(
            stallMs = clampFloor(stall, floorMs),
            probability = probability.coerceIn(0f, 1f),
        )
    }

    /**
     * Zero stays zero; anything else is raised to the floor.
     *
     * Zero has to survive the clamp or the pre-onset range would command a
     * 450 ms stall at 0% probability, which is harmless today and a trap the
     * moment anything reads `stallMs` without checking `probability`.
     */
    private fun clampFloor(stallMs: Int, floorMs: Int): Int =
        if (stallMs <= 0) 0 else maxOf(stallMs, floorMs)

    /**
     * Does this scroll stall?
     *
     * Bernoulli, per scroll event, from an injected source so the decision is
     * reproducible in tests. Deliberately plain: a minimum-gap rule would
     * change the felt texture and is not added without evidence that the raw
     * run lengths read as a freeze rather than a hitch.
     * `FrictionCurveTest` reports that distribution.
     */
    fun shouldStall(point: FrictionPoint, roll: Float): Boolean =
        point.stallMs > 0 && roll < point.probability
}
