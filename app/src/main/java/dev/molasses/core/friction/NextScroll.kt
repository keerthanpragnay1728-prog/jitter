package dev.molasses.core.friction

/**
 * What the next scroll costs, in the three shapes that answer is worth giving.
 *
 * ## Why a band and not a number
 * The gate's readout was going to print the live stall and probability. The
 * numbers say why that is decoration for most of the gates a user actually
 * sees.
 *
 * With the default horizon, onset is at ten minutes of accumulated time and
 * five minute leases put the first two gates of a cycle at 0:00 and 5:00. Both
 * read `0ms 0%`. The first gate of a cycle is by far the most seen, because
 * every cycle has exactly one and later gates need leases to have been taken,
 * and it is the gate where the answer would be most useful and says least. At
 * the other end, everything from the horizon on is pinned at the ceiling, and
 * a long session spends most of its gates there, so the readout freezes
 * exactly when it is looked at most.
 *
 * Measured against the real curve rather than reasoned about:
 *
 * ```
 * gate   accumulated   stall     probability
 * 1      0:00          0ms       0%
 * 2      5:00          0ms       0%
 * 3      10:00         450ms     10%
 * 4      15:00         866ms     61%
 * 5      20:00         2950ms    91%
 * 6+     25:00+        5000ms    100%   (pinned)
 * ```
 *
 * So there are three bands and only the middle one is a number worth printing.
 * Zero is not a reading, it is the absence of one, and a repeated ceiling is
 * not a reading either. Both deserve a phrase that says what is true, and a
 * phrase is also the only version that is honest at a glance: `0ms 0%` reads
 * as a broken readout rather than as "friction has not started".
 *
 * ## Probability first
 * On the ramp the stall sits at the floor for the whole ten to twelve minute
 * band while the probability moves from 10% to 60%. A readout showing
 * milliseconds alone would be flat across the stretch where the experience
 * changes most, so the probability leads and the duration follows it.
 *
 * ## One question, three answers
 * Every band answers "what does the next scroll cost". That framing is the
 * only one all three can answer: as a status line, [BeforeOnset] would have to
 * say something about the session, and it has nothing to say that the
 * accumulated total does not already show.
 *
 * ## The boundaries come from the curve, not from here
 * [readingAt] asks [FrictionCurve] for both the onset and the numbers. A band
 * selector with its own copy of the thresholds would be a second description
 * of the curve, and the second description is wrong the first time a knot
 * moves. The only thing this file decides is which of three things to say.
 *
 * Pure; no Android imports. The strings live in `strings.xml` and the mapping
 * from a reading to a `@StringRes` is at the call site, as CLAUDE.md requires.
 *
 * Unit-tested in `NextScrollTest`.
 */
object NextScroll {

    /**
     * Which of three things is true about the next scroll.
     *
     * A sealed hierarchy rather than a band enum plus nullable numbers,
     * because two of the three bands have no numbers at all and a nullable
     * pair would invite a call site to print `--` for a case that has a real
     * sentence to say instead.
     */
    sealed interface Reading {

        /**
         * Friction has not started on this app this cycle.
         *
         * True below the onset, which is 40% of the horizon: ten minutes on
         * the default. Worth saying rather than printing zeros, because it is
         * the one thing at this gate the user cannot work out from anything
         * else on the screen.
         */
        data object BeforeOnset : Reading

        /** On the ramp. The only band where the numbers move. */
        data class OnTheRamp(val stallMs: Int, val probability: Float) : Reading

        /**
         * At or past the horizon, where the curve is flat.
         *
         * Carries the numbers even though the copy does not print them: the
         * debug screen and any future readout can still want them, and a band
         * that threw them away would make this type lossy for no gain.
         *
         * Note [probability] is not always 1.0 here. A horizon wider than the
         * taper point brings the terminal probability down toward 0.70, so
         * copy that said "always" would be wrong on exactly the horizons a
         * heavy user picks.
         */
        data class Pinned(val stallMs: Int, val probability: Float) : Reading
    }

    /**
     * @param accumulatedMs true foreground time this cycle, as the ledger
     *   reports it.
     * @param penaltyMs the ratchet's addition for sitting past a lease. Added
     *   here rather than by the caller because [FrictionCurve.effectiveMs] is
     *   the one definition of what the curve is read against, and the engine
     *   reads it the same way.
     */
    fun readingAt(
        accumulatedMs: Long,
        penaltyMs: Long,
        horizonMs: Long,
        floorMs: Int = FrictionCurve.DEFAULT_FLOOR_MS,
    ): Reading {
        val effective = FrictionCurve.effectiveMs(accumulatedMs, penaltyMs)
        val horizon = FrictionCurve.clampHorizon(horizonMs)
        val point = FrictionCurve.frictionAt(effective, horizon, floorMs)
        return when {
            effective < FrictionCurve.onsetMs(horizon) -> Reading.BeforeOnset
            effective >= horizon -> Reading.Pinned(point.stallMs, point.probability)
            else -> Reading.OnTheRamp(point.stallMs, point.probability)
        }
    }

    /** Whole percent, for display. Rounded rather than truncated. */
    fun percent(probability: Float): Int =
        Math.round(probability * 100f).coerceIn(0, 100)
}
