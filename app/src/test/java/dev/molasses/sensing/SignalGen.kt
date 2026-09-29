package dev.molasses.sensing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic accelerometer signals for exercising the seven-test battery
 * without a treadmill.
 *
 * Real gait is not a symmetric sinusoid: each footfall is a sharp heel-strike
 * transient followed by a smoother push-off, so |a| shows roughly one dominant
 * peak per step. That is why SS8's 1.2-2.6 Hz band is steps-per-second.
 * Modelling walking as a sine would double-peak |a| and land at twice the real
 * rate, so bursts it is.
 *
 * Samples carry a nanosecond timestamp because [GravitySplitter] derives its
 * alpha from the inter-sample delta -- a generator that only produced
 * millisecond times could not exercise the rate-independence this patch is
 * about.
 */
object SignalGen {

    const val G = 9.81

    /** Raw acceleration including gravity, plus the true gravity that produced it. */
    data class Sample(
        val timestampNs: Long,
        val x: Double,
        val y: Double,
        val z: Double,
        val trueGx: Double,
        val trueGy: Double,
        val trueGz: Double,
    ) {
        val tMs: Long get() = timestampNs / 1_000_000

        /** Linear acceleration as a perfect fused sensor would report it. */
        val linearX: Double get() = x - trueGx
        val linearY: Double get() = y - trueGy
        val linearZ: Double get() = z - trueGz
    }

    /**
     * @param stepHz footfalls per second.
     * @param peakA heel-strike peak of linear acceleration, m/s^2.
     * @param sampleHz delivery rate; varied to exercise the dt-derived alpha.
     * @param tiltDeg final tilt of the device away from its start orientation.
     * @param lateralFrac share of the burst directed laterally.
     * @param jitterFrac pseudo-random variation of step intervals as a
     *   fraction of the nominal interval. 0 is metronomic.
     * @param phaseOffset fraction of one step interval to shift all steps by,
     *   for the band-edge phase sweep.
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
        phaseOffset: Double = 0.0,
        seed: Int = 12345,
    ): List<Sample> {
        val dtMs = 1000.0 / sampleHz
        val out = ArrayList<Sample>((durationMs / dtMs).toInt() + 1)

        val interval = 1000.0 / stepHz
        val stepTimes = ArrayList<Double>()
        var t = 250.0 + phaseOffset * interval
        // kotlin.random rather than a hand-rolled LCG: an earlier version used
        // `rnd * 1103515245 + 12345` with the middle bits, which produced a
        // jitter sequence biased enough to shift the measured cadence of a
        // 1.20 Hz walk to 1.14 Hz -- an artifact that looked exactly like an
        // analyzer bug during the SS2 band-edge sweep.
        val rnd = kotlin.random.Random(seed)
        while (t < durationMs) {
            stepTimes += t
            val u = rnd.nextDouble(-1.0, 1.0)
            t += interval * (1 + jitterFrac * u)
        }

        var i = 0
        while (true) {
            val ms = i * dtMs
            if (ms > durationMs) break
            val ramp = if (tiltRampMs <= 0) 1.0 else (ms / tiltRampMs).coerceAtMost(1.0)
            val ang = Math.toRadians(tiltDeg * ramp)
            val gx = 0.0
            val gy = G * sin(ang)
            val gz = G * cos(ang)

            var env = 0.0
            val half = burstWidthMs / 2
            for (st in stepTimes) {
                val d = ms - st
                if (d > -half && d < half) env += 0.5 * (1 + cos(PI * d / half))
            }
            val a = peakA * env

            out += Sample(
                timestampNs = (ms * 1_000_000.0).toLong(),
                x = gx + a * lateralFrac,
                y = gy + a * (gy / G),
                z = gz + a * (gz / G),
                trueGx = gx, trueGy = gy, trueGz = gz,
            )
            i++
        }
        return out
    }

    /** Phone flat on a desk being tapped. No orientation change at all. */
    fun deskTap(durationMs: Long, tapHz: Double = 1.8, peakA: Double = 14.0, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs, stepHz = tapHz, peakA = peakA, sampleHz = sampleHz,
            tiltDeg = 0.0, tiltRampMs = 0, lateralFrac = 0.1,
        )

    /**
     * Hand-waving: burst-shaped like gait so the cadence tests pass, but
     * directed across gravity instead of along it. Isolates the
     * vertical-energy-share test.
     */
    fun lateralWave(
        durationMs: Long,
        hz: Double = 1.8,
        peakA: Double = 14.0,
        sampleHz: Int = 50,
    ): List<Sample> {
        val dtMs = 1000.0 / sampleHz
        val out = ArrayList<Sample>()
        val ang = Math.toRadians(40.0)
        val interval = 1000.0 / hz
        val half = 70.0
        var i = 0
        while (true) {
            val ms = i * dtMs
            if (ms > durationMs) break
            val ramp = (ms / 400).coerceAtMost(1.0)
            var env = 0.0
            var st = 250.0
            while (st < durationMs) {
                val d = ms - st
                if (d > -half && d < half) env += 0.5 * (1 + cos(PI * d / half))
                st += interval
            }
            val gy = G * sin(ang * ramp)
            val gz = G * cos(ang * ramp)
            // Entire burst on x, perpendicular to gravity in the y-z plane.
            out += Sample(
                timestampNs = (ms * 1_000_000.0).toLong(),
                x = peakA * env, y = gy, z = gz,
                trueGx = 0.0, trueGy = gy, trueGz = gz,
            )
            i++
        }
        return out
    }

