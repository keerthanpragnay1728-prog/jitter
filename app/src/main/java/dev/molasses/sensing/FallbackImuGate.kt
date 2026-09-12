package dev.molasses.sensing

import dev.molasses.core.model.GateProgress

/**
 * Wraps [CadenceAnalyzer] with the pipeline choice, the sustain requirement,
 * and the optional barometer shortcut. Pure; unit-tested in
 * `FallbackImuGateTest`.
 *
 * ## Two pipelines, one gate
 * [Mode.FUSED] takes `TYPE_LINEAR_ACCELERATION` and `TYPE_GRAVITY` straight
 * from the platform. [Mode.IIR] takes raw `TYPE_ACCELEROMETER` and separates
 * the two with [GravitySplitter]. Each carries its own [Thresholds]; see that
 * interface for why they must not be shared.
 *
 * Note that the fused path needs *both* fused sensors. `TYPE_LINEAR_ACCELERATION`
 * alone is not enough: the vertical-energy-share and gravity-angle tests both
 * need a gravity vector, and deriving one by re-integrating would give back
 * the filter problem this path exists to avoid. [MovementDetector] falls back
 * to [Mode.IIR] when `TYPE_GRAVITY` is missing.
 *
 * ## Sustain
 * SS8: all conditions held for a rolling 8 s. The timer resets the moment any
 * test fails, so an 8 s pass means 8 *continuous* seconds of gait-shaped
 * motion, not 8 seconds of intermittent shaking that happened to satisfy each
 * test at some point.
 *
 * Barometer corroboration is a shortcut, never a requirement: a monotonic drop
 * of >= 0.04 hPa inside 6 s (~0.35 m, i.e. standing up) reduces the sustain to
 * 4 s. Most budget devices have no barometer, so requiring it would exclude a
 * large share of the target hardware.
 */
class FallbackImuGate(
    val mode: Mode = Mode.IIR,
    thresholds: Thresholds = if (mode == Mode.FUSED) Thresholds.FUSED else Thresholds.IIR,
    private val analyzer: CadenceAnalyzer = CadenceAnalyzer(thresholds),
    private val splitter: GravitySplitter = GravitySplitter(),
    private val sustainMs: Long = SUSTAIN_MS,
    private val shortcutSustainMs: Long = SHORTCUT_SUSTAIN_MS,
) {
    enum class Mode { FUSED, IIR }

    private var okSinceMs: Long? = null
    private var barometerShortcut = false

    private val pressureSamples = ArrayDeque<Pair<Long, Double>>()

    val path: GateProgress.Path
        get() = if (mode == Mode.FUSED) GateProgress.Path.IMU_FUSED else GateProgress.Path.IMU_IIR

    val thresholds: Thresholds get() = analyzer.thresholds

    /** Diagnostic: alpha realised on the last IIR sample. NaN in fused mode. */
    val lastAlpha: Double
        get() = if (mode == Mode.IIR) splitter.lastAlpha else Double.NaN

    fun reset() {
        analyzer.reset()
        analyzer.anchorOrientation()
        splitter.reset()
        okSinceMs = null
        barometerShortcut = false
        pressureSamples.clear()
    }

    /**
     * [Mode.IIR] entry point: raw accelerometer including gravity.
     *
     * @param timestampNs `SensorEvent.timestamp`, nanoseconds. Passed through
     *   rather than pre-divided because [GravitySplitter] derives its alpha
     *   from the inter-sample delta and needs the full resolution.
     */
    fun onRawSample(timestampNs: Long, x: Double, y: Double, z: Double): GateProgress {
        splitter.update(timestampNs, x, y, z)
        if (!splitter.primed) {
            return GateProgress(reason = GateProgress.Reason.WAITING_TO_START, path = path)
        }
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
    ): GateProgress = onSeparated(timestampNs / 1_000_000, ax, ay, az, gx, gy, gz)

    private fun onSeparated(
        tMs: Long,
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
    ): GateProgress {
        val v = analyzer.onSample(tMs, ax, ay, az, gx, gy, gz)

        if (!v.ok) {
            okSinceMs = null
            return GateProgress(
                fraction = 0f,
                reason = v.reason,
                path = path,
                events = v.peaks,
                passed = false,
            )
        }

        val since = okSinceMs ?: tMs.also { okSinceMs = it }
        val required = if (barometerShortcut) shortcutSustainMs else sustainMs
        val held = tMs - since
        val passed = held >= required

        return GateProgress(
            fraction = (held.toFloat() / required).coerceIn(0f, 1f),
            reason = if (passed) GateProgress.Reason.PASSED else GateProgress.Reason.SUSTAINING,
            path = path,
            events = v.peaks,
            passed = passed,
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
        // noise on phone-grade parts is ~0.01 hPa.
        var monotonic = true
        var prev = pressureSamples.first().second
        for ((_, p) in pressureSamples.drop(1)) {
            if (p > prev + PRESSURE_NOISE_HPA) { monotonic = false; break }
            prev = p
        }
        val drop = pressureSamples.first().second - pressureSamples.last().second
        if (monotonic && drop >= PRESSURE_DROP_HPA) barometerShortcut = true
    }

    val hasBarometerShortcut: Boolean get() = barometerShortcut

    companion object {
        const val SUSTAIN_MS = 8_000L
        const val SHORTCUT_SUSTAIN_MS = 4_000L
        const val PRESSURE_WINDOW_MS = 6_000L
        const val PRESSURE_DROP_HPA = 0.04
        const val PRESSURE_NOISE_HPA = 0.01
    }
}
