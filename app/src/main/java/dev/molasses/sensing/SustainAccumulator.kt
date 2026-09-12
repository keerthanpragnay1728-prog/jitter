package dev.molasses.sensing

/**
 * Leaky bucket replacing the continuous sustain streak. Pure.
 *
 * The streak required all seven tests to hold on every window for 8 continuous
 * seconds. That is the same defect already found and fixed for the CV floor,
 * and it applied to the other six tests just as hard: a sustain period spans
 * hundreds of overlapping windows, so any per-window false-fail rate compounds
 * into near-certain failure. On the first device build the ring reached 100%
 * and then reset with CADENCE_TOO_SLOW, which is that defect in the field.
 *
 * A bucket forgives a transient failure instead of erasing everything. A
 * passing tick adds its own duration. A failing tick removes half of it. A
 * stumble costs progress in proportion to its length rather than costing the
 * whole session.
 *
 * The asymmetry matters. Credit accrues at 1x and drains at 0.5x, so sustained
 * failure still drains the bucket and nothing can be cleared by alternating
 * pass and fail. At a 50% pass rate the bucket gains 0.25x per tick, which
 * clears 8 s in 64 s. Random chatter is tolerated. Standing still is not.
 */
class SustainAccumulator(
    /** Net credit needed to pass, ms. */
    val requiredMs: Long = DEFAULT_REQUIRED_MS,
    /** Fraction of a tick's duration removed by a failing tick. */
    val decayFactor: Double = DEFAULT_DECAY_FACTOR,
) {
    var creditMs: Double = 0.0
        private set

    var ticks: Int = 0
        private set

    val fraction: Float
        get() = (creditMs / requiredMs).coerceIn(0.0, 1.0).toFloat()

    val passed: Boolean
        get() = creditMs >= requiredMs

    fun reset() {
        creditMs = 0.0
        ticks = 0
    }

    /**
     * Advance one evaluation tick.
     *
     * @param passing whether every test held on this tick.
     * @param dtMs the tick's own duration. Passed in rather than assumed so
     *   that a late or coalesced tick contributes what it is actually worth,
     *   which is what keeps the result independent of sensor delivery rate.
     * @return true once the bucket has reached [requiredMs].
     */
    fun tick(passing: Boolean, dtMs: Long): Boolean {
        ticks++
        creditMs = if (passing) {
            (creditMs + dtMs).coerceAtMost(requiredMs.toDouble())
        } else {
            (creditMs - dtMs * decayFactor).coerceAtLeast(0.0)
        }
        return passed
    }

    companion object {
        const val DEFAULT_REQUIRED_MS = 8_000L
        const val DEFAULT_DECAY_FACTOR = 0.5
    }
}
