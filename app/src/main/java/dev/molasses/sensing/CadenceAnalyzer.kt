package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import dev.molasses.core.stats.Cv
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The seven-test battery. Pure -- no Android imports, so every threshold is
 * exercised against synthetic signals rather than by walking around holding a
 * phone.
 *
 * ## Input contract
 * This class does not know where its numbers come from. It is fed *already
 * separated* linear acceleration and gravity on every sample, because the two
 * supported pipelines separate them very differently:
 *
 * - **Fused**: `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` straight from the
 *   platform's sensor fusion.
 * - **IIR**: raw `TYPE_ACCELEROMETER` through [GravitySplitter].
 *
 * Those are two calibration domains, not one pipeline with two front ends,
 * which is why [thresholds] is injected rather than read from a companion.
 *
 * ## The battery
 * All seven must hold simultaneously. Each rejects a specific cheat, which is
 * why none can be dropped and why they are an AND rather than a score:
 *
 * | test                    | rejects                              |
 * |-------------------------|--------------------------------------|
 * | RMS of \|a\|            | thumb tremor                         |
 * | peak count in window    | a single jerk                        |
 * | dominant interval freq  | shaking faster than walking          |
 * | CV of peak intervals    | erratic flicking (high) AND a metronome or desk-tap (low) |
 * | vertical energy share   | lateral hand-waving                  |
 * | peak \|a\|              | violent shaking to brute-force       |
 * | gravity-angle change    | a phone lying still, being tapped    |
 */
