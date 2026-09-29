package dev.molasses.sensing

import dev.molasses.core.model.GateProgress

/**
 * The IMU movement gate: pipeline selection, the seven-test battery on a fixed
 * tick, a leaky-bucket accumulator, and the optional barometer shortcut. Pure,
 * unit-tested in `FallbackImuGateTest`.
 *
 * ## Two pipelines, one gate
 * [Mode.FUSED] takes `TYPE_LINEAR_ACCELERATION` and `TYPE_GRAVITY` straight
 * from the platform. [Mode.IIR] takes raw `TYPE_ACCELEROMETER` and separates
 * the two with [GravitySplitter]. Each carries its own [Thresholds]; see that
 * interface for why they must not be shared.
 *
 * The fused path needs both fused sensors. `TYPE_LINEAR_ACCELERATION` alone is
 * not enough: the vertical-energy-share and gravity-angle tests both need a
 * gravity vector, and re-deriving one would give back the filter problem this
 * path exists to avoid. [MovementDetector] falls back to [Mode.IIR] when
 * `TYPE_GRAVITY` is missing.
 *
 * ## Fixed tick, not per sample
 * Samples feed the analyzer continuously, but the battery is only evaluated
 * every [Thresholds.tickIntervalMs]. Evaluating per sample made the outcome
 * depend on delivery rate, which is the OEM's choice and not ours. The tick is
 * driven off sample timestamps rather than wall time, so it stays testable and
 * stays correct when delivery stalls.
 *
 * ## Bucket, not streak
 * A passing tick adds its duration, a failing tick removes half of it. See
 * [SustainAccumulator] for why a continuous streak could not work.
 *
 * The barometer is a shortcut, never a requirement: a monotonic drop of at
 * least 0.04 hPa inside 6 s (about 0.35 m, standing up) halves the credit
 * required. Most budget devices have no barometer, so requiring one would
 * exclude a large share of the target hardware.
 */