    /** Violent shaking to brute-force the gate. */
    fun violentShake(durationMs: Long, hz: Double = 5.0, peakA: Double = 40.0, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs, stepHz = hz, peakA = peakA, sampleHz = sampleHz,
            tiltDeg = 60.0, tiltRampMs = 200, lateralFrac = 0.4, burstWidthMs = 90.0,
        )

    /** Shaking faster than walking, but not violently. */
    fun fastShake(durationMs: Long, hz: Double = 4.0, peakA: Double = 10.0, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs, stepHz = hz, peakA = peakA, sampleHz = sampleHz,
            tiltDeg = 45.0, tiltRampMs = 200, lateralFrac = 0.3, burstWidthMs = 100.0,
        )

    /** Thumb tremor while sitting still: tiny amplitude. */
    fun thumbTremor(durationMs: Long, sampleHz: Int = 50) =
        gait(
            durationMs = durationMs, stepHz = 4.0, peakA = 0.35, sampleHz = sampleHz,
            tiltDeg = 2.0, tiltRampMs = 200, lateralFrac = 0.5, burstWidthMs = 100.0,
        )

    /** Phone lying perfectly still on a table. */
    fun static(durationMs: Long, sampleHz: Int = 50): List<Sample> {
        val dtMs = 1000.0 / sampleHz
        val out = ArrayList<Sample>()
        var i = 0
        while (true) {
            val ms = i * dtMs
            if (ms > durationMs) break
            out += Sample(
                timestampNs = (ms * 1_000_000.0).toLong(),
                x = 0.0, y = 0.0, z = G,
                trueGx = 0.0, trueGy = 0.0, trueGz = G,
            )
            i++
        }
        return out
    }

    // ------------------------------------------------------------- feeding

    /**
     * Feed raw samples through the IIR path and return the last tick, or null
     * if the run was too short to close one. Most samples do not close a tick
     * now that the battery runs at a fixed 4 Hz.
     */
    fun feedIir(gate: FallbackImuGate, samples: List<Sample>): GateEvaluation? {
        var last: GateEvaluation? = null
        for (s in samples) gate.onRawSample(s.timestampNs, s.x, s.y, s.z)?.let { last = it }
        return last
    }

    /** Feed the fused path from the generator's exact gravity. */
    fun feedFused(gate: FallbackImuGate, samples: List<Sample>): GateEvaluation? {
        var last: GateEvaluation? = null
        for (s in samples) {
            gate.onFusedSample(
                s.timestampNs, s.linearX, s.linearY, s.linearZ,
                s.trueGx, s.trueGy, s.trueGz,
            )?.let { last = it }
        }
        return last
    }

    /** Every tick of a run, for accumulator and instrumentation tests. */
    fun ticksIir(gate: FallbackImuGate, samples: List<Sample>): List<GateEvaluation> {
        val out = mutableListOf<GateEvaluation>()
        for (s in samples) gate.onRawSample(s.timestampNs, s.x, s.y, s.z)?.let { out += it }
        return out
    }

    /** Feed through the IIR path and return the analyzer verdict. */
    fun verdictIir(
        samples: List<Sample>,
        thresholds: Thresholds = Thresholds.IIR,
        tauSeconds: Double = GravitySplitter.DEFAULT_TAU_SECONDS,
    ): CadenceAnalyzer.Verdict {
        val analyzer = CadenceAnalyzer(thresholds)
        val splitter = GravitySplitter(tauSeconds)
        var v: CadenceAnalyzer.Verdict? = null
        for (s in samples) {
            splitter.update(s.timestampNs, s.x, s.y, s.z)
            if (!splitter.primed) continue
            v = analyzer.onSample(
                s.tMs,
                splitter.linearX, splitter.linearY, splitter.linearZ,
                splitter.gravityX, splitter.gravityY, splitter.gravityZ,
            )
        }
        return v ?: analyzer.verdict(samples.lastOrNull()?.tMs ?: 0)
    }

    /** Feed through the fused path, using the generator's exact gravity. */
    fun verdictFused(
        samples: List<Sample>,
        thresholds: Thresholds = Thresholds.FUSED,
    ): CadenceAnalyzer.Verdict {
        val analyzer = CadenceAnalyzer(thresholds)
        var v: CadenceAnalyzer.Verdict? = null
        for (s in samples) {
            v = analyzer.onSample(
                s.tMs,
                s.linearX, s.linearY, s.linearZ,
                s.trueGx, s.trueGy, s.trueGz,
            )
        }
        return v ?: analyzer.verdict(samples.lastOrNull()?.tMs ?: 0)
    }
}
