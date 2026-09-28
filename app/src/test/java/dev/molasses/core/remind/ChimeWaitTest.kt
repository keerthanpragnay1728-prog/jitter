package dev.molasses.core.remind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeWaitTest {

    @Test
    fun `waits while playing, up to the cap and never past it`() {
        assertTrue(ChimeWait.keepWaiting(100, playing = true, everPlayed = true))
        assertTrue(ChimeWait.keepWaiting(2_999, playing = true, everPlayed = true))
        assertFalse(ChimeWait.keepWaiting(3_000, playing = true, everPlayed = true))
        assertFalse(ChimeWait.keepWaiting(60_000, playing = true, everPlayed = true))
    }

    @Test
    fun `stops as soon as a sound that played has ended`() {
        assertFalse(ChimeWait.keepWaiting(400, playing = false, everPlayed = true))
    }

    @Test
    fun `gives a sound a moment to start, and no more`() {
        assertTrue(ChimeWait.keepWaiting(0, playing = false, everPlayed = false))
        assertTrue(ChimeWait.keepWaiting(499, playing = false, everPlayed = false))
        assertFalse(ChimeWait.keepWaiting(500, playing = false, everPlayed = false))
    }

    @Test
    fun `the whole wait is far inside the receiver window`() {
        // goAsync's shortest window is 10 s.
        assertTrue(ChimeWait.CAP_MS + ChimeWait.POLL_MS < 10_000L)
    }
}