class FallbackImuGate(
    val mode: Mode = Mode.IIR,
    val thresholds: Thresholds =
        if (mode == Mode.FUSED) Thresholds.FUSED else Thresholds.IIR,
    private val analyzer: CadenceAnalyzer = CadenceAnalyzer(thresholds),
    private val splitter: GravitySplitter = GravitySplitter(),
) {
    enum class Mode { FUSED, IIR }

    private val accumulator = SustainAccumulator(
        requiredMs = thresholds.sustainRequiredMs,
        decayFactor = thresholds.sustainDecayFactor,
    )
    private val hzGate = HysteresisGate(thresholds.minHz, thresholds.minHzExit)
    private val rmsGate = HysteresisGate(thresholds.minRms, thresholds.minRmsExit)

    private var lastTickMs = Long.MIN_VALUE
    private var barometerShortcut = false
    private val pressureSamples = ArrayDeque<Pair<Long, Double>>()

    val path: GateProgress.Path
        get() = if (mode == Mode.FUSED) GateProgress.Path.IMU_FUSED else GateProgress.Path.IMU_IIR

    /** Diagnostic: alpha realised on the last IIR sample. NaN in fused mode. */
    val lastAlpha: Double
        get() = if (mode == Mode.IIR) splitter.lastAlpha else Double.NaN

    val creditMs: Long get() = accumulator.creditMs.toLong()
    val ticks: Int get() = accumulator.ticks

    fun reset() {
        analyzer.reset()
        analyzer.anchorOrientation()
        splitter.reset()
        accumulator.reset()
        hzGate.reset()
        rmsGate.reset()
        lastTickMs = Long.MIN_VALUE
        barometerShortcut = false
        pressureSamples.clear()
    }

    /**
     * [Mode.IIR] entry point: raw accelerometer including gravity.
     *
     * @param timestampNs `SensorEvent.timestamp`, nanoseconds. Passed through
     *   whole rather than pre-divided because [GravitySplitter] derives its
     *   alpha from the inter-sample delta and needs the resolution.
     * @return null on samples that do not close a tick.
     */
    fun onRawSample(timestampNs: Long, x: Double, y: Double, z: Double): GateEvaluation? {
        splitter.update(timestampNs, x, y, z)
        if (!splitter.primed) return null
        return onSeparated(
            tMs = timestampNs / 1_000_000,
            ax = splitter.linearX, ay = splitter.linearY, az = splitter.linearZ,
            gx = splitter.gravityX, gy = splitter.gravityY, gz = splitter.gravityZ,
        )
    }

    /** [Mode.FUSED] entry point: linear acceleration and gravity, already split. */
    fun onFusedSample(
        timestampNs: Long,
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
    ): GateEvaluation? = onSeparated(timestampNs / 1_000_000, ax, ay, az, gx, gy, gz)

    private fun onSeparated(
        tMs: Long,
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
    ): GateEvaluation? {
        analyzer.onSample(tMs, ax, ay, az, gx, gy, gz)

        if (lastTickMs == Long.MIN_VALUE) {
            lastTickMs = tMs
            return null
        }
        val elapsed = tMs - lastTickMs
        if (elapsed < thresholds.tickIntervalMs) return null

        // Advance along a fixed grid rather than resetting to the sample time.
        // Resetting made the realised tick period depend on the sample period
        // (280 ms at 25 Hz against 250 ms at 200 Hz), which put an 8% spread
        // on time to clear across devices.
        //
        // A gap larger than two intervals is a delivery stall (doze, a sensor
        // pause, a batched flush). Resync instead of firing a backlog: catching
        // up would evaluate one stale window many times over and could credit
        // seconds of movement that never happened.
        lastTickMs = if (elapsed > 2 * thresholds.tickIntervalMs) {
            tMs
        } else {
            lastTickMs + thresholds.tickIntervalMs
        }

        // Every tick is worth exactly one interval. A stall costs the credit it
        // would have earned, which is right: there is no evidence of movement
        // across a gap.
        return evaluateTick(tMs, thresholds.tickIntervalMs)
    }

    private fun evaluateTick(tMs: Long, dtMs: Long): GateEvaluation {
        val v = analyzer.verdict(tMs)

        // Hysteresis is applied here rather than inside the analyzer so that
        // the analyzer stays a stateless measurement of one window, and the
        // seven tests keep a single implementation. The analyzer's own
        // failure set is the strict, no-hysteresis view; the two tests with a
        // Schmitt trigger are forgiven while their gate is latched.
        val hzLatched = hzGate.update(if (v.dominantHz > 0) v.dominantHz else 0.0)
        val rmsLatched = rmsGate.update(v.rms)

        val strict = v.allFailures
        val passRms = GateProgress.Reason.NOT_ENOUGH_MOTION !in strict || rmsLatched
        val passHz = GateProgress.Reason.CADENCE_TOO_SLOW !in strict || hzLatched
        val passPeaks = GateProgress.Reason.NEED_MORE_STEPS !in strict
        val passCv = GateProgress.Reason.CADENCE_IRREGULAR !in strict &&
            GateProgress.Reason.TOO_REGULAR !in strict
        val passVertical = GateProgress.Reason.MOTION_NOT_VERTICAL !in strict
        val passPeakCeiling = GateProgress.Reason.TOO_VIOLENT !in strict &&
            GateProgress.Reason.CADENCE_TOO_FAST !in strict
        val passTilt = GateProgress.Reason.PHONE_STATIONARY !in strict

        val allPass = passRms && passHz && passPeaks && passCv &&
            passVertical && passPeakCeiling && passTilt

        val passed = accumulator.tick(allPass, dtMs)

        val reason = when {
            passed -> GateProgress.Reason.PASSED
            allPass -> GateProgress.Reason.SUSTAINING
            // Report the strict reason so the user is told what is actually
            // wrong, even where hysteresis would have forgiven it.
            else -> v.reason
        }

        val evaluation = TickEvaluation(
            tick = accumulator.ticks,
            tMs = tMs,
            dtMs = dtMs,
            path = path,
            thresholdsId = thresholds.id,
            rms = v.rms,
            peaks = v.peaks,
            hz = v.dominantHz,
            cv = v.cv,
            verticalShare = v.verticalShare,
            peakMagnitude = v.peakMagnitude,
            tiltDegrees = v.tiltDegrees,
            regularityCv = v.regularityCv,
            regularityIntervals = v.regularityIntervals,
            tiltSustainedMs = v.tiltSustainedMs,
            suppressedPeaks = v.suppressedPeaks,
            passRms = passRms,
            passPeaks = passPeaks,
            passHz = passHz,
            passCv = passCv,
            passVertical = passVertical,
            passPeakCeiling = passPeakCeiling,
            passTilt = passTilt,
            hzLatched = hzLatched,
            rmsLatched = rmsLatched,
            allPass = allPass,
            creditMs = accumulator.creditMs,
            requiredMs = accumulator.requiredMs,
            reason = reason,
        )

        return GateEvaluation(
            progress = GateProgress(
                fraction = accumulator.fraction,
                reason = reason,
                path = path,
                events = v.peaks,
            ),
            passed = passed,
            tick = evaluation,
        )
    }

    /** Last analyzer verdict, for the sweep and the debug screen. */
    fun verdict(nowMs: Long): CadenceAnalyzer.Verdict = analyzer.verdict(nowMs)

    /** Optional `TYPE_PRESSURE` feed, in hPa. */
    fun onPressure(tMs: Long, hPa: Double) {
        pressureSamples.addLast(tMs to hPa)
        while (pressureSamples.isNotEmpty() &&
            tMs - pressureSamples.first().first > PRESSURE_WINDOW_MS
        ) {
            pressureSamples.removeFirst()
        }
        if (pressureSamples.size < 2) return

        // Monotonic non-increasing across the window with a total drop over the
        // threshold. "Monotonic" carries a small tolerance because barometer
        // noise on phone-grade parts is about 0.01 hPa.
        var monotonic = true
        var prev = pressureSamples.first().second
        for ((_, p) in pressureSamples.drop(1)) {
            if (p > prev + PRESSURE_NOISE_HPA) { monotonic = false; break }
            prev = p
        }
        val drop = pressureSamples.first().second - pressureSamples.last().second
        if (monotonic && drop >= PRESSURE_DROP_HPA && !barometerShortcut) {
            barometerShortcut = true
            // Halve the outstanding requirement by crediting half the bucket.
            accumulator.tick(true, accumulator.requiredMs / 2)
        }
    }

    val hasBarometerShortcut: Boolean get() = barometerShortcut

    companion object {
        const val PRESSURE_WINDOW_MS = 6_000L
        const val PRESSURE_DROP_HPA = 0.04
        const val PRESSURE_NOISE_HPA = 0.01
    }
}
