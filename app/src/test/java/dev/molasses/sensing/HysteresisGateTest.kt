package dev.molasses.sensing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteresisGateTest {

    @Test
    fun `entry needs the upper threshold`() {
        val g = HysteresisGate(enterAt = 1.20, exitAt = 1.05)
        assertFalse(g.update(1.19))
        assertFalse(g.update(1.10))
        assertTrue(g.update(1.20))
    }

    @Test
    fun `once inside it takes the lower threshold to leave`() {
        val g = HysteresisGate(enterAt = 1.20, exitAt = 1.05)
        g.update(1.25)
        // Values that would have failed a single 1.20 threshold.
        assertTrue(g.update(1.19))
        assertTrue(g.update(1.10))
        assertTrue(g.update(1.05))
        assertFalse(g.update(1.04))
    }

    @Test
    fun `band edge chatter cannot flip the gate`() {
        // The device failure: a walker at the band edge crossed a single
        // threshold repeatedly and every crossing zeroed the sustain streak.
        val g = HysteresisGate(enterAt = 1.20, exitAt = 1.05)
        g.update(1.21)
        val chatter = listOf(1.19, 1.21, 1.18, 1.22, 1.17, 1.20, 1.16, 1.19)
        for (v in chatter) {
            assertTrue("chatter at $v must not drop the gate", g.update(v))
        }
    }

    @Test
    fun `leaving genuinely and coming back needs the upper threshold again`() {
        val g = HysteresisGate(enterAt = 1.20, exitAt = 1.05)
        g.update(1.25)
        assertFalse(g.update(0.9))
        assertFalse("1.10 is not enough to re-enter", g.update(1.10))
        assertTrue(g.update(1.20))
    }

    @Test
    fun `reset drops the latch`() {
        val g = HysteresisGate(1.20, 1.05)
        g.update(1.5)
        g.reset()
        assertFalse(g.isInside)
        assertFalse(g.update(1.10))
    }

    @Test
    fun `an inverted pair is refused at construction`() {
        // exitAt above enterAt would make the gate flicker rather than latch.
        assertThrows(IllegalArgumentException::class.java) {
            HysteresisGate(enterAt = 1.05, exitAt = 1.20)
        }
    }

    @Test
    fun `equal thresholds are allowed and behave as a plain threshold`() {
        val g = HysteresisGate(1.2, 1.2)
        assertTrue(g.update(1.2))
        assertFalse(g.update(1.19))
    }

    @Test
    fun `the production pairs are the ones the thresholds declare`() {
        val t = Thresholds.IIR
        assertTrue("cadence exit must sit below entry", t.minHzExit < t.minHz)
        assertTrue("rms exit must sit below entry", t.minRmsExit < t.minRms)
    }
}
