package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Path B: accelerometer cadence analysis. Pure -- no Android imports, so the
 * seven-test battery below is unit-testable against synthetic signals rather
 * than by walking around holding a phone.
 *
 * Pipeline (SS8):
 *  1. Low-pass the raw vector (alpha = 0.8) to estimate gravity `g`;
 *     linear acceleration `a = raw - g`.
 *  2. Project `a` onto `g-hat` for `a_vert`; the orthogonal residual is `a_lat`.
 *  3. Peak-pick `|a|` with a 250 ms refractory period.
 *
 * Then all seven tests must hold simultaneously. Each rejects a specific
 * cheat, which is why none of them can be dropped:
 *
 * | test                    | threshold  | rejects                      |
 * |-------------------------|------------|------------------------------|
 * | RMS of |a|              | > 1.2 m/s2 | thumb tremor (< 0.4)         |
 * | peaks in 3 s window     | >= 4       | a single jerk                |
 * | dominant interval freq  | 1.2-2.6 Hz | fast shaking (> 3.5 Hz)      |
 * | CV of peak intervals    | < 0.35     | erratic wrist flicking       |
 * | vertical energy share   | >= 0.50    | lateral hand-waving          |
 * | peak |a|                | < 25 m/s2  | violent shaking to brute-force |
 * | gravity angle change    | > 25 deg, 1 s | phone flat on a desk being tapped |
 */
