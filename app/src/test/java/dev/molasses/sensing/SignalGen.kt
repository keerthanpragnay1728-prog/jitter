package dev.molasses.sensing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic accelerometer signals for exercising [CadenceAnalyzer]'s
 * seven-test battery without a treadmill.
 *
 * Real gait is not a symmetric sinusoid: each footfall is a sharp heel-strike
 * transient followed by a smoother push-off, so |a| shows roughly one dominant
 * peak per step. That is why the brief's 1.2-2.6 Hz band is steps-per-second.
 * Modelling walking as a sine wave would double-peak |a| and land at twice the
 * real rate, so bursts it is.
 */
object SignalGen {

    const val G = 9.81
    data class Sample(val tMs: Long, val x: Double, val y: Double, val z: Double)

    /**
     * @param stepHz footfalls per second.
     * @param peakA heel-strike peak of linear acceleration, m/s^2.
     * @param tiltDeg final tilt of the device away from its start orientation.
     * @param tiltRampMs how long the tilt takes.
     * @param lateralFrac share of the burst directed laterally rather than
     *   along gravity.
     * @param jitterFrac pseudo-random variation of step intervals, as a
     *   fraction of the nominal interval. 0 is metronomic.
     */
    fun gait(
        durationMs: Long,
        stepHz: Double = 1.8,
        peakA: Double = 12.0,
        sampleHz: Int = 50,
        tiltDeg: Double = 40.0,
        tiltRampMs: Long = 400,
        lateralFrac: Double = 0.2,
        jitterFrac: Double = 0.0,
        burstWidthMs: Double = 140.0,
        seed: Int = 12345,
    ): List<Sample> {
        val dt = 1000 / sampleHz
        val out = ArrayList<Sample>((durationMs / dt).toInt() + 1)

        // Step times, with optional jitter.
        val stepTimes = ArrayList<Double>()
        var t = 250.0
        var rnd = seed
        val interval = 1000.0 / stepHz
        while (t < durationMs) {
            stepTimes += t
            rnd = rnd * 1103515245 + 12345
            val u = ((rnd ushr 16) and 0x7FFF) / 32767.0 * 2 - 1 // -1..1
            t += interval * (1 + jitterFrac * u)
        }

        var ms = 0L
        while (ms <= durationMs) {
            val ramp = if (tiltRampMs <= 0) 1.0 else (ms.toDouble() / tiltRampMs).coerceAtMost(1.0)
            val ang = Math.toRadians(tiltDeg * ramp)
            // Gravity rotated about the x axis.
            val gx = 0.0
            val gy = G * sin(ang)
            val gz = G * cos(ang)

            // Burst envelope: raised cosine of half-width burstWidthMs/2.
            var env = 0.0
            val half = burstWidthMs / 2
            for (st in stepTimes) {
                val d = ms - st
                if (d > -half && d < half) env += 0.5 * (1 + cos(PI * d / half))
            }
            val a = peakA * env

            // Along gravity (vertical) plus a lateral component on x.
            val gn = G
            out += Sample(
                tMs = ms,
                x = gx + a * lateralFrac,
                y = gy + a * (gy / gn),
                z = gz + a * (gz / gn),
            )
            ms += dt
        }
        return out
    }

    /** Phone flat on a desk, being tapped. No orientation change at all. */
    fun deskTap(durationMs: Long, tapHz: Double = 1.8, peakA: Double = 14.0, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs,
            stepHz = tapHz,
            peakA = peakA,
            sampleHz = sampleHz,
            tiltDeg = 0.0,
            tiltRampMs = 0,
            lateralFrac = 0.1,
        )

    /**
     * Hand-waving: burst-shaped like gait (so the cadence tests pass) but
     * directed across gravity instead of along it. This isolates the
     * vertical-energy-share test.
     */
    fun lateralWave(
        durationMs: Long,
        hz: Double = 1.8,
        peakA: Double = 14.0,
        sampleHz: Int = 50,
    ): List<Sample> {
        val dt = 1000 / sampleHz
        val out = ArrayList<Sample>()
        val ang = Math.toRadians(40.0)
        val interval = 1000.0 / hz
        val half = 70.0
        var ms = 0L
        while (ms <= durationMs) {
            val ramp = (ms.toDouble() / 400).coerceAtMost(1.0)
            var env = 0.0
            var st = 250.0
            while (st < durationMs) {
                val d = ms - st
                if (d > -half && d < half) env += 0.5 * (1 + cos(PI * d / half))
                st += interval
            }
            // Entire burst on x, which is perpendicular to gravity (y-z plane).
            out += Sample(
                tMs = ms,
                x = peakA * env,
                y = G * sin(ang * ramp),
                z = G * cos(ang * ramp),
            )
            ms += dt
        }
        return out
    }

    /** Violent shaking to brute-force the gate. */
    fun violentShake(durationMs: Long, hz: Double = 5.0, peakA: Double = 40.0, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs,
            stepHz = hz,
            peakA = peakA,
            sampleHz = sampleHz,
            tiltDeg = 60.0,
            tiltRampMs = 200,
            lateralFrac = 0.4,
            burstWidthMs = 90.0,
        )

    /** Thumb tremor while sitting still: tiny amplitude. */
    fun thumbTremor(durationMs: Long, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs,
            stepHz = 4.0,
            peakA = 0.35,
            sampleHz = sampleHz,
            tiltDeg = 2.0,
            tiltRampMs = 200,
            lateralFrac = 0.5,
            burstWidthMs = 100.0,
        )

    fun feed(gate: FallbackImuGate, samples: List<Sample>) =
        samples.map { gate.onSample(it.tMs, it.x, it.y, it.z) }.last()

    fun feed(analyzer: CadenceAnalyzer, samples: List<Sample>): CadenceAnalyzer.Verdict {
        var v: CadenceAnalyzer.Verdict? = null
        for (s in samples) v = analyzer.onSample(s.tMs, s.x, s.y, s.z)
        return v!!
    }
}
