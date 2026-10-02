package dev.molasses.core.lease

import dev.molasses.core.bit.BitStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateReadoutTest {

    private val minute = 60_000L

    @Test
    fun `the three numbers render as durations and a count`() {
        val f = GateReadout.fields(
            todayMs = 2 * 60 * minute + 14 * minute,
            cycleMs = 38 * minute,
            opensToday = 17,
            remainingMs = 8_000L,
        )
        assertEquals("2h14m", f.today)
        assertEquals("38m", f.cycle)
        assertEquals("17", f.opens)
    }

    @Test
    fun `an unavailable reading is two dashes and not a zero`() {
        val f = GateReadout.fields(null, null, null, 8_000L)
        assertEquals(GateReadout.UNKNOWN, f.today)
        assertEquals(GateReadout.UNKNOWN, f.cycle)
        assertEquals(GateReadout.UNKNOWN, f.opens)
    }

    @Test
    fun `a real zero is distinguishable from an unavailable one`() {
        val f = GateReadout.fields(0L, 0L, 0, 8_000L)
        assertEquals("0m", f.today)
        assertEquals("0m", f.cycle)
        assertEquals("0", f.opens)
    }

    @Test
    fun `a negative open count is unavailable rather than trusted`() {
        assertEquals(GateReadout.UNKNOWN, GateReadout.fields(null, null, -1, 0L).opens)
    }

    @Test
    fun `the countdown rounds up so it opens on the number offered`() {
        assertEquals("8", GateReadout.countdown(8_000L))
        assertEquals("8", GateReadout.countdown(7_999L))
        assertEquals("8", GateReadout.countdown(7_001L))
        assertEquals("7", GateReadout.countdown(7_000L))
        assertEquals("1", GateReadout.countdown(1L))
    }

    @Test
    fun `the countdown reads zero only when it is zero`() {
        assertEquals("0", GateReadout.countdown(0L))
        assertEquals("0", GateReadout.countdown(-1L))
    }

    @Test
    fun `the face is flat while waiting and neutral at zero`() {
        assertEquals(BitStateMachine.BLINK_HALF, GateReadout.faceFor(1L))
        assertEquals(BitStateMachine.NEUTRAL, GateReadout.faceFor(0L))
        assertEquals(GateReadout.FACE_READY, GateReadout.fields(null, null, null, 0L).face)
    }

    @Test
    fun `both faces are real faces`() {
        assertTrue(GateReadout.FACE_WAITING in BitStateMachine.FACES)
        assertTrue(GateReadout.FACE_READY in BitStateMachine.FACES)
    }

    @Test
    fun `the panel is up only at zero`() {
        assertFalse(GateReadout.panelUp(1L))
        assertTrue(GateReadout.panelUp(0L))
    }

    @Test
    fun `lease buttons are labelled in whole minutes`() {
        assertEquals(
            listOf("5m", "10m", "15m"),
            LeaseLadder.OFFERED_MS.map { GateReadout.leaseLabel(it) },
        )
    }

    @Test
    fun `the numeral and the playhead's seconds are one value`() {
        for (ms in listOf(30_000L, 29_999L, 24_001L, 8_000L, 7_001L, 1L, 0L, -5L)) {
            val f = GateReadout.fields(todayMs = null, cycleMs = null, opensToday = null, remainingMs = ms)
            assertEquals("at ${ms}ms", f.countdown, f.remainingSec.toString())
            assertEquals(GateReadout.countdown(ms), f.countdown)
        }
        assertEquals(0, GateReadout.remainingSeconds(-1L))
        assertEquals(8, GateReadout.remainingSeconds(7_001L))
    }
}
