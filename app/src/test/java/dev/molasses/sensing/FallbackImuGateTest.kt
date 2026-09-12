package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackImuGateTest {

    private fun walk(durationMs: Long, sampleHz: Int = 50, tiltRampMs: Long = 400) =
        SignalGen.gait(
            durationMs, jitterFrac = 0.08, sampleHz = sampleHz, tiltRampMs = tiltRampMs,
        )

    private fun feedFused(gate: FallbackImuGate, samples: List<SignalGen.Sample>) =
        samples.map {
            gate.onFusedSample(
                it.timestampNs, it.linearX, it.linearY, it.linearZ,
                it.trueGx, it.trueGy, it.trueGz,
            )
        }.last()

    // ------------------------------------------------------------------ sustain

    @Test
    fun `sustained walking passes on the IIR path`() {
        val g = FallbackImuGate(FallbackImuGate.Mode.IIR)
        val p = SignalGen.feedIir(g, walk(16_000))
        assertTrue("reason=${p.reason} fraction=${p.fraction}", p.passed)
        assertEquals(GateProgress.Path.IMU_IIR, p.path)
    }

    @Test
    fun `sustained walking passes on the fused path`() {
        val g = FallbackImuGate(FallbackImuGate.Mode.FUSED)
        val p = feedFused(g, walk(16_000))
        assertTrue("reason=${p.reason} fraction=${p.fraction}", p.passed)
        assertEquals(GateProgress.Path.IMU_FUSED, p.path)
        assertEquals(Thresholds.FUSED, g.thresholds)
    }

    @Test
    fun `the two paths carry separate threshold sets`() {
        // SS1.1: the fused sensor has an internal high-pass we neither control
        // nor can query, so it is a second calibration domain.
        assertEquals("IIR", FallbackImuGate(FallbackImuGate.Mode.IIR).thresholds.id)
        assertEquals("FUSED", FallbackImuGate(FallbackImuGate.Mode.FUSED).thresholds.id)
        assertEquals(
            Thresholds.Calibration.UNCALIBRATED,
            Thresholds.FUSED.calibration,
        )
        assertEquals(
            Thresholds.Calibration.SYNTHETIC_SWEEP,
            Thresholds.IIR.calibration,
        )
    }

    @Test
    fun `a short walk does not pass`() {
        val g = FallbackImuGate()
        val p = SignalGen.feedIir(g, walk(7_000))
        assertFalse(p.passed)
        assertTrue("should be part-way, was ${p.fraction}", p.fraction < 1f)
    }

    @Test
    fun `the sustain timer resets when the conditions lapse`() {
        // Six seconds of walking, two of stillness, then six more. Never eight
        // continuous, so it must not pass -- otherwise intermittent shaking
        // with pauses would satisfy the gate.
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(6_000))
        val stop = SignalGen.thumbTremor(2_000).map {
            SignalGen.Sample(
                it.timestampNs + 6_000_000_000, it.x, it.y, it.z,
                it.trueGx, it.trueGy, it.trueGz,
            )
        }
        var last = SignalGen.feedIir(g, stop)
        assertFalse("stillness must break the streak", last.passed)
        val more = walk(6_000, tiltRampMs = 0).map {
            SignalGen.Sample(
                it.timestampNs + 8_000_000_000, it.x, it.y, it.z,
                it.trueGx, it.trueGy, it.trueGz,
            )
        }
        last = SignalGen.feedIir(g, more)
        assertFalse("only 6 s since the break, reason=${last.reason}", last.passed)
    }

    @Test
    fun `progress fraction reports how much of the sustain window is held`() {
        val short = SignalGen.feedIir(FallbackImuGate(), walk(8_000))
        val long = SignalGen.feedIir(FallbackImuGate(), walk(16_000))
        assertTrue(short.fraction < long.fraction)
        assertEquals(1f, long.fraction, 1e-4f)
    }

    // ----------------------------------------------------------- delivery rate

    @Test
    fun `the gate passes at every delivery rate`() {
        for (rate in listOf(25, 50, 100, 200)) {
            val g = FallbackImuGate()
            val p = SignalGen.feedIir(g, walk(16_000, sampleHz = rate))
            assertTrue("$rate Hz gave ${p.reason}", p.passed)
        }
    }

    @Test
    fun `the splitter reports the alpha it derived`() {
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(4_000, sampleHz = 100))
        // 100 Hz, tau 0.65 -> alpha = 0.65 / 0.66.
        assertEquals(0.65 / 0.66, g.lastAlpha, 1e-4)
    }

    // ------------------------------------------------------------- barometer

    @Test
    fun `barometer drop shortens the sustain requirement`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 250) { g.onPressure(t.toLong(), hPa); hPa -= 0.003 }
        assertTrue("shortcut should be armed", g.hasBarometerShortcut)
        val p = SignalGen.feedIir(g, walk(11_000))
        assertTrue("4 s sustain should suffice, reason=${p.reason}", p.passed)
    }

    @Test
    fun `a noisy non-monotonic pressure trace does not arm the shortcut`() {
        val g = FallbackImuGate()
        listOf(1013.25, 1013.22, 1013.28, 1013.21, 1013.26, 1013.20)
            .forEachIndexed { i, p -> g.onPressure(i * 500L, p) }
        assertFalse(g.hasBarometerShortcut)
    }

    @Test
    fun `a drop below the threshold does not arm the shortcut`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 500) { g.onPressure(t.toLong(), hPa); hPa -= 0.002 }
        assertFalse(g.hasBarometerShortcut)
    }

    @Test
    fun `the barometer is never required`() {
        val g = FallbackImuGate()
        assertFalse(g.hasBarometerShortcut)
        assertTrue(SignalGen.feedIir(g, walk(16_000)).passed)
    }

    // --------------------------------------------------------------- rejection

    @Test
    fun `desk tapping cannot be sustained into a pass`() {
        val p = SignalGen.feedIir(FallbackImuGate(), SignalGen.deskTap(24_000))
        assertFalse("24 s of desk tapping must not pass", p.passed)
    }

    @Test
    fun `violent shaking cannot be sustained into a pass`() {
        val p = SignalGen.feedIir(FallbackImuGate(), SignalGen.violentShake(24_000))
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.TOO_VIOLENT, p.reason)
    }

    @Test
    fun `reset clears the streak, the shortcut and the filter`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 250) { g.onPressure(t.toLong(), hPa); hPa -= 0.003 }
        SignalGen.feedIir(g, walk(16_000))
        g.reset()
        assertFalse(g.hasBarometerShortcut)
        assertFalse(SignalGen.feedIir(g, walk(2_000)).passed)
    }
}
