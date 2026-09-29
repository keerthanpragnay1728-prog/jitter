package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackImuGateTest {

    private fun walk(durationMs: Long, sampleHz: Int = 50, tiltRampMs: Long = 400) =
        SignalGen.gait(
            durationMs, jitterFrac = 0.08, sampleHz = sampleHz, tiltRampMs = tiltRampMs,
        )

    private fun shift(samples: List<SignalGen.Sample>, byMs: Long) = samples.map {
        SignalGen.Sample(
            it.timestampNs + byMs * 1_000_000, it.x, it.y, it.z,
            it.trueGx, it.trueGy, it.trueGz,
        )
    }

    // ------------------------------------------------------------- clearing

    @Test
    fun `sustained walking clears on the IIR path`() {
        val g = FallbackImuGate(FallbackImuGate.Mode.IIR)
        val last = SignalGen.feedIir(g, walk(20_000))
        assertNotNull(last)
        assertTrue("reason=${last!!.progress.reason} credit=${g.creditMs}", last.passed)
        assertEquals(GateProgress.Path.IMU_IIR, last.progress.path)
    }

    @Test
    fun `sustained walking clears on the fused path`() {
        val g = FallbackImuGate(FallbackImuGate.Mode.FUSED)
        val last = SignalGen.feedFused(g, walk(20_000))
        assertNotNull(last)
        assertTrue("reason=${last!!.progress.reason}", last.passed)
        assertEquals(GateProgress.Path.IMU_FUSED, last.progress.path)
        assertEquals(Thresholds.FUSED, g.thresholds)
    }

    @Test
    fun `the two paths carry separate threshold sets`() {
        assertEquals("IIR", FallbackImuGate(FallbackImuGate.Mode.IIR).thresholds.id)
        assertEquals("FUSED", FallbackImuGate(FallbackImuGate.Mode.FUSED).thresholds.id)
        assertEquals(Thresholds.Calibration.UNCALIBRATED, Thresholds.FUSED.calibration)
        assertEquals(Thresholds.Calibration.SYNTHETIC_SWEEP, Thresholds.IIR.calibration)
    }

    @Test
    fun `a short walk does not clear`() {
        val g = FallbackImuGate()
        val last = SignalGen.feedIir(g, walk(7_000))
        assertNotNull(last)
        assertFalse(last!!.passed)
        assertTrue("fraction was ${last.progress.fraction}", last.progress.fraction < 1f)
    }

    // ----------------------------------------------------- tick, not sample

    @Test
    fun `the battery runs on a fixed tick regardless of delivery rate`() {
        // The tick grid is anchored, so the same wall duration yields the same
        // tick count and the same credit per tick at every delivery rate. An
        // earlier version reset the grid to the sample time, which made the
        // realised period 280 ms at 25 Hz against 250 ms at 200 Hz.
        val counts = listOf(25, 50, 100, 200).map { rate ->
            val ticks = SignalGen.ticksIir(FallbackImuGate(), walk(12_000, sampleHz = rate))
            rate to ticks
        }
        for ((rate, ticks) in counts) {
            val dts = ticks.mapNotNull { it.tick?.dtMs }.toSet()
            assertEquals("tick duration at $rate Hz", setOf(250L), dts)
        }
        val sizes = counts.map { it.second.size }
        println("ticks over 12 s per rate: ${counts.map { it.first to it.second.size }}")
        assertEquals("tick count must not depend on delivery rate", 1, sizes.toSet().size)
    }

    @Test
    fun `every delivery rate clears`() {
        for (rate in listOf(25, 50, 100, 200)) {
            val g = FallbackImuGate()
            val last = SignalGen.feedIir(g, walk(20_000, sampleHz = rate))
            assertTrue(
                "$rate Hz gave ${last?.progress?.reason} credit=${g.creditMs}",
                last?.passed == true,
            )
        }
    }

    @Test
    fun `the splitter reports the alpha it derived`() {
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(4_000, sampleHz = 100))
        assertEquals(0.65 / 0.66, g.lastAlpha, 1e-4)
    }

    // --------------------------------------------------- bucket, not streak

    @Test
    fun `a one second stumble costs a little progress and does not erase it`() {
        // The streak this replaced would have zeroed everything here, which is
        // what made the gate unclearable on the device.
        //
        // The cost is small because 1 s of stillness sits inside a 3.5 s
        // analysis window, so most of the window is still walking and only the
        // ticks where the measurement actually drops below a threshold fail.
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(6_000))
        val before = g.creditMs
        assertTrue("expected credit before the stumble, got $before", before > 3_000)

        SignalGen.feedIir(g, shift(SignalGen.thumbTremor(1_000), 6_000))
        val after = g.creditMs
        println("stumble cost: $before -> $after")
        assertTrue("stumble must cost something", after < before)
        assertTrue(
            "stumble must not erase everything: $before -> $after",
            after > before * 0.8,
        )
    }

    @Test
    fun `walking through a stumble still clears`() {
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(6_000))
        SignalGen.feedIir(g, shift(SignalGen.thumbTremor(1_000), 6_000))
        val last = SignalGen.feedIir(g, shift(walk(16_000, tiltRampMs = 0), 7_000))
        assertTrue("reason=${last?.progress?.reason} credit=${g.creditMs}", last?.passed == true)
    }

    @Test
    fun `standing still drains the bucket to zero`() {
        val g = FallbackImuGate()
        SignalGen.feedIir(g, walk(6_000))
        assertTrue(g.creditMs > 0)
        SignalGen.feedIir(g, shift(SignalGen.static(30_000), 6_000))
        assertEquals("sustained failure must drain to empty", 0L, g.creditMs)
    }

    @Test
    fun `progress fraction tracks the bucket`() {
        val shortRun = FallbackImuGate().also { SignalGen.feedIir(it, walk(6_000)) }
        val longRun = FallbackImuGate().also { SignalGen.feedIir(it, walk(20_000)) }
        assertTrue(shortRun.creditMs < longRun.creditMs)
        assertEquals(1f, SignalGen.feedIir(FallbackImuGate(), walk(20_000))!!.progress.fraction, 1e-4f)
    }

    // ------------------------------------------------------------- barometer

    @Test
    fun `barometer drop halves the credit still needed`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 250) { g.onPressure(t.toLong(), hPa); hPa -= 0.003 }
        assertTrue("shortcut should be armed", g.hasBarometerShortcut)
        val last = SignalGen.feedIir(g, walk(12_000))
        assertTrue("4 s of walking should suffice, credit=${g.creditMs}", last?.passed == true)
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
        assertTrue(SignalGen.feedIir(g, walk(20_000))?.passed == true)
    }

    // --------------------------------------------------------------- rejects

    @Test
    fun `desk tapping never clears however long it runs`() {
        val g = FallbackImuGate()
        val last = SignalGen.feedIir(g, SignalGen.deskTap(60_000))
        assertFalse("60 s of desk tapping must not clear", last?.passed == true)
        assertEquals(0L, g.creditMs)
    }

    @Test
    fun `violent shaking never clears`() {
        val g = FallbackImuGate()
        val last = SignalGen.feedIir(g, SignalGen.violentShake(60_000))
        assertFalse(last?.passed == true)
        assertEquals(GateProgress.Reason.TOO_VIOLENT, last?.progress?.reason)
    }

    @Test
    fun `alternating pass and fail cannot clear`() {
        // Credit accrues at 1x and drains at 0.5x, so a 50% pass rate still
        // gains. It must not gain fast enough to be a bypass, and sustained
        // failure must still lose. This checks the asymmetry holds the right
        // way round for a signal that is half cheating.
        val g = FallbackImuGate()
        var t = 0L
        repeat(10) {
            SignalGen.feedIir(g, shift(walk(1_000, tiltRampMs = 0), t)); t += 1_000
            SignalGen.feedIir(g, shift(SignalGen.static(1_000), t)); t += 1_000
        }
        assertTrue(
            "half-cheating should not clear in 20 s, credit=${g.creditMs}",
            g.creditMs < 8_000,
        )
    }

    @Test
    fun `reset clears the bucket, the shortcut and the filter`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 250) { g.onPressure(t.toLong(), hPa); hPa -= 0.003 }
        SignalGen.feedIir(g, walk(20_000))
        g.reset()
        assertFalse(g.hasBarometerShortcut)
        assertEquals(0L, g.creditMs)
        assertEquals(0, g.ticks)
    }
}
