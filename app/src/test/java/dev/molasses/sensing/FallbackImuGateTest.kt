package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackImuGateTest {

    @Test
    fun `eight seconds of sustained walking passes`() {
        val g = FallbackImuGate()
        val p = SignalGen.feed(g, SignalGen.gait(11_000))
        assertTrue("reason=${p.reason} fraction=${p.fraction}", p.passed)
        assertEquals(GateProgress.Reason.PASSED, p.reason)
        assertEquals(GateProgress.Path.IMU_CADENCE, p.path)
    }

    @Test
    fun `seven seconds of walking does not pass`() {
        val g = FallbackImuGate()
        val p = SignalGen.feed(g, SignalGen.gait(7_000))
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.SUSTAINING, p.reason)
        assertTrue("should be most of the way there, was ${p.fraction}", p.fraction > 0.4f)
    }

    @Test
    fun `the sustain timer resets when the conditions lapse`() {
        // Six seconds of walking, a two-second stop, then six more. Never
        // eight continuous, so it must not pass -- otherwise intermittent
        // shaking with pauses would satisfy the gate.
        val g = FallbackImuGate()
        val walk1 = SignalGen.gait(6_000)
        val stopStart = 6_000L
        val stop = SignalGen.thumbTremor(2_000).map {
            SignalGen.Sample(it.tMs + stopStart, it.x, it.y, it.z)
        }
        val walk2 = SignalGen.gait(6_000, tiltRampMs = 0).map {
            SignalGen.Sample(it.tMs + stopStart + 2_000, it.x, it.y, it.z)
        }
        var last = SignalGen.feed(g, walk1)
        last = SignalGen.feed(g, stop)
        assertFalse("stop must break the streak", last.passed)
        last = SignalGen.feed(g, walk2)
        assertFalse("only 6 s since the break, reason=${last.reason}", last.passed)
    }

    @Test
    fun `progress fraction reports how much of the sustain window is held`() {
        val g = FallbackImuGate()
        val p4 = SignalGen.feed(g, SignalGen.gait(5_000))
        val p8 = SignalGen.feed(g, SignalGen.gait(11_000))
        assertTrue(p4.fraction < p8.fraction)
        assertEquals(1f, p8.fraction, 1e-4f)
    }

    @Test
    fun `barometer drop shortens the sustain requirement to four seconds`() {
        val g = FallbackImuGate()
        // A monotonic 0.06 hPa drop over 5 s: standing up.
        var hPa = 1013.25
        for (t in 0..5_000 step 250) {
            g.onPressure(t.toLong(), hPa)
            hPa -= 0.003
        }
        assertTrue("shortcut should be armed", g.hasBarometerShortcut)

        val p = SignalGen.feed(g, SignalGen.gait(7_000))
        assertTrue("4 s sustain should suffice, reason=${p.reason}", p.passed)
    }

    @Test
    fun `a noisy non-monotonic pressure trace does not arm the shortcut`() {
        val g = FallbackImuGate()
        val trace = listOf(1013.25, 1013.22, 1013.28, 1013.21, 1013.26, 1013.20)
        trace.forEachIndexed { i, p -> g.onPressure(i * 500L, p) }
        assertFalse(g.hasBarometerShortcut)
    }

    @Test
    fun `a drop below the threshold does not arm the shortcut`() {
        val g = FallbackImuGate()
        // 0.02 hPa total: within noise, roughly 17 cm.
        var hPa = 1013.25
        for (t in 0..5_000 step 500) {
            g.onPressure(t.toLong(), hPa)
            hPa -= 0.002
        }
        assertFalse(g.hasBarometerShortcut)
    }

    @Test
    fun `the barometer is never required`() {
        // Most budget devices have no barometer at all; the gate must still be
        // clearable without one.
        val g = FallbackImuGate()
        assertFalse(g.hasBarometerShortcut)
        assertTrue(SignalGen.feed(g, SignalGen.gait(11_000)).passed)
    }

    @Test
    fun `desk tapping cannot be sustained into a pass`() {
        val g = FallbackImuGate()
        val p = SignalGen.feed(g, SignalGen.deskTap(20_000))
        assertFalse("20 s of desk tapping must not pass", p.passed)
        assertEquals(GateProgress.Reason.PHONE_STATIONARY, p.reason)
    }

    @Test
    fun `violent shaking cannot be sustained into a pass`() {
        val g = FallbackImuGate()
        val p = SignalGen.feed(g, SignalGen.violentShake(20_000))
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.TOO_VIOLENT, p.reason)
    }

    @Test
    fun `reset clears both the streak and the barometer shortcut`() {
        val g = FallbackImuGate()
        var hPa = 1013.25
        for (t in 0..5_000 step 250) { g.onPressure(t.toLong(), hPa); hPa -= 0.003 }
        SignalGen.feed(g, SignalGen.gait(11_000))
        g.reset()
        assertFalse(g.hasBarometerShortcut)
        assertFalse(SignalGen.feed(g, SignalGen.gait(2_000)).passed)
    }
}
