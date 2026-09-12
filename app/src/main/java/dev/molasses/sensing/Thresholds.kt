package dev.molasses.sensing

/**
 * The seven-test battery's constants, in one place so a hardware sweep can
 * re-derive them without touching logic.
 *
 * ## Why there are two sets
 * `TYPE_LINEAR_ACCELERATION` is, on most devices, a software-fused sensor with
 * its own internal high-pass whose corner we neither control nor can query.
 * For identical physical motion its RMS will not match what the IIR path
 * produces. Sharing one RMS floor across both would mean one of the two paths
 * is running against thresholds that were never measured for it.
 *
 * So: two sets behind one interface, and [calibration] says which of them has
 * actually been validated.
 */
interface Thresholds {
    val id: String
    val calibration: Calibration

    /** Analysis window. */
    val windowMs: Long

    /** Minimum samples before any verdict other than WAITING_TO_START. */
    val minSamples: Int

    /** Peak-pick refractory period. */
    val refractoryMs: Long

    /** Ignore ripples below this when peak-picking. */
    val peakMinMagnitude: Double

    /** Dynamic RMS floor, m/s^2. Rejects thumb tremor. */
    val minRms: Double

    /** Peaks required inside [windowMs]. Rejects a single jerk. */
    val minPeaks: Int

    /**
     * Dominant cadence band, Hz.
     *
     * [minHz] carries a guard band below the *stated* 1.2 Hz product floor.
     * Measured cadence over a 3.5 s window is a 3-5 interval sample statistic:
     * a walker whose true cadence is exactly 1.20 Hz measures 1.19 Hz as often
     * as 1.21. A threshold placed at the value it is meant to admit rejects
     * half of those windows, and since the gate needs 8 *continuous* seconds,
     * a 1.2 Hz walker would never clear it.
     *
     * [maxHz] deliberately carries no guard band. The two edges have opposite
     * failure costs: the low edge exists to admit real users, so it errs
     * toward admitting; the high edge exists to exclude shaking, so it errs
     * toward excluding.
     */
    val minHz: Double
    val maxHz: Double

    /**
     * Two-sided coefficient of variation on peak intervals.
     *
     * Below [minCv] is *too* regular: human gait measures ~0.03-0.10 even for
     * a metronomic walker, so a CV of 0.01 is a machine, a metronome, or a
     * thumb on a desk. Above [maxCv] is erratic wrist-flicking.
     */
    val minCv: Double
    val maxCv: Double

    /**
     * Baseline over which the [minCv] floor is measured -- deliberately much
     * longer than [windowMs], which the [maxCv] ceiling uses.
     *
     * The two bounds are not symmetric and cannot share a window.
     *
     * The ceiling asks "is this erratic right now", so it must be responsive,
     * and an occasional over-estimate only rejects someone already shaking.
     *
     * The floor asks "has this been machine-regular throughout", and it is
     * statistically hostile on a short window: small-sample spread estimates
     * come out spuriously small far more often than spuriously large. Measured
     * over 100k trials for a walker with a true CV of 0.06, the Bessel-corrected
     * estimate falls under 0.02 in 10.6% of windows at 3 intervals, 4.6% at 4,
     * 2.0% at 5 and 0.2% at 8.
     *
     * Those per-window rates compound catastrophically against the gate's
     * 8-second *continuous* sustain: at 100 Hz a sustain period spans ~800
     * overlapping windows, so even a 2% per-window false rate makes the gate
     * effectively unclearable. Measured before this was widened, a jittered
     * 1.8 Hz walk at 100 Hz had its streak broken at t=3.15 s and again at
     * t=11.6 s and never passed, while the identical signal at 50 Hz passed --
     * the difference being only that coarser timestamp quantisation added
     * enough noise to keep the estimate off the floor.
     *
     * A 12 s baseline gives ~20 intervals at 1.8 Hz, where the estimate is
     * stable, and still engages (see [minCvIntervals]) well before an 8 s
     * sustain could complete.
     */
    val regularityWindowMs: Long

    /**
     * Intervals required in [regularityWindowMs] before the [minCv] floor is
     * evaluated at all. Below this the floor abstains rather than guessing.
     */
    val minCvIntervals: Int

    /** Vertical share of total energy. Rejects lateral hand-waving. */
    val minVerticalShare: Double

    /** Peak |a| ceiling, m/s^2. Rejects violent shaking. */
    val maxPeakMagnitude: Double

    /** Gravity-vector angle change vs gate start, degrees, and its sustain. */
    val minTiltDegrees: Double
    val minTiltSustainMs: Long

    enum class Calibration {
        /** Measured against the synthetic sweep in `CalibrationSweep`. */
        SYNTHETIC_SWEEP,

        /**
         * Seeded from another path's values and never measured for this one.
         * Surfaced in the debug screen so an unvalidated path is not mistaken
         * for a validated one.
         */
        UNCALIBRATED,
    }

    companion object {
        /**
         * Accelerometer + [GravitySplitter]. Values re-derived in
         * `CalibrationSweep` after the gravity time constant moved from 80 ms
         * to 650 ms; see README "Threshold recalibration".
         */
        val IIR: Thresholds = object : Thresholds {
            override val id = "IIR"
            override val calibration = Calibration.SYNTHETIC_SWEEP
            override val windowMs = 3_500L
            override val minSamples = 40
            override val refractoryMs = 250L
            override val peakMinMagnitude = 0.8
            override val minRms = 1.5
            override val minPeaks = 4
            override val minHz = 1.15
            override val maxHz = 2.6
            override val minCv = 0.02
            override val maxCv = 0.35
            override val regularityWindowMs = 12_000L
            override val minCvIntervals = 8
            override val minVerticalShare = 0.50
            override val maxPeakMagnitude = 25.0
            override val minTiltDegrees = 25.0
            override val minTiltSustainMs = 1_000L
        }

        /**
         * `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY`.
         *
         * Seeded from [IIR] and deliberately marked [Calibration.UNCALIBRATED]:
         * no hardware sweep exists for it, and the fused sensor's undocumented
         * internal high-pass means these numbers are a starting point, not a
         * measurement. Re-run `CalibrationSweep` against real devices before
         * promoting this to SYNTHETIC_SWEEP.
         */
        val FUSED: Thresholds = object : Thresholds {
            override val id = "FUSED"
            override val calibration = Calibration.UNCALIBRATED
            override val windowMs = IIR.windowMs
            override val minSamples = IIR.minSamples
            override val refractoryMs = IIR.refractoryMs
            override val peakMinMagnitude = IIR.peakMinMagnitude
            override val minRms = IIR.minRms
            override val minPeaks = IIR.minPeaks
            override val minHz = IIR.minHz
            override val maxHz = IIR.maxHz
            override val minCv = IIR.minCv
            override val maxCv = IIR.maxCv
            override val regularityWindowMs = IIR.regularityWindowMs
            override val minCvIntervals = IIR.minCvIntervals
            override val minVerticalShare = IIR.minVerticalShare
            override val maxPeakMagnitude = IIR.maxPeakMagnitude
            override val minTiltDegrees = IIR.minTiltDegrees
            override val minTiltSustainMs = IIR.minTiltSustainMs
        }
    }
}
