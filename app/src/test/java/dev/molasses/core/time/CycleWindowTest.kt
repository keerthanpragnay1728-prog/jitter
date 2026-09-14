package dev.molasses.core.time

import dev.molasses.core.model.CycleResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-hour window, in isolation from the engine.
 *
 * Every case here is a clock the user could actually produce: settings has a
 * date and time screen, and a reboot needs no tooling either.
 */
class CycleWindowTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val window = CycleResetPolicy.WINDOW_MS

    private val wallBase = 1_700_000_000_000L

    /** Both clocks in step, which is what an untampered device looks like. */
    private fun honest(offsetMs: Long, boot: Int = 1) =
        StampedInstant(wallMs = wallBase + offsetMs, elapsedMs = offsetMs, bootId = boot)

    private val anchor = honest(0)

    @Test
    fun `an unset anchor is never due and reports a full window`() {
        val now = honest(48 * hour)
        assertFalse(CycleWindow.isDue(StampedInstant.UNSET, now))
        assertEquals(0L, CycleWindow.ageMs(StampedInstant.UNSET, now))
        assertEquals(window, CycleWindow.remainingMs(StampedInstant.UNSET, now))
    }

    @Test
    fun `not due one millisecond before the deadline, due at it`() {
        assertFalse(CycleWindow.isDue(anchor, honest(window - 1)))
        assertTrue(CycleWindow.isDue(anchor, honest(window)))
    }

    @Test
    fun `remaining counts down and never goes negative`() {
        assertEquals(window, CycleWindow.remainingMs(anchor, honest(0)))
        assertEquals(window - 90 * min, CycleWindow.remainingMs(anchor, honest(90 * min)))
        assertEquals(0L, CycleWindow.remainingMs(anchor, honest(window)))
        assertEquals(0L, CycleWindow.remainingMs(anchor, honest(48 * hour)))
    }

    @Test
    fun `moving the system clock forward six hours credits nothing`() {
        // Two minutes of real time have passed. The user has set the clock
        // forward six hours on top of that.
        val now = StampedInstant(
            wallMs = wallBase + 2 * min + 6 * hour,
            elapsedMs = 2 * min,
            bootId = 1,
        )
        assertEquals(2 * min, CycleWindow.ageMs(anchor, now))
        assertFalse(CycleWindow.isDue(anchor, now))
        assertEquals(window - 2 * min, CycleWindow.remainingMs(anchor, now))
    }

    @Test
    fun `moving the system clock backward does not extend the window`() {
        // Five hours of real time, wall clock wound back two.
        val now = StampedInstant(
            wallMs = wallBase + 3 * hour,
            elapsedMs = 5 * hour,
            bootId = 1,
        )
        // min(3h, 5h) is the clamp's answer; it under-credits rather than
        // letting the user bank a negative gap.
        assertEquals(3 * hour, CycleWindow.ageMs(anchor, now))
        assertEquals(window - 3 * hour, CycleWindow.remainingMs(anchor, now))
    }

    @Test
    fun `a reboot preserves the age, measured on the wall clock`() {
        // Anchored, then rebooted; two hours of wall time have passed and the
        // monotonic clock has restarted near zero.
        val now = StampedInstant(
            wallMs = wallBase + 2 * hour,
            elapsedMs = 90_000,
            bootId = 2,
        )
        assertEquals(2 * hour, CycleWindow.ageMs(anchor, now))
        assertFalse(CycleWindow.isDue(anchor, now))
        assertEquals(window - 2 * hour, CycleWindow.remainingMs(anchor, now))
    }

    @Test
    fun `a reboot with the clock wound back credits nothing rather than negative time`() {
        val now = StampedInstant(
            wallMs = wallBase - 3 * hour,
            elapsedMs = 60_000,
            bootId = 2,
        )
        assertEquals(0L, CycleWindow.ageMs(anchor, now))
        assertEquals(window, CycleWindow.remainingMs(anchor, now))
    }

    @Test
    fun `a full window elapses across a reboot`() {
        val now = StampedInstant(
            wallMs = wallBase + window,
            elapsedMs = 10 * min,
            bootId = 2,
        )
        assertTrue(CycleWindow.isDue(anchor, now))
    }

    @Test
    fun `drift under the clamp tolerance is credited as monotonic time`() {
        // Clocks disagree by 30 s, which is below ClockTamperClamp.TOLERANCE_MS
        // and is ordinary NTP correction rather than tampering.
        val now = StampedInstant(
            wallMs = wallBase + 3 * hour + 30_000,
            elapsedMs = 3 * hour,
            bootId = 1,
        )
        assertEquals(3 * hour, CycleWindow.ageMs(anchor, now))
    }
}
