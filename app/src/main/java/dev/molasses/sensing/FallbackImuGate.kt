package dev.molasses.sensing

import dev.molasses.core.model.GateProgress

/**
 * Wraps [CadenceAnalyzer] with the sustain requirement and the optional
 * barometer shortcut. Pure; unit-tested in `FallbackImuGateTest`.
 *
 * SS8: "Sustain all conditions for a rolling 8 s before passing." The sustain
 * timer resets the moment any test fails, so an 8 s pass really means 8
 * continuous seconds of gait-shaped motion and not 8 seconds of intermittent
 * shaking that happened to satisfy each test at some point.
 *
 * Barometer corroboration is a *shortcut*, never a requirement: a monotonic
 * drop of >= 0.04 hPa inside 6 s (~0.35 m of elevation, i.e. standing up)
 * reduces the sustain requirement to 4 s. Most budget devices have no
 * barometer, so requiring it would exclude a large share of the target
 * hardware.
 */
class FallbackImuGate(
    private val analyzer: CadenceAnalyzer = CadenceAnalyzer(),
    private val sustainMs: Long = SUSTAIN_MS,
    private val shortcutSustainMs: Long = SHORTCUT_SUSTAIN_MS,
) {
    private var okSinceMs: Long? = null
    private var barometerShortcut = false

    private val pressureSamples = ArrayDeque<Pair<Long, Double>>()

    fun reset() {
        analyzer.reset()
        analyzer.anchorOrientation()
        okSinceMs = null
        barometerShortcut = false
        pressureSamples.clear()
    }

    fun onSample(tMs: Long, x: Double, y: Double, z: Double): GateProgress {
        val v = analyzer.onSample(tMs, x, y, z)

        if (!v.ok) {
            okSinceMs = null
            return GateProgress(
                fraction = 0f,
                reason = v.reason,
                path = GateProgress.Path.IMU_CADENCE,
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
            path = GateProgress.Path.IMU_CADENCE,
            events = v.peaks,
            passed = passed,
        )
    }

    /** Optional [android.hardware.Sensor.TYPE_PRESSURE] feed, in hPa. */
    fun onPressure(tMs: Long, hPa: Double) {
        pressureSamples.addLast(tMs to hPa)
        while (pressureSamples.isNotEmpty() && tMs - pressureSamples.first().first > PRESSURE_WINDOW_MS) {
            pressureSamples.removeFirst()
        }
        if (pressureSamples.size < 2) return

        // Monotonic non-increasing across the window, with a total drop over
        // the threshold. "Monotonic" is checked with a small tolerance because
        // barometer noise on phone-grade parts is ~0.01 hPa.
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
