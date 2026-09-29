package dev.molasses.sensing

import dev.molasses.core.model.GateProgress.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every cheat class, asserted against the *specific* test that is supposed to
 * catch it -- not merely against "the gate failed". A rejection for the wrong
 * reason is a threshold that happens to be mis-set in a direction nobody
 * noticed, and it stops protecting anything the moment the other threshold
 * moves.
 */
class MovementRejectionTest {

    private fun iir(samples: List<SignalGen.Sample>) = SignalGen.verdictIir(samples)
    private fun fused(samples: List<SignalGen.Sample>) = SignalGen.verdictFused(samples)

    /** Realistic walking: jittered, because metronome-flat is now a cheat class. */
    private fun walk(
        peakA: Double = 12.0,
        hz: Double = 1.8,
        tiltDeg: Double = 40.0,
        sampleHz: Int = 50,
    ) = SignalGen.gait(
        14_000, stepHz = hz, peakA = peakA, tiltDeg = tiltDeg,
        sampleHz = sampleHz, jitterFrac = 0.08,
    )

    // --------------------------------------------------------------- accepts

    @Test
    fun `ordinary walking passes on both pipelines`() {
        val i = iir(walk())
        assertTrue("IIR: ${i.reason} rms=${i.rms} cv=${i.cv}", i.ok)
        val f = fused(walk())
        assertTrue("FUSED: ${f.reason} rms=${f.rms} cv=${f.cv}", f.ok)
    }

    @Test
    fun `walking anywhere in the band passes`() {
        for (hz in listOf(1.25, 1.4, 1.6, 1.8, 2.0, 2.2, 2.4)) {
            val v = iir(walk(hz = hz))
            assertTrue("$hz Hz gave ${v.reason} (peaks=${v.peaks}, cv=${v.cv})", v.ok)
        }
    }

    // ----------------------------------------- desk tap: the SS3 regression case

    @Test
    fun `desk tapping is rejected by two independent tests`() {
        // The regression case worth pinning. A phone lying flat being tapped
        // produces a signal that clears the motion floor, sits in the cadence
        // band, and is 99% vertical. Before the CV floor existed, the gravity
        // angle was the *only* thing separating it from walking -- a single
        // threshold standing between a trivial bypass and the product.
        val v = iir(SignalGen.deskTap(14_000))
        assertFalse("desk tapping must not pass", v.ok)

        assertTrue(
            "expected the gravity-angle test to fire; failures were ${v.failures}",
            Reason.PHONE_STATIONARY in v.failures,
        )
        assertTrue(
            "expected the CV floor to fire too; failures were ${v.failures} (cv=${v.cv})",
            Reason.TOO_REGULAR in v.failures,
        )
        assertTrue(
            "two independent tests must fire, saw ${v.failures}",
            v.failures.size >= 2,
        )

        // And note how convincing it otherwise looks:
        assertTrue("desk tap clears the motion floor: ${v.rms}", v.rms > Thresholds.IIR.minRms)
        assertTrue("and its cadence is in band: ${v.dominantHz}", v.dominantHz in 1.2..2.6)
        assertTrue("and it is almost purely vertical: ${v.verticalShare}", v.verticalShare > 0.9)
    }

    @Test
    fun `a metronomic source is rejected even when it is genuinely moving`() {
        // Tilt and amplitude both fine; only the regularity gives it away.
        val v = iir(SignalGen.gait(14_000, jitterFrac = 0.0, tiltDeg = 40.0, peakA = 12.0))
        assertFalse("perfectly metronomic motion must not pass, cv=${v.cv}", v.ok)
        assertEquals(Reason.TOO_REGULAR, v.reason)
    }

    // ---------------------------------------------------------------- rejects

    @Test
    fun `thumb tremor is rejected on RMS`() {
        val v = iir(SignalGen.thumbTremor(14_000))
        assertFalse(v.ok)
        assertTrue(Reason.NOT_ENOUGH_MOTION in v.failures)
        assertTrue("tremor RMS far below the floor: ${v.rms}", v.rms < 0.4)
    }

