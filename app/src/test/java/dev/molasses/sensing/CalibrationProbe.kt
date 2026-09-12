package dev.molasses.sensing

import org.junit.Test

/**
 * Not an assertion -- a diagnostic. Prints the seven measured quantities for
 * each synthetic signal class so the thresholds in [CadenceAnalyzer] can be
 * checked against something other than intuition.
 */
class CalibrationProbe {

    private fun report(name: String, samples: List<SignalGen.Sample>) {
        val a = CadenceAnalyzer()
        val v = SignalGen.feed(a, samples)
        println(
            "%-16s ok=%-5s reason=%-20s rms=%6.2f peaks=%2d hz=%5.2f cv=%5.2f vert=%.2f pk=%6.2f tilt=%5.1f tiltMs=%d"
                .format(
                    name, v.ok, v.reason, v.rms, v.peaks, v.dominantHz,
                    if (v.cv > 99) 99.0 else v.cv, v.verticalShare, v.peakMagnitude,
                    v.tiltDegrees, v.tiltSustainedMs,
                ),
        )
    }

    @Test
    fun probe() {
        println("=== CadenceAnalyzer calibration (3 s window, 50 Hz) ===")
        report("walk 1.8Hz", SignalGen.gait(6_000))
        report("walk 1.3Hz", SignalGen.gait(6_000, stepHz = 1.3))
        report("walk 2.4Hz", SignalGen.gait(6_000, stepHz = 2.4))
        report("walk jitter .2", SignalGen.gait(6_000, jitterFrac = 0.2))
        report("walk jitter .6", SignalGen.gait(6_000, jitterFrac = 0.6))
        report("slow 0.9Hz", SignalGen.gait(6_000, stepHz = 0.9))
        report("desk tap", SignalGen.deskTap(6_000))
        report("lateral wave", SignalGen.lateralWave(6_000))
        report("violent shake", SignalGen.violentShake(6_000))
        report("thumb tremor", SignalGen.thumbTremor(6_000))
        report("weak walk A=4", SignalGen.gait(6_000, peakA = 4.0))
        report("walk 1.2Hz", SignalGen.gait(6_000, stepHz = 1.2))
        report("walk 1.0Hz", SignalGen.gait(6_000, stepHz = 1.0))
        report("walk 2.6Hz", SignalGen.gait(6_000, stepHz = 2.6))
        report("walk 2.8Hz", SignalGen.gait(6_000, stepHz = 2.8))
        report("walk A=7", SignalGen.gait(6_000, peakA = 7.0))
        report("walk A=6", SignalGen.gait(6_000, peakA = 6.0))
        report("pocket tilt10", SignalGen.gait(6_000, tiltDeg = 10.0))
        report("pocket tilt26", SignalGen.gait(6_000, tiltDeg = 26.0))
        report("jitter .9", SignalGen.gait(6_000, jitterFrac = 0.9))
    }
}
