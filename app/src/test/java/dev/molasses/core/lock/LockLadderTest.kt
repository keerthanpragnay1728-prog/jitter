package dev.molasses.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLadderTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun `the steps are the documented ladder, ascending`() {
        assertEquals(
            listOf(1 * hour, 2 * hour, 6 * hour, 12 * hour, 24 * hour, 3 * day, 7 * day, 30 * day),
            LockLadder.STEPS_MS,
        )
        assertEquals(LockLadder.STEPS_MS.sorted(), LockLadder.STEPS_MS)
        assertEquals(LockLadder.STEPS_MS.distinct(), LockLadder.STEPS_MS)
    }

    @Test
    fun `index maps to duration and back`() {
        LockLadder.STEPS_MS.forEachIndexed { i, ms ->
            assertEquals(ms, LockLadder.durationAt(i))
            assertEquals(i, LockLadder.indexOf(ms))
        }
        assertEquals(-1, LockLadder.indexOf(90 * 60 * 1000))
    }

    @Test
    fun `an out of range index clamps rather than throwing`() {
        assertEquals(LockLadder.MIN_MS, LockLadder.durationAt(-4))
        assertEquals(LockLadder.MAX_MS, LockLadder.durationAt(999))
    }

    @Test
    fun `snap picks the nearest step`() {
        assertEquals(1 * hour, LockLadder.snap(50 * 60 * 1000))
        assertEquals(2 * hour, LockLadder.snap(2 * hour + 5 * 60 * 1000))
        assertEquals(24 * hour, LockLadder.snap(20 * hour))
        assertEquals(7 * day, LockLadder.snap(6 * day))
    }

    @Test
    fun `snap clamps outside the ladder`() {
        assertEquals(LockLadder.MIN_MS, LockLadder.snap(0))
        assertEquals(LockLadder.MIN_MS, LockLadder.snap(-5))
        assertEquals(LockLadder.MAX_MS, LockLadder.snap(365 * day))
    }

    @Test
    fun `a tie rounds up, toward more friction`() {
        // Exactly between 1h and 2h.
        assertEquals(2 * hour, LockLadder.snap(90 * 60 * 1000))
        // Exactly between 3d and 7d.
        assertEquals(7 * day, LockLadder.snap(5 * day))
    }

    @Test
    fun `next returns the following step and saturates at the top`() {
        assertEquals(2 * hour, LockLadder.next(1 * hour))
        assertEquals(6 * hour, LockLadder.next(3 * hour))
        assertEquals(LockLadder.MAX_MS, LockLadder.next(30 * day))
        assertEquals(LockLadder.MAX_MS, LockLadder.next(400 * day))
    }

    @Test
    fun `every snap result is itself a step`() {
        for (v in listOf(0L, 1L, hour, 5 * hour, 13 * hour, 2 * day, 10 * day, 60 * day)) {
            assertTrue("$v", LockLadder.snap(v) in LockLadder.STEPS_MS)
        }
    }
}
