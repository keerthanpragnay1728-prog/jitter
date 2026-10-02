package dev.molasses.core.lease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePlayheadTest {

    private fun play(remaining: Int, total: Int) = GatePlayhead.playhead(remaining, total)

    @Test
    fun `the three drawn examples`() {
        assertEquals("------------------------|------", play(24, 30))
        assertEquals("---------|---------------------", play(9, 30))
        assertEquals("|------------------------------", play(0, 30))
    }

    @Test
    fun `the length is total plus one for every remaining value`() {
        for (total in listOf(0, 8, 12, 16, 20, 24, 28, 30)) {
            for (remaining in -3..total + 3) {
                assertEquals("total=$total remaining=$remaining", total + 1, play(remaining, total).length)
            }
        }
    }

    @Test
    fun `exactly one marker, at the index of the seconds left`() {
        for (total in listOf(8, 30)) {
            for (remaining in 0..total) {
                val line = play(remaining, total)
                assertEquals(1, line.count { it == GatePlayhead.MARKER })
                assertEquals(remaining, line.indexOf(GatePlayhead.MARKER))
                assertTrue(line.all { it == GatePlayhead.MARKER || it == GatePlayhead.TRACK })
            }
        }
    }

    @Test
    fun `below zero clamps to the left edge, above the total to the right`() {
        assertEquals(play(0, 8), play(-1, 8))
        assertEquals(play(0, 8), play(Int.MIN_VALUE, 8))
        assertEquals(play(8, 8), play(9, 8))
        assertEquals(play(8, 8), play(Int.MAX_VALUE, 8))
        assertEquals("--------|", play(99, 8))
        assertEquals("|", play(5, -4))
    }

    @Test
    fun `the 30 second cap gives the longest track, 31 columns`() {
        val capSec = (GateCountdown.CAP_MS / 1000L).toInt()
        assertEquals(30, capSec)
        for (taken in 0..20) {
            val total = (GateCountdown.durationMs(taken) / 1000L).toInt()
            assertTrue("gate $taken is $total s", total <= capSec)
            assertEquals(total + 1, play(total, total).length)
        }
        assertEquals(31, play(capSec, (GateCountdown.durationMs(100) / 1000L).toInt()).length)
    }

    @Test
    fun `ASCII only`() {
        for (remaining in 0..30) {
            assertTrue(play(remaining, 30).all { it.code in 0x20..0x7E })
        }
    }
}
