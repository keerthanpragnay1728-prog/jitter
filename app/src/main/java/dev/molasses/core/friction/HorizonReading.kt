package dev.molasses.core.friction

/**
 * One app's time against its horizon, as the screens show it.
 *
 * Replaces the tier readouts. A tier was a five minute rung of a ladder the
 * friction curve no longer follows, so "Tier 4, 20 minutes used" described
 * neither the time spent nor the friction in force. This carries the three
 * numbers the curve actually reads and derives everything shown from them.
 *
 * Pure; no Android imports. Unit-tested in `HorizonReadingTest`.
 */
data class HorizonReading(
    /** True foreground time this cycle. */
    val accumulatedMs: Long,
    /** Added for staying past a lease. Shown separately, never folded in. */
    val penaltyMs: Long,
    val horizonMs: Long,
) {
    /** What the curve is read against. */
    val effectiveMs: Long get() = FrictionCurve.effectiveMs(accumulatedMs, penaltyMs)

    /** Past the horizon. The same function `FrictionEngine.isTerminal` answers with. */
    val terminal: Boolean get() = FrictionCurve.isTerminal(accumulatedMs, penaltyMs, horizonMs)

    val usedMinutes: Long get() = accumulatedMs.coerceAtLeast(0L) / MINUTE_MS
    val penaltyMinutes: Long get() = penaltyMs.coerceAtLeast(0L) / MINUTE_MS
    val horizonMinutes: Long get() = FrictionCurve.terminalMs(horizonMs) / MINUTE_MS

    /**
     * Effective time as a whole percentage of the horizon, floored and
     * unbounded above: 140 means forty percent past it. 100 or more is
     * exactly [terminal].
     */
    val percentOfHorizon: Long get() = effectiveMs * 100 / FrictionCurve.terminalMs(horizonMs)

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

/**
 * The friction model at one horizon, as CFG describes it. Every number comes
 * from [FrictionCurve], so the description cannot drift from the curve.
 */
data class FrictionSummary(
    val horizonMinutes: Long,
    val onsetMinutes: Long,
    val onsetPercent: Int,
    val firstStallMs: Int,
    val firstProbabilityPercent: Int,
    val ceilingStallMs: Int,
    val ceilingProbabilityPercent: Int,
) {
    companion object {
        fun of(horizonMs: Long): FrictionSummary {
            val horizon = FrictionCurve.clampHorizon(horizonMs)
            val onset = FrictionCurve.onsetMs(horizon)
            val first = FrictionCurve.frictionAt(onset, horizon)
            val ceiling = FrictionCurve.frictionAt(FrictionCurve.terminalMs(horizon), horizon)
            return FrictionSummary(
                horizonMinutes = horizon / 60_000L,
                onsetMinutes = onset / 60_000L,
                onsetPercent = Math.round(FrictionCurve.ONSET_FRACTION * 100).toInt(),
                firstStallMs = first.stallMs,
                firstProbabilityPercent = Math.round(first.probability * 100),
                ceilingStallMs = ceiling.stallMs,
                ceilingProbabilityPercent = Math.round(ceiling.probability * 100),
            )
        }
    }
}
