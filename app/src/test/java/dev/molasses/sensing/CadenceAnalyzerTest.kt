package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Analyzer mechanics and the measured consequences of the current thresholds.
 * Cheat-class coverage lives in `MovementRejectionTest`; the recalibration
 * evidence lives in `CalibrationSweep`.
 */
class CadenceAnalyzerTest {

    private fun walk(peakA: Double = 12.0, hz: Double = 1.8) =
        SignalGen.gait(14_000, stepHz = hz, peakA = peakA, jitterFrac = 0.08)

    // ------------------------------------------------- passband and calibration

    @Test
    fun `the 650 ms time constant retains the passband the 80 ms one removed`() {
        // The headline of this patch, measured rather than argued. Peak |a| is
        // compared against a known input peak under both time constants.
        val peakA = 12.0
        val old = SignalGen.verdictIir(walk(peakA), tauSeconds = 0.08).peakMagnitude / peakA
        val new = SignalGen.verdictIir(walk(peakA)).peakMagnitude / peakA
        println("retention: tau=80ms -> %.0f%%, tau=650ms -> %.0f%%".format(old * 100, new * 100))
        assertTrue("old retention was $old, expected ~0.65", old in 0.60..0.70)
        assertTrue("new retention was $new, expected ~0.87", new in 0.82..0.92)
    }

    @Test
    fun `the RMS floor preserves the physical sensitivity the old floor had`() {
        // Old pipeline: RMS = 0.228 x peakA, floor 1.2 -> needs peakA >= 5.3.
        // New pipeline: RMS = 0.292 x peakA, floor 1.5 -> needs peakA >= 5.1.
        // A floor of 2.2 would have needed 7.5, tightening the gate by 43%
        // against no cheat class. See README "Threshold recalibration".
        assertTrue("A=6 must still pass", SignalGen.verdictIir(walk(peakA = 6.0)).ok)
        val weak = SignalGen.verdictIir(walk(peakA = 4.0))
        assertFalse("A=4 is below the floor", weak.ok)
        assertEquals(GateProgress.Reason.NOT_ENOUGH_MOTION, weak.reason)

        // Headroom over the cheat this floor exists for.
        val tremor = SignalGen.verdictIir(SignalGen.thumbTremor(14_000))
        assertTrue(
            "floor should clear tremor by a wide margin: ${tremor.rms} vs ${Thresholds.IIR.minRms}",
            Thresholds.IIR.minRms > tremor.rms * 5,
        )
    }

    @Test
    fun `the gravity estimate tracks tilt more slowly than it used to`() {
        // Unavoidable consequence of the longer time constant, measured so the
        // cost is visible: at 26 deg of true tilt the reported angle now takes
        // 2.3 s to cross the 25 deg threshold instead of 0.6 s.
        fun crossMs(trueTilt: Double, tau: Double): Long? {
            val analyzer = CadenceAnalyzer(Thresholds.IIR)
            val splitter = GravitySplitter(tau)
            for (s in SignalGen.gait(14_000, tiltDeg = trueTilt, jitterFrac = 0.08)) {
                splitter.update(s.timestampNs, s.x, s.y, s.z)
                if (!splitter.primed) continue
                val v = analyzer.onSample(
                    s.tMs,
                    splitter.linearX, splitter.linearY, splitter.linearZ,
                    splitter.gravityX, splitter.gravityY, splitter.gravityZ,
                )
                if (v.tiltDegrees > Thresholds.IIR.minTiltDegrees) return s.tMs
            }
            return null
        }
        val oldCross = crossMs(26.0, 0.08)!!
        val newCross = crossMs(26.0, 0.65)!!
        println("tilt 26 deg crosses 25 deg at: old ${oldCross}ms, new ${newCross}ms")
        assertTrue(newCross > oldCross)
        // It still has to fit inside the gate's 90 s budget with room to spare.
        assertTrue("new crossing at ${newCross}ms", newCross < 3_000)
        // And a shallow-tilt walk must still pass overall.
        assertTrue(
            "26 deg tilt should still pass",
            SignalGen.verdictIir(SignalGen.gait(14_000, tiltDeg = 26.0, jitterFrac = 0.08)).ok,
        )
    }

    // ---------------------------------------------------------------- mechanics

    @Test
    fun `thresholds are injected, not baked in`() {
        // The point of the Thresholds interface: a hardware sweep can re-derive
        // constants without touching logic.
        val strict = object : Thresholds by Thresholds.IIR {
            override val minRms = 99.0
            override val id = "STRICT"
        }
        assertTrue(SignalGen.verdictIir(walk()).ok)
        val v = SignalGen.verdictIir(walk(), thresholds = strict)
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.NOT_ENOUGH_MOTION, v.reason)
        assertEquals("STRICT", v.thresholdsId)
    }

    @Test
    fun `too few samples yields a waiting verdict rather than a pass`() {
        val v = SignalGen.verdictIir(SignalGen.gait(200))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.WAITING_TO_START, v.reason)
    }

    @Test
    fun `reset clears state so a new gate starts clean`() {
        val a = CadenceAnalyzer()
        for (s in walk()) {
            a.onSample(s.tMs, s.linearX, s.linearY, s.linearZ, s.trueGx, s.trueGy, s.trueGz)
        }
        a.reset()
        assertEquals(GateProgress.Reason.WAITING_TO_START, a.verdict(0).reason)
    }

    @Test
    fun `the refractory period suppresses double-counting one impulse`() {
        // Exactly one burst inside the window. The longer time constant leaves
        // a larger ringing peak ~80 ms after each heel strike than the old one
        // did, so this is the check that the refractory still absorbs it.
        val v = SignalGen.verdictIir(SignalGen.gait(1_500, stepHz = 0.34, peakA = 14.0))
        assertEquals("one impulse must register one peak", 1, v.peaks)
        assertEquals("and the ringing must not count as decimation", 0, v.suppressedPeaks)
    }

    @Test
    fun `the verdict reports every failing test, not only the headline`() {
        val v = SignalGen.verdictIir(SignalGen.static(14_000))
        assertTrue("static should fail several tests, saw ${v.failures}", v.failures.size >= 2)
        assertTrue(v.reason in v.failures)
    }

    @Test
    fun `angleBetweenDegrees is correct on known vectors`() {
        assertEquals(90.0, CadenceAnalyzer.angleBetweenDegrees(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(0.0, CadenceAnalyzer.angleBetweenDegrees(0.0, 0.0, 9.8, 0.0, 0.0, 4.2), 1e-6)
        assertEquals(180.0, CadenceAnalyzer.angleBetweenDegrees(0.0, 0.0, 1.0, 0.0, 0.0, -1.0), 1e-6)
    }
}