class CadenceAnalyzer(
    val thresholds: Thresholds = Thresholds.IIR,
) {
    private data class Sample(val tMs: Long, val magnitude: Double, val vert: Double)

    private val samples = ArrayDeque<Sample>()
    private val peakTimesMs = ArrayDeque<Long>()

    /**
     * Candidate peaks that cleared [Thresholds.peakMinMagnitude] and were
     * *comparable in amplitude* to the accepted peak before them, but arrived
     * inside the refractory window. See the aliasing guard in [verdict].
     *
     * The amplitude condition is what makes this measurable rather than noisy.
     * Measured on a 1.8 Hz walk through the IIR path, each heel strike leaves a
     * filter-ringing peak 80 ms later at 19% of the main peak's magnitude; an
     * aliased 4.5 Hz shake produces candidates at 220 ms and ~95%. Counting
     * every suppressed candidate would flag real gait at a ratio of 0.86.
     */
    private val suppressedPeakMs = ArrayDeque<Long>()

    private var lastAcceptedPeakMagnitude = 0.0

    /**
     * Accepted peaks over [Thresholds.regularityWindowMs] rather than the
     * analysis window. Used only by the CV *floor* -- see that threshold for
     * why the floor needs a longer baseline than the ceiling.
     */
    private val regularityPeakMs = ArrayDeque<Long>()

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var haveGravity = false

    private var referenceGx = 0.0
    private var referenceGy = 0.0
    private var referenceGz = 0.0
    private var haveReference = false

    private var lastPeakMs = Long.MIN_VALUE
    private var prevMagnitude = 0.0
    private var risingMagnitude = 0.0
    private var rising = false

    /** Start of the sustained tilt-change condition, or null. */
    private var tiltSinceMs: Long? = null

    fun reset() {
        samples.clear()
        peakTimesMs.clear()
        suppressedPeakMs.clear()
        regularityPeakMs.clear()
        haveGravity = false
        haveReference = false
        lastPeakMs = Long.MIN_VALUE
        prevMagnitude = 0.0
        rising = false
        risingMagnitude = 0.0
        lastAcceptedPeakMagnitude = 0.0
        tiltSinceMs = null
    }

    /** Re-anchor the tilt reference, e.g. when a gate opens. */
    fun anchorOrientation() {
        haveReference = false
        tiltSinceMs = null
    }

    /**
     * Feed one sample.
     *
     * @param tMs monotonic sample time, ms.
     * @param ax/ay/az linear acceleration (gravity already removed), m/s^2.
     * @param gxIn/gyIn/gzIn the gravity vector at this instant, m/s^2.
     */
    fun onSample(
        tMs: Long,
        ax: Double,
        ay: Double,
        az: Double,
        gxIn: Double,
        gyIn: Double,
        gzIn: Double,
    ): Verdict {
        gx = gxIn; gy = gyIn; gz = gzIn
        haveGravity = true
        if (!haveReference) {
            referenceGx = gx; referenceGy = gy; referenceGz = gz
            haveReference = true
        }

        val mag = sqrt(ax * ax + ay * ay + az * az)

        // Project onto the gravity unit vector for the vertical component; the
        // orthogonal residual is lateral.
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        val vert = if (gNorm > 1e-6) (ax * gx + ay * gy + az * gz) / gNorm else 0.0

        samples.addLast(Sample(tMs, mag, vert))
        while (samples.isNotEmpty() && tMs - samples.first().tMs > thresholds.windowMs) {
            samples.removeFirst()
        }

        // Peak-pick with a refractory period. A local maximum is confirmed on
        // the first downward step after a rise, not on the sample itself, so a
        // monotonic ramp never registers.
        if (mag > prevMagnitude) {
            rising = true
            risingMagnitude = mag
        } else if (rising && mag < prevMagnitude) {
            rising = false
            if (risingMagnitude >= thresholds.peakMinMagnitude) {
                if (lastPeakMs == Long.MIN_VALUE || tMs - lastPeakMs >= thresholds.refractoryMs) {
                    lastPeakMs = tMs
                    lastAcceptedPeakMagnitude = risingMagnitude
                    peakTimesMs.addLast(tMs)
                    regularityPeakMs.addLast(tMs)
                } else if (
                    risingMagnitude >= SUPPRESSED_SIGNIFICANCE * lastAcceptedPeakMagnitude
                ) {
                    // A full-amplitude event dropped only because it arrived
                    // too soon: the refractory window is decimating real
                    // motion rather than de-duplicating one heel strike.
                    suppressedPeakMs.addLast(tMs)
                }
            }
        }
        prevMagnitude = mag
        while (peakTimesMs.isNotEmpty() && tMs - peakTimesMs.first() > thresholds.windowMs) {
            peakTimesMs.removeFirst()
        }
        while (suppressedPeakMs.isNotEmpty() &&
            tMs - suppressedPeakMs.first() > thresholds.windowMs
        ) {
            suppressedPeakMs.removeFirst()
        }
        while (regularityPeakMs.isNotEmpty() &&
            tMs - regularityPeakMs.first() > thresholds.regularityWindowMs
        ) {
            regularityPeakMs.removeFirst()
        }

        val tilt = tiltDegrees()
        tiltSinceMs = if (tilt > thresholds.minTiltDegrees) (tiltSinceMs ?: tMs) else null

        return verdict(tMs)
    }

    /** Result of the battery at one instant. */
    data class Verdict(
        val ok: Boolean,
        val reason: GateProgress.Reason,
        val rms: Double,
        val peaks: Int,
        val dominantHz: Double,
        val cv: Double,
        val verticalShare: Double,
        val peakMagnitude: Double,
        val tiltDegrees: Double,
        val tiltSustainedMs: Long,
        /** CV over [Thresholds.regularityWindowMs]; NaN until enough intervals. */
        val regularityCv: Double,
        val regularityIntervals: Int,
        /** Peaks dropped by the refractory window. See the aliasing guard. */
        val suppressedPeaks: Int,
        val thresholdsId: String,
    ) {
        /** Every test that is currently failing, not just the reported one. */
        val failures: Set<GateProgress.Reason>
            get() = buildSet {
                if (!ok) add(reason)
                addAll(allFailures)
            }

        internal var allFailures: Set<GateProgress.Reason> = emptySet()
    }

    fun verdict(nowMs: Long): Verdict {
        val n = samples.size
        if (n < thresholds.minSamples) {
            return Verdict(
                false, GateProgress.Reason.WAITING_TO_START,
                0.0, peakTimesMs.size, 0.0, Double.NaN, 0.0, 0.0,
                tiltDegrees(), 0, Double.NaN, 0, suppressedPeakMs.size, thresholds.id,
            )
        }

        var sumSq = 0.0
        var sumVertSq = 0.0
        var peakMag = 0.0
        for (s in samples) {
            sumSq += s.magnitude * s.magnitude
            sumVertSq += s.vert * s.vert
            if (s.magnitude > peakMag) peakMag = s.magnitude
        }
        val rms = sqrt(sumSq / n)
        val verticalShare = if (sumSq > 1e-9) sumVertSq / sumSq else 0.0

        val peaks = peakTimesMs.size
        val times = peakTimesMs.toList()
        var cv = Double.NaN
        var dominantHz = 0.0
        if (times.size >= 2) {
            val intervals = DoubleArray(times.size - 1) {
                (times[it + 1] - times[it]).toDouble()
            }
            val mean = intervals.average()
            // Bessel-corrected: at the MIN_PEAKS floor there are only 3
            // intervals, and the population estimator's ~18% downward bias
            // there would drag real walkers into the TOO_REGULAR floor.
            cv = Cv.sample(intervals)
            dominantHz = if (mean > 0) 1000.0 / mean else 0.0
        }

        val tilt = tiltDegrees()
        val tiltMs = tiltSinceMs?.let { nowMs - it } ?: 0L

        // Aliasing guard.
        //
        // The refractory window is a hard decimator: any motion faster than
        // 1/refractory (4 Hz at 250 ms) has peaks dropped, and what survives
        // can land anywhere -- including the middle of the pass band. Measured
        // on the fused path, a 4.5 Hz shake with human-like jitter aliases to
        // 2.17 Hz with CV 0.035 and passes every other test. The IIR path
        // happens to resist this because its filter perturbs peak timing, but
        // that is luck, not design.
        //
        // Discarded peaks are the evidence: real walking at 1.2-2.6 Hz has
        // intervals of 380-830 ms and suppresses essentially none, whereas
        // aliased shaking suppresses roughly as many as it keeps.
        // Long-baseline regularity, for the floor only.
        val regularityTimes = regularityPeakMs.toList()
        val regularityIntervals = regularityTimes.size - 1
        val regularityCv = if (regularityIntervals >= 2) {
            Cv.sample(
                DoubleArray(regularityIntervals) {
                    (regularityTimes[it + 1] - regularityTimes[it]).toDouble()
                },
            )
        } else {
            Double.NaN
        }

        val suppressed = suppressedPeakMs.size
        val aliased = peaks >= 2 && suppressed > peaks * MAX_SUPPRESSED_RATIO

        // Collect every failing test, not only the first. MovementRejectionTest
        // asserts that desk-tapping trips two independent tests, which is only
        // checkable if the verdict reports more than the headline reason.
        val failing = buildSet {
            if (peakMag >= thresholds.maxPeakMagnitude) add(GateProgress.Reason.TOO_VIOLENT)
            if (times.size >= 2 && dominantHz > thresholds.maxHz) {
                add(GateProgress.Reason.CADENCE_TOO_FAST)
            }
            if (aliased) add(GateProgress.Reason.CADENCE_TOO_FAST)
            if (rms <= thresholds.minRms) add(GateProgress.Reason.NOT_ENOUGH_MOTION)
            if (tilt <= thresholds.minTiltDegrees || tiltMs < thresholds.minTiltSustainMs) {
                add(GateProgress.Reason.PHONE_STATIONARY)
            }
            if (verticalShare < thresholds.minVerticalShare) {
                add(GateProgress.Reason.MOTION_NOT_VERTICAL)
            }
            if (peaks < thresholds.minPeaks) add(GateProgress.Reason.NEED_MORE_STEPS)
            if (times.size >= 2 && dominantHz < thresholds.minHz) {
                add(GateProgress.Reason.CADENCE_TOO_SLOW)
            }
            if (!cv.isNaN() && cv > thresholds.maxCv) add(GateProgress.Reason.CADENCE_IRREGULAR)
            if (!regularityCv.isNaN() &&
                regularityIntervals >= thresholds.minCvIntervals &&
                regularityCv < thresholds.minCv
            ) {
                // Human gait measures CV ~0.03-0.10 even for a metronomic
                // walker. Below 0.02 is a machine, a metronome, or a thumb
                // tapping a stationary phone.
                //
                // Gated on interval count: see Thresholds.minCvIntervals for
                // why evaluating this on 3 intervals rejects one window in ten
                // from a real walker.
                add(GateProgress.Reason.TOO_REGULAR)
            }
        }

        // Report the most diagnostic failure. Disqualifying cheats are named
        // before "keep going" conditions so the gate tells a cheater why --
        // both fairer and a better deterrent than a stuck progress ring.
        val reason = REASON_PRIORITY.firstOrNull { it in failing }
            ?: GateProgress.Reason.PASSED

        return Verdict(
            ok = failing.isEmpty(),
            reason = reason,
            rms = rms,
            peaks = peaks,
            dominantHz = dominantHz,
            cv = cv,
            verticalShare = verticalShare,
            peakMagnitude = peakMag,
            tiltDegrees = tilt,
            tiltSustainedMs = tiltMs,
            regularityCv = regularityCv,
            regularityIntervals = regularityIntervals,
            suppressedPeaks = suppressed,
            thresholdsId = thresholds.id,
        ).also { it.allFailures = failing }
    }

    private fun tiltDegrees(): Double {
        if (!haveReference || !haveGravity) return 0.0
        return angleBetweenDegrees(gx, gy, gz, referenceGx, referenceGy, referenceGz)
    }

    companion object {
        /**
         * Suppressed-to-accepted peak ratio above which the motion is judged
         * faster than the refractory window can represent. Derived in
         * `CalibrationSweep`: real gait sits at 0.00, aliased shaking at 0.6+.
         */
        const val MAX_SUPPRESSED_RATIO = 0.30

        /**
         * Fraction of the preceding accepted peak's magnitude a suppressed
         * candidate must reach before it counts as a decimated real event
         * rather than filter ringing. Measured separation is 0.19 (gait
         * ringing) against 0.95 (aliased shake), so 0.5 sits in a wide gap.
         */
        const val SUPPRESSED_SIGNIFICANCE = 0.5

        /** Most diagnostic first. */
        private val REASON_PRIORITY = listOf(
            GateProgress.Reason.TOO_VIOLENT,
            GateProgress.Reason.CADENCE_TOO_FAST,
            GateProgress.Reason.NOT_ENOUGH_MOTION,
            GateProgress.Reason.PHONE_STATIONARY,
            GateProgress.Reason.MOTION_NOT_VERTICAL,
            GateProgress.Reason.TOO_REGULAR,
            GateProgress.Reason.NEED_MORE_STEPS,
            GateProgress.Reason.CADENCE_TOO_SLOW,
            GateProgress.Reason.CADENCE_IRREGULAR,
        )

        /** Exposed for tests that build synthetic gravity vectors. */
        fun angleBetweenDegrees(
            ax: Double, ay: Double, az: Double,
            bx: Double, by: Double, bz: Double,
        ): Double {
            val aN = sqrt(ax * ax + ay * ay + az * az)
            val bN = sqrt(bx * bx + by * by + bz * bz)
            if (aN < 1e-6 || bN < 1e-6) return 0.0
            val dot = (ax * bx + ay * by + az * bz) / (aN * bN)
            return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
        }
    }
}
