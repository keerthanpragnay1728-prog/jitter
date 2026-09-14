package dev.molasses.core.safety

import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseWindowTest {

    private val wallBase = 1_700_000_000_000L
    private val min = 60_000L

    private fun honest(offsetMs: Long, boot: Int = 1) =
        StampedInstant(wallMs = wallBase + offsetMs, elapsedMs = offsetMs, bootId = boot)

    private val started = honest(0)

    @Test
    fun `an unset pause is not active and has no remainder`() {
        assertFalse(PauseWindow.isActive(StampedInstant.UNSET, honest(0)))
        assertEquals(0L, PauseWindow.remainingMs(StampedInstant.UNSET, honest(0)))
    }

    @Test
    fun `counts down and expires at fifteen minutes`() {
        assertEquals(15 * min, PauseWindow.remainingMs(started, honest(0)))
        assertEquals(10 * min, PauseWindow.remainingMs(started, honest(5 * min)))
        assertTrue(PauseWindow.isActive(started, honest(15 * min - 1)))
        assertEquals(0L, PauseWindow.remainingMs(started, honest(15 * min)))
        assertFalse(PauseWindow.isActive(started, honest(15 * min)))
    }

    @Test
    fun `never reports a negative remainder`() {
        assertEquals(0L, PauseWindow.remainingMs(started, honest(48 * 60 * min)))
    }

    @Test
    fun `winding the clock back does not hold the pause open`() {
        // Sixteen real minutes; wall clock wound back an hour on top. Under
        // the cycle's min(wall, elapsed) clamp this credits zero and the
        // pause never ends, which is why a pause does not use that clamp.
        val now = StampedInstant(
            wallMs = wallBase + 16 * min - 60 * min,
            elapsedMs = 16 * min,
            bootId = 1,
        )
        assertFalse(PauseWindow.isActive(started, now))
    }

    @Test
    fun `the wall clock cannot extend a pause by any amount`() {
        // One real minute of elapsedRealtime, wall clock moved a week back.
        val now = StampedInstant(
            wallMs = wallBase - 7 * 24 * 60 * min,
            elapsedMs = 1 * min,
            bootId = 1,
        )
        assertEquals(14 * min, PauseWindow.remainingMs(started, now))
    }

    @Test
    fun `a corrupt stamp from the future reads as expired`() {
        val now = StampedInstant(wallMs = wallBase, elapsedMs = -5 * min, bootId = 1)
        assertFalse(PauseWindow.isActive(started, now))
    }

    @Test
    fun `winding the clock forward does not end the pause early`() {
        // One real minute; wall clock pushed forward an hour.
        val now = StampedInstant(
            wallMs = wallBase + 1 * min + 60 * min,
            elapsedMs = 1 * min,
            bootId = 1,
        )
        assertTrue(PauseWindow.isActive(started, now))
        assertEquals(14 * min, PauseWindow.remainingMs(started, now))
    }

    @Test
    fun `a reboot ends the pause outright`() {
        // Even one minute in. There is no elapsedRealtime reading that spans
        // a boot, and for a pause "expired" is the safe answer.
        val now = StampedInstant(wallMs = wallBase + 1 * min, elapsedMs = 60_000, bootId = 2)
        assertFalse(PauseWindow.isActive(started, now))
    }
}