class CadenceAnalyzer(
    private val windowMs: Long = WINDOW_MS,
) {
    private data class Sample(val tMs: Long, val magnitude: Double, val vert: Double)

    private val samples = ArrayDeque<Sample>()
    private val peakTimesMs = ArrayDeque<Long>()

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gravityPrimed = false

    private var referenceGx = 0.0
    private var referenceGy = 0.0
    private var referenceGz = 0.0
    private var haveReference = false

    private var lastPeakMs = Long.MIN_VALUE
    private var prevMagnitude = 0.0
    private var risingMagnitude = 0.0
    private var rising = false

    /** Start (or restart) of the sustained tilt-change condition, or null. */
    private var tiltSinceMs: Long? = null

    fun reset() {
        samples.clear()
        peakTimesMs.clear()
        gravityPrimed = false
        haveReference = false
        lastPeakMs = Long.MIN_VALUE
        prevMagnitude = 0.0
        rising = false
        risingMagnitude = 0.0
        tiltSinceMs = null
    }

    /**
     * Feed one accelerometer sample.
     *
     * @param tMs monotonic sample time in ms.
     * @param x/y/z raw acceleration including gravity, m/s^2.
     */
    fun onSample(tMs: Long, x: Double, y: Double, z: Double): Verdict {
        // 1. gravity estimate by exponential low-pass.
        if (!gravityPrimed) {
            gx = x; gy = y; gz = z
            gravityPrimed = true
        } else {
            gx = ALPHA * gx + (1 - ALPHA) * x
            gy = ALPHA * gy + (1 - ALPHA) * y
            gz = ALPHA * gz + (1 - ALPHA) * z
        }
        if (!haveReference) {
            referenceGx = gx; referenceGy = gy; referenceGz = gz
            haveReference = true
        }

        // Linear acceleration.
        val ax = x - gx
        val ay = y - gy
        val az = z - gz
        val mag = sqrt(ax * ax + ay * ay + az * az)

        // 2. project onto the gravity unit vector.
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        val vert = if (gNorm > 1e-6) (ax * gx + ay * gy + az * gz) / gNorm else 0.0

        samples.addLast(Sample(tMs, mag, vert))
        while (samples.isNotEmpty() && tMs - samples.first().tMs > windowMs) {
            samples.removeFirst()
        }

        // 3. peak-pick with refractory period. A local maximum is confirmed on
        // the first downward step after a rise, not on the sample itself, so a
        // monotonic ramp never registers.
        if (mag > prevMagnitude) {
            rising = true
            risingMagnitude = mag
        } else if (rising && mag < prevMagnitude) {
            rising = false
            if (risingMagnitude >= PEAK_MIN_MAGNITUDE &&
                (lastPeakMs == Long.MIN_VALUE || tMs - lastPeakMs >= REFRACTORY_MS)
            ) {
                lastPeakMs = tMs
                peakTimesMs.addLast(tMs)
            }
        }
        prevMagnitude = mag
        while (peakTimesMs.isNotEmpty() && tMs - peakTimesMs.first() > windowMs) {
            peakTimesMs.removeFirst()
        }

        // Tilt: angle between the current and gate-start gravity vectors.
        val tilt = tiltDegrees()
        tiltSinceMs = if (tilt > MIN_TILT_DEGREES) (tiltSinceMs ?: tMs) else null

        return verdict(tMs)
    }

    /** Result of the seven-test battery at one instant. */
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
    )

    fun verdict(nowMs: Long): Verdict {
        val n = samples.size
        if (n < MIN_SAMPLES) {
            return Verdict(
                false, GateProgress.Reason.WAITING_TO_START,
                0.0, peakTimesMs.size, 0.0, 0.0, 0.0, 0.0, tiltDegrees(), 0,
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
        var mean = 0.0
        var cv = Double.MAX_VALUE
        var dominantHz = 0.0
        if (times.size >= 2) {
            val intervals = DoubleArray(times.size - 1) {
                (times[it + 1] - times[it]).toDouble()
            }
            mean = intervals.average()
            val sd = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
            cv = if (mean > 0) sd / mean else Double.MAX_VALUE
            dominantHz = if (mean > 0) 1000.0 / mean else 0.0
        }

        val tilt = tiltDegrees()
        val tiltMs = tiltSinceMs?.let { nowMs - it } ?: 0L

        // Order matters: report the most diagnostic failure. Disqualifying
        // cheats (too violent, too fast, lateral, stationary) are named before
        // "keep going" conditions so the gate tells a cheater why, which is
        // both fairer and a better deterrent than a stuck progress ring.
        val reason = when {
            peakMag >= MAX_PEAK_MAGNITUDE -> GateProgress.Reason.TOO_VIOLENT
            times.size >= 2 && dominantHz > MAX_HZ -> GateProgress.Reason.CADENCE_TOO_FAST
            rms <= MIN_RMS -> GateProgress.Reason.NOT_ENOUGH_MOTION
            tilt <= MIN_TILT_DEGREES || tiltMs < MIN_TILT_SUSTAIN_MS ->
                GateProgress.Reason.PHONE_STATIONARY
            verticalShare < MIN_VERTICAL_SHARE -> GateProgress.Reason.MOTION_NOT_VERTICAL
            peaks < MIN_PEAKS -> GateProgress.Reason.NEED_MORE_STEPS
            dominantHz < MIN_HZ -> GateProgress.Reason.CADENCE_TOO_SLOW
            cv >= MAX_CV -> GateProgress.Reason.CADENCE_IRREGULAR
            else -> GateProgress.Reason.PASSED
        }

        return Verdict(
            ok = reason == GateProgress.Reason.PASSED,
            reason = reason,
            rms = rms,
            peaks = peaks,
            dominantHz = dominantHz,
            cv = cv,
            verticalShare = verticalShare,
            peakMagnitude = peakMag,
            tiltDegrees = tilt,
            tiltSustainedMs = tiltMs,
        )
    }

    private fun tiltDegrees(): Double {
        if (!haveReference || !gravityPrimed) return 0.0
        val aN = sqrt(gx * gx + gy * gy + gz * gz)
        val bN = sqrt(
            referenceGx * referenceGx + referenceGy * referenceGy + referenceGz * referenceGz,
        )
        if (aN < 1e-6 || bN < 1e-6) return 0.0
        val dot = (gx * referenceGx + gy * referenceGy + gz * referenceGz) / (aN * bN)
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /** Re-anchor the tilt reference, e.g. when a gate opens. */
    fun anchorOrientation() {
        haveReference = false
        tiltSinceMs = null
    }

    companion object {
        const val ALPHA = 0.8
        const val WINDOW_MS = 3_000L
        const val REFRACTORY_MS = 250L
        const val MIN_SAMPLES = 40

        const val MIN_RMS = 1.2
        const val MIN_PEAKS = 4
        const val MIN_HZ = 1.2
        const val MAX_HZ = 2.6
        /** Above this is shaking, not walking; SS8 names 3.5 Hz as the cheat. */
        const val REJECT_HZ = 3.5
        const val MAX_CV = 0.35
        const val MIN_VERTICAL_SHARE = 0.50
        const val MAX_PEAK_MAGNITUDE = 25.0
        const val MIN_TILT_DEGREES = 25.0
        const val MIN_TILT_SUSTAIN_MS = 1_000L

        /** Ignore ripples below this when peak-picking. */
        const val PEAK_MIN_MAGNITUDE = 0.8

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