    @Test
    fun `a phone lying perfectly still is rejected`() {
        val v = iir(SignalGen.static(14_000))
        assertFalse(v.ok)
        assertTrue(Reason.NOT_ENOUGH_MOTION in v.failures)
        assertTrue(Reason.PHONE_STATIONARY in v.failures)
    }

    @Test
    fun `tilt below the threshold is rejected`() {
        val v = iir(walk(tiltDeg = 10.0))
        assertFalse(v.ok)
        assertTrue(Reason.PHONE_STATIONARY in v.failures)
    }

    @Test
    fun `lateral hand-waving is rejected on vertical energy share`() {
        val v = iir(SignalGen.lateralWave(14_000))
        assertFalse(v.ok)
        assertTrue("failures=${v.failures}", Reason.MOTION_NOT_VERTICAL in v.failures)
        assertTrue("vertical share was ${v.verticalShare}", v.verticalShare < 0.5)
    }

    @Test
    fun `violent shaking is rejected on peak magnitude`() {
        val v = iir(SignalGen.violentShake(14_000))
        assertFalse(v.ok)
        assertTrue(Reason.TOO_VIOLENT in v.failures)
        assertTrue(v.peakMagnitude >= Thresholds.IIR.maxPeakMagnitude)
    }

    @Test
    fun `erratic flicking is rejected on the CV ceiling`() {
        val v = iir(SignalGen.gait(14_000, stepHz = 1.9, jitterFrac = 1.6))
        assertFalse("cv=${v.cv} reason=${v.reason}", v.ok)
    }

    // --------------------------------------------- the refractory aliasing hole

    @Test
    fun `shaking that aliases into the band is rejected on both pipelines`() {
        // Found by the SS4 sweep, and the reason the aliasing guard exists.
        // The 250 ms refractory is a hard decimator: a 4.5 Hz shake has
        // alternate peaks dropped and the survivors read as 2.17 Hz with a
        // CV of 0.035 -- in band, in range, and on the fused pipeline it
        // passed every other test.
        for (hz in listOf(4.0, 4.5, 5.0, 6.0)) {
            for (jitter in listOf(0.0, 0.15)) {
                val sig = SignalGen.gait(
                    14_000, stepHz = hz, peakA = 10.0, jitterFrac = jitter,
                    tiltDeg = 45.0, burstWidthMs = 100.0,
                )
                val i = iir(sig)
                val f = fused(sig)
                assertFalse("IIR passed ${hz}Hz jitter=$jitter: ${i.reason}", i.ok)
                assertFalse("FUSED passed ${hz}Hz jitter=$jitter: ${f.reason}", f.ok)
                assertTrue(
                    "FUSED ${hz}Hz should read too fast, got ${f.reason} at ${f.dominantHz}Hz",
                    Reason.CADENCE_TOO_FAST in f.failures,
                )
            }
        }
    }

    @Test
    fun `the aliasing guard does not fire on real gait`() {
        // The guard's first implementation counted every refractory-suppressed
        // candidate and flagged real walking at a ratio of 0.86, because the
        // IIR filter leaves a ringing peak 80 ms after each heel strike.
        // Requiring the suppressed peak to be at least half the amplitude of
        // the accepted one separates 0.19 from 0.95.
        for (peakA in listOf(6.0, 12.0, 20.0)) {
            for (hz in listOf(1.3, 1.8, 2.4)) {
                val v = iir(walk(peakA = peakA, hz = hz))
                assertEquals(
                    "gait at ${hz}Hz A=$peakA suppressed ${v.suppressedPeaks} peaks",
                    0, v.suppressedPeaks,
                )
            }
        }
    }

    // ------------------------------------------------------- rate independence

    @Test
    fun `verdicts hold across delivery rates`() {
        for (rate in listOf(25, 50, 100, 200)) {
            assertTrue("walk at $rate Hz", iir(walk(sampleHz = rate)).ok)
            assertFalse(
                "desk tap at $rate Hz",
                iir(SignalGen.deskTap(14_000, sampleHz = rate)).ok,
            )
        }
    }
}
