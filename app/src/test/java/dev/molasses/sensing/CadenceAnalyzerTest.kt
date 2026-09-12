package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each test names the cheat it defends against. Measured values for every
 * signal class are printed by [CalibrationProbe] and reproduced in the README.
 */
class CadenceAnalyzerTest {

    private fun verdict(samples: List<SignalGen.Sample>) =
        SignalGen.feed(CadenceAnalyzer(), samples)

    // ------------------------------------------------------------- accepts

    @Test
    fun `ordinary walking passes`() {
        val v = verdict(SignalGen.gait(6_000, stepHz = 1.8))
        assertTrue("reason=${v.reason} rms=${v.rms} vert=${v.verticalShare}", v.ok)
        assertEquals(GateProgress.Reason.PASSED, v.reason)
    }

    @Test
    fun `walking anywhere in the middle of the band passes`() {
        for (hz in listOf(1.4, 1.6, 1.8, 2.0, 2.2, 2.4)) {
            val v = verdict(SignalGen.gait(6_000, stepHz = hz))
            assertTrue("$hz Hz should pass, got ${v.reason}", v.ok)
        }
    }

    @Test
    fun `natural step-to-step variation still passes`() {
        // Real gait is metronomic but not metronome-perfect.
        val v = verdict(SignalGen.gait(6_000, jitterFrac = 0.25))
        assertTrue("reason=${v.reason} cv=${v.cv}", v.ok)
        assertTrue("cv should be well under the limit, was ${v.cv}", v.cv < CadenceAnalyzer.MAX_CV)
    }

    @Test
    fun `phone in a pocket at 26 degrees of tilt passes`() {
        val v = verdict(SignalGen.gait(6_000, tiltDeg = 26.0))
        assertTrue("reason=${v.reason} tilt=${v.tiltDegrees}", v.ok)
    }

    // -------------------------------------------------------------- rejects

