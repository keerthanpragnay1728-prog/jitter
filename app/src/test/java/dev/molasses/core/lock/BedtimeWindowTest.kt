package dev.molasses.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeWindowTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `an evening bedtime runs to the morning`() {
        assertEquals(7 * hour, BedtimeWindow.durationMs(23 * 60))
        assertEquals(8 * hour, BedtimeWindow.durationMs(22 * 60))
    }

    @Test
    fun `after midnight it is the same morning, not the next one`() {
        // The failure this catches: wrapping unconditionally and locking
        // someone out for 23 hours because they typed it at 1am.
        assertEquals(5 * hour, BedtimeWindow.durationMs(60))
        assertEquals(30 * minute, BedtimeWindow.durationMs(5 * 60 + 30))
    }

    @Test
    fun `at the wake time exactly it is a full day, never a no-op`() {
        // A command that silently did nothing at one minute of the day is an
        // edge the user only finds at the moment they needed it.
        assertEquals(24 * hour, BedtimeWindow.durationMs(BedtimeWindow.WAKE_MINUTE_OF_DAY))
    }

    @Test
    fun `a duration is always positive`() {
        for (m in 0 until BedtimeWindow.MINUTES_PER_DAY) {
            assertTrue("minute $m", BedtimeWindow.durationMs(m) > 0L)
        }
    }

    @Test
    fun `an out of range minute wraps rather than throwing`() {
        assertEquals(BedtimeWindow.durationMs(60), BedtimeWindow.durationMs(24 * 60 + 60))
        assertEquals(BedtimeWindow.durationMs(23 * 60), BedtimeWindow.durationMs(-60))
    }

    @Test
    fun `a bedtime lock is inside what the parser accepts`() {
        // It goes through the same LockRegistry as a typed lock, so it has to
        // sit under the ladder's ceiling or it would be the one lock the
        // grammar could not have produced.
        for (m in 0 until BedtimeWindow.MINUTES_PER_DAY) {
            assertTrue("minute $m", BedtimeWindow.durationMs(m) <= LockLadder.MAX_MS)
        }
    }
}
