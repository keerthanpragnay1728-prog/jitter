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
 * The 2D friction curve, scaled to a declared session horizon.
 *
 * ## Why a horizon at all
 * The curve used to terminate at a fixed twenty five minutes with five second
 * stalls at 100% probability, for every app and every user. That is not a
 * degraded phone, it is a phone you cannot use, and it made a one hour lecture
 * or a long research session impossible rather than expensive. The two numbers
 * were picked without reference to either the app or the person.
 *
 * The fix is to scale the curve to a horizon the user declares per app, not to
 * loosen it. A horizon is a statement about what a session in this app is for.
 * It is **not** a reset and nothing about it refunds friction: someone forty
 * minutes into a sixty minute horizon enters the curve at minute forty, deep
 * in the ramp, exactly as they would have at any other horizon.
 *
 * ## One rule for the shape
 * ```
 * onset    = horizon * 0.40
 * terminal = horizon
 * ```
 * Derived, with no free parameters, the same way the mood midpoint is. A
 * per-horizon table would be three numbers nobody could defend individually.
 *
 * ## The ceiling tapers, and this is the part that fixes the complaint
 * Stretching the timeline alone moves the cliff without removing it: minute
 * fifty five of a sixty minute lecture would still be five second stalls at
 * 100%, which is unwatchable. So the terminal ceiling comes down as the
 * horizon grows, linearly and clamped at both ends, between
 * [TAPER_FROM_HORIZON_MS] and [TAPER_TO_HORIZON_MS].
 *
 * At or below the default horizon the ceiling is exactly what it has always
 * been, so nothing about declaring an ordinary session changes what it costs.
 * At sixty minutes it is 70% and three seconds: heavy, constant,
 * clearly degraded, and still usable for the thing the user said they were
 * doing. A longer horizon therefore accumulates **more friction in total** and
 * is **never more severe at any instant**, which is the property worth
 * stating, because the reverse would make a long horizon strictly worse to
 * declare than to lie about.
 *
 * ## Why the taper scales the whole curve and not only the last knot
 * Because tapering the terminal alone breaks monotonicity. The knot at 16/19
 * of the ramp is 4200 ms at 98%; leave it alone and taper the terminal to
 * 3000 ms at 70% and the curve steps *down* over the last sixth, which is
 * friction being refunded and is the one thing this file may never do.
 *
 * So both dimensions are scaled by the ratio the terminal moved. The shape is
 * preserved exactly, monotonicity falls out of multiplying a non-decreasing
 * sequence by a positive constant, and the terminal lands on the tapered
 * values by construction rather than by agreement.
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
 * The source table's low bands ask for durations below the floor. Those are
 * clamped up, which flattens the early range to a constant duration where only
 * probability moves. That is not a defect, it is the design falling out of the
 * arithmetic, and the taper makes it wider at long horizons rather than
 * introducing anything new.
 *
 * ## Monotonicity
 * Both dimensions are non-decreasing in `accumulatedMs`, asserted by a sweep
 * test at one second granularity over the whole range, at three horizons.
 *
 * The source table is **not** monotonic at its band edges: the 8 to 9 minute
 * band ended at 350 ms and the 9 to 10 band started at 300 ms, and the same
 * dips appeared at 12, 14 and 18 minutes in one dimension or the other. Read
 * literally it would refund friction at four points. [KNOTS] resolves each
 * boundary to the larger of the two neighbouring values, so the curve is
 * continuous and never steps down. That is the same principle as
 * `MonotonicInt` on `tierIndex`, applied to a continuous function.
 *
 * The one discontinuity that survives is at the onset, where both dimensions
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

    // -------------------------------------------------------------- horizon

    /**
     * What a newly tracked app gets.
     *
     * Twenty five minutes, because that is where the terminal has always been
     * and where the measurements were taken. The segment D floor and the mood
     * boundaries were both calibrated against a 25 minute saturation, and a
     * default that saturated seven minutes earlier would be shipping a shape
     * nobody has run on hardware while claiming it was the conservative
     * choice.
     *
     * It is not the old curve renamed. See [ONSET_MS]: the onset moves from
     * six minutes to ten, because the one shape rule cannot reproduce the
     * 0.24 ratio the old pair implied at any horizon. The terminal is the half
     * the measurements are about, so that is the half that is held.
     *
     * Tracking an app is not a decision about how long you use it, so the
     * default has to be the ordinary case rather than the generous one, and
     * widening past it is deliberate in three separate ways: a trip to
     * settings, a confirmation echo, and a wait for the next cycle.
     */
    const val DEFAULT_HORIZON_MS = 25L * 60 * 1000

    /**
     * Below this the onset is under four minutes and the floor clamp collapses
     * the early bands into a single constant, so the curve stops having a
     * shape to speak of.
     */
    const val MIN_HORIZON_MS = 10L * 60 * 1000

    /** Above this the taper is clamped anyway and the curve stops meaning much. */
    const val MAX_HORIZON_MS = 60L * 60 * 1000

    /** Onset as a share of the horizon. The whole shape rule. */
    const val ONSET_FRACTION = 0.40

    /** A horizon outside the offered range is clamped, never rejected. */
    fun clampHorizon(horizonMs: Long): Long =
        horizonMs.coerceIn(MIN_HORIZON_MS, MAX_HORIZON_MS)

    /** Where friction starts for this horizon. */
    fun onsetMs(horizonMs: Long): Long =
        (clampHorizon(horizonMs) * ONSET_FRACTION).toLong()

    /** Where it saturates. The horizon itself, clamped. */
    fun terminalMs(horizonMs: Long): Long = clampHorizon(horizonMs)

    /**
     * The time the curve is actually read against: true time plus the
     * ratchet's penalty.
     *
     * One definition rather than two. The engine computes this on every
     * scroll and [dev.molasses.core.friction.NextScroll] has to compute the
     * same thing to say what that scroll will cost, and a readout that
     * disagreed with the engine by a term would be worse than no readout: it
     * would be a confident wrong number on the one screen whose point is
     * honest ones.
     *
     * Floored at zero. Both inputs are monotonic by construction, so a
     * negative sum can only be a corrupt read.
     */
    fun effectiveMs(accumulatedMs: Long, penaltyMs: Long): Long =
        (accumulatedMs + penaltyMs).coerceAtLeast(0L)

    /**
     * Past the horizon: friction is at its ceiling. The one definition of
     * "terminal". `FrictionEngine.isTerminal`, the walking gate's label and
     * the ledger all read it through [HorizonReading], so none of them can
     * disagree with the others.
     */
    fun isTerminal(accumulatedMs: Long, penaltyMs: Long, horizonMs: Long): Boolean =
        effectiveMs(accumulatedMs, penaltyMs) >= terminalMs(horizonMs)

    /**
     * The onset and terminal at the default horizon.
     *
     * Kept because several things want a boundary when no app is in hand, and
     * because it is worth writing down what the default actually is: ten
     * minutes to twenty five, against the six to twenty five this file carried
     * before the horizon existed. The terminal is unchanged and the onset is
     * four minutes later, which is the whole of the behavioural change at the
     * default. The 0.24 ratio the old pair implied is not reachable at any
     * horizon under one shape rule, so a later onset is the price of not
     * having a per horizon table.
     */
    val ONSET_MS: Long = onsetMs(DEFAULT_HORIZON_MS)
    val TERMINAL_MS: Long = terminalMs(DEFAULT_HORIZON_MS)

    // ---------------------------------------------------------------- taper

    /**
     * At or below this horizon the ceiling is untapered.
     *
     * The default, so the default sits exactly on the calibrated ceiling and
     * the taper is confined to its actual job: softening horizons longer than
     * the one a session is assumed to be. It started at twenty minutes, which
     * put the default an eighth of the way in and took 250ms and four points
     * of probability off a ceiling that had been measured on hardware. That
     * was an accident of two numbers written at different times rather than a
     * choice, so it is tied to the default now and moves with it.
     */
    const val TAPER_FROM_HORIZON_MS = DEFAULT_HORIZON_MS

    /** At or above this horizon the taper is at full extent. */
    const val TAPER_TO_HORIZON_MS = 60L * 60 * 1000

    const val TERMINAL_STALL_MS = 5000
    const val TERMINAL_STALL_TAPERED_MS = 3000
    const val TERMINAL_PROBABILITY = 1.00f
    const val TERMINAL_PROBABILITY_TAPERED = 0.70f

    /** 0.0 at [TAPER_FROM_HORIZON_MS], 1.0 at [TAPER_TO_HORIZON_MS]. */
    private fun taper(horizonMs: Long): Float {
        val span = (TAPER_TO_HORIZON_MS - TAPER_FROM_HORIZON_MS).toFloat()
        val over = (clampHorizon(horizonMs) - TAPER_FROM_HORIZON_MS).toFloat()
        return (over / span).coerceIn(0f, 1f)
    }

    /** The stall this horizon saturates at. */
    fun terminalStallMs(horizonMs: Long): Int {
        val t = taper(horizonMs)
        return (TERMINAL_STALL_MS + (TERMINAL_STALL_TAPERED_MS - TERMINAL_STALL_MS) * t).toInt()
    }

    /** The probability this horizon saturates at. */
    fun terminalProbability(horizonMs: Long): Float {
        val t = taper(horizonMs)
        return TERMINAL_PROBABILITY +
            (TERMINAL_PROBABILITY_TAPERED - TERMINAL_PROBABILITY) * t
    }

    // ----------------------------------------------------------------- shape

    /**
     * One point on the curve, positioned as a fraction of the onset-to-terminal
     * ramp rather than at an absolute minute, which is what lets one shape
     * serve every horizon. Values between knots are linearly interpolated in
     * both dimensions.
     */
    private data class Knot(val fraction: Double, val stallMs: Int, val probability: Float)

    /**
     * The minute marks the shape was originally specified at, kept as the
     * derivation so the provenance of every fraction below stays readable.
     */
    private const val SOURCE_ONSET_MIN = 6.0
    private const val SOURCE_TERMINAL_MIN = 25.0

    private fun at(minute: Double): Double =
        (minute - SOURCE_ONSET_MIN) / (SOURCE_TERMINAL_MIN - SOURCE_ONSET_MIN)

    /**
     * Derived from the B1 table by taking each band boundary once, resolving a
     * disagreement between the band that ends there and the band that starts
     * there in favour of the larger value.
     *
     * The stall and probability columns are the values at the **untapered**
     * ceiling. [frictionAt] scales both by however far the terminal has come
     * down for the horizon it is asked about.
     */
    private val KNOTS = listOf(
        // Onset. Duration is at the floor for the whole early range; only
        // probability separates these bands.
        Knot(at(6.0), 150, 0.10f),
        Knot(at(7.0), 150, 0.15f),
        Knot(at(8.0), 150, 0.20f),
        Knot(at(9.0), 350, 0.30f),
        Knot(at(10.0), 450, 0.40f),
        // 10-12 ends at 800/50, 12-14 starts at 700/60. Larger wins: 800/60.
        Knot(at(12.0), 800, 0.60f),
        // 12-14 ends at 1200/65, 14-18 starts at 1200/70. Larger wins.
        Knot(at(14.0), 1200, 0.70f),
        // 14-18 ends at 2700/90, 18-22 starts at 2500/90. Larger wins.
        Knot(at(18.0), 2700, 0.90f),
        // 18-22 ends at 4200/95, 22-25 starts at 4000/98. Larger wins.
        Knot(at(22.0), 4200, 0.98f),
        Knot(at(25.0), TERMINAL_STALL_MS, TERMINAL_PROBABILITY),
    )

    /**
     * The curve at [accumulatedMs], for an app with this [horizonMs].
     *
     * [horizonMs] has no default on purpose. Friction is now a property of one
     * app's declared horizon, and a call site that forgot to say which app it
     * meant would silently get the default one's curve. Naming it is the same
     * discipline `ClockTamperClamp` applies to a clamp direction.
     *
     * @param floorMs no non-zero stall is commanded below this.
     */
    fun frictionAt(
        accumulatedMs: Long,
        horizonMs: Long,
        floorMs: Int = DEFAULT_FLOOR_MS,
    ): FrictionPoint {
        val horizon = clampHorizon(horizonMs)
        val onset = onsetMs(horizon)
        val topStall = terminalStallMs(horizon)
        val topProbability = terminalProbability(horizon)

        if (accumulatedMs < onset) return FrictionPoint(0, 0f)
        if (accumulatedMs >= horizon) {
            return FrictionPoint(clampFloor(topStall, floorMs), topProbability)
        }

        // How far along the ramp, which is the space the knots live in.
        val f = (accumulatedMs - onset).toDouble() / (horizon - onset).toDouble()

        var lower = KNOTS.first()
        var upper = KNOTS.first()
        for (i in KNOTS.indices) {
            if (KNOTS[i].fraction <= f) {
                lower = KNOTS[i]
                upper = KNOTS.getOrElse(i + 1) { KNOTS[i] }
            }
        }

        val span = upper.fraction - lower.fraction
        val t = if (span <= 0.0) 0.0 else (f - lower.fraction) / span

        val stall = lower.stallMs + (upper.stallMs - lower.stallMs) * t
        val probability = lower.probability + (upper.probability - lower.probability) * t

        // Scaled by however far this horizon brought the ceiling down. See the
        // class doc: scaling the whole shape is what keeps it monotonic.
        val stallScale = topStall.toDouble() / TERMINAL_STALL_MS
        val probabilityScale = topProbability.toDouble() / TERMINAL_PROBABILITY

        return FrictionPoint(
            stallMs = clampFloor((stall * stallScale).toInt(), floorMs),
            probability = (probability * probabilityScale).toFloat().coerceIn(0f, 1f),
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