    @Test
    fun `thumb tremor while sitting still is rejected on RMS`() {
        val v = verdict(SignalGen.thumbTremor(6_000))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.NOT_ENOUGH_MOTION, v.reason)
        assertTrue("tremor RMS should be far below the floor, was ${v.rms}", v.rms < 0.4)
    }

    @Test
    fun `a phone flat on a desk being tapped is rejected on the tilt test`() {
        // The cheat this exists for: tapping or bouncing a stationary phone
        // produces a perfectly gait-shaped |a| signal. Only the gravity-vector
        // angle distinguishes it from walking.
        val v = verdict(SignalGen.deskTap(6_000))
        assertFalse("desk tapping must not pass", v.ok)
        assertEquals(GateProgress.Reason.PHONE_STATIONARY, v.reason)
        // Note how convincing it otherwise looks:
        assertTrue("desk tap RMS clears the motion floor: ${v.rms}", v.rms > CadenceAnalyzer.MIN_RMS)
        assertTrue("and its cadence is in band: ${v.dominantHz}", v.dominantHz in 1.2..2.6)
    }

    @Test
    fun `tilt below 25 degrees is rejected`() {
        val v = verdict(SignalGen.gait(6_000, tiltDeg = 10.0))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.PHONE_STATIONARY, v.reason)
    }

    @Test
    fun `lateral hand-waving is rejected on vertical energy share`() {
        val v = verdict(SignalGen.lateralWave(6_000))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.MOTION_NOT_VERTICAL, v.reason)
        assertTrue("vertical share should be low, was ${v.verticalShare}", v.verticalShare < 0.5)
    }

    @Test
    fun `violent shaking to brute-force the gate is rejected on peak magnitude`() {
        val v = verdict(SignalGen.violentShake(6_000))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.TOO_VIOLENT, v.reason)
        assertTrue(v.peakMagnitude >= CadenceAnalyzer.MAX_PEAK_MAGNITUDE)
    }

    @Test
    fun `fast shaking above the band is rejected`() {
        val v = verdict(SignalGen.gait(6_000, stepHz = 4.0, peakA = 10.0))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.CADENCE_TOO_FAST, v.reason)
    }

    @Test
    fun `cadence just above the ceiling is rejected`() {
        val v = verdict(SignalGen.gait(6_000, stepHz = 2.8))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.CADENCE_TOO_FAST, v.reason)
    }

    @Test
    fun `a single jerk is rejected on peak count`() {
        val v = verdict(SignalGen.gait(6_000, stepHz = 0.2, peakA = 14.0))
        assertFalse(v.ok)
        assertTrue(
            "expected a peak-count or motion rejection, got ${v.reason}",
            v.reason == GateProgress.Reason.NEED_MORE_STEPS ||
                v.reason == GateProgress.Reason.NOT_ENOUGH_MOTION,
        )
    }

    @Test
    fun `erratic wrist flicking is rejected on CV`() {
        val v = verdict(SignalGen.gait(6_000, jitterFrac = 1.6, stepHz = 1.9))
        assertFalse("reason=${v.reason} cv=${v.cv}", v.ok)
    }

    // ------------------------------------------- documented threshold effects

    @Test
    fun `the peak-count rule binds before the stated frequency floor`() {
        // SPEC CONFLICT, surfaced rather than silently retuned. SS8's table
        // states a pass band of 1.2-2.6 Hz *and* ">= 4 peaks in a 3 s window".
        // Four peaks need three intervals, and at 1.2 Hz a 3 s trailing window
        // holds only 3 peaks depending on phase (1.2 x 3 = 3.6 expected). So
        // the effective floor is ~1.33 Hz, not 1.2 Hz, and a genuinely slow
        // walker is rejected with NEED_MORE_STEPS.
        //
        // Both thresholds are implemented exactly as written. The README
        // records the fix options (MIN_PEAKS = 3, or a 3.5 s window) for a
        // decision rather than applying one unilaterally.
        val v = verdict(SignalGen.gait(6_000, stepHz = 1.2))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.NEED_MORE_STEPS, v.reason)
        assertEquals(3, v.peaks)
        assertTrue("cadence itself is in band: ${v.dominantHz}", v.dominantHz >= CadenceAnalyzer.MIN_HZ)

        // 1.4 Hz is the first rate that reliably clears the peak count.
        assertTrue(verdict(SignalGen.gait(6_000, stepHz = 1.4)).ok)
    }

    @Test
    fun `the specified alpha attenuates the pass band by about a third`() {
        // alpha = 0.8 at 50 Hz puts the gravity filter's -3 dB point at ~2 Hz,
        // which is the middle of the 1.2-2.6 Hz band of interest. The
        // "gravity" estimate therefore absorbs a large share of the gait
        // signal and `a = raw - g` keeps only ~65% of it. Measured, not
        // asserted from theory: peak |a| against a known input peak.
        val peakA = 12.0
        val v = verdict(SignalGen.gait(6_000, peakA = peakA, stepHz = 1.8))
        val retention = v.peakMagnitude / peakA
        assertTrue("retention was $retention", retention in 0.55..0.75)
    }

    @Test
    fun `the RMS floor requires a firm heel strike`() {
        // Consequence of the attenuation above: RMS > 1.2 m/s2 needs roughly a
        // 5.5 m/s2 input peak. A soft-footed walker, or a phone loose in a
        // bag, lands under it.
        assertTrue(verdict(SignalGen.gait(6_000, peakA = 6.0)).ok)
        val weak = verdict(SignalGen.gait(6_000, peakA = 4.0))
        assertFalse(weak.ok)
        assertEquals(GateProgress.Reason.NOT_ENOUGH_MOTION, weak.reason)
    }

    // --------------------------------------------------------------- hygiene

    @Test
    fun `too few samples yields a waiting verdict rather than a pass`() {
        val v = verdict(SignalGen.gait(200))
        assertFalse(v.ok)
        assertEquals(GateProgress.Reason.WAITING_TO_START, v.reason)
    }

    @Test
    fun `reset clears state so a new gate starts clean`() {
        val a = CadenceAnalyzer()
        SignalGen.feed(a, SignalGen.gait(6_000))
        a.reset()
        assertEquals(GateProgress.Reason.WAITING_TO_START, a.verdict(0).reason)
    }

    @Test
    fun `the peak refractory period suppresses double-counting one impulse`() {
        // A 250 ms refractory means a single heel strike, however ragged,
        // contributes at most one peak.
        val a = CadenceAnalyzer()
        // One wide burst, no repeats.
        SignalGen.feed(a, SignalGen.gait(3_000, stepHz = 0.34, peakA = 14.0))
        assertTrue("expected at most 1 peak, got ${a.verdict(3_000).peaks}", a.verdict(3_000).peaks <= 1)
    }

    @Test
    fun `angleBetweenDegrees is correct on known vectors`() {
        assertEquals(
            90.0,
            CadenceAnalyzer.angleBetweenDegrees(1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            1e-6,
        )
        assertEquals(
            0.0,
            CadenceAnalyzer.angleBetweenDegrees(0.0, 0.0, 9.8, 0.0, 0.0, 4.2),
            1e-6,
        )
        assertEquals(
            180.0,
            CadenceAnalyzer.angleBetweenDegrees(0.0, 0.0, 1.0, 0.0, 0.0, -1.0),
            1e-6,
        )
    }
}
