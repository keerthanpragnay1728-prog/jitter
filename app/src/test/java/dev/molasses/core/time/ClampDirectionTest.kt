package dev.molasses.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two clamp directions, side by side.
 *
 * Written as one file on purpose: the whole point is that a restriction and a
 * relief are given the *same* tampered gap and must disagree about it.
 */
class ClampDirectionTest {

    private val min = 60_000L
    private val hour = 60 * min

    private fun gap(wallDelta: Long, elapsedDelta: Long, boot: Boolean = false) =
        ClockTamperClamp.Gap(
            lastSeenWallMs = 1_700_000_000_000L,
            lastSeenElapsedMs = 0L,
            nowWallMs = 1_700_000_000_000L + wallDelta,
            nowElapsedMs = elapsedDelta,
            bootIdChanged = boot,
        )

    @Test
    fun `the default direction is restriction`() {
        val forward = gap(wallDelta = 30 * hour, elapsedDelta = 1 * min)
        assertEquals(
            ClockTamperClamp.evaluate(forward, ClockTamperClamp.Direction.RESTRICTION).creditedMs,
            ClockTamperClamp.evaluate(forward).creditedMs,
        )
    }

    @Test
    fun `a forward jump credits nothing to a restriction`() {
        // The lock attack. Crediting the jump would expire a long lock at once.
        val v = ClockTamperClamp.evaluate(
            gap(wallDelta = 31 * 24 * hour, elapsedDelta = 1 * min),
            ClockTamperClamp.Direction.RESTRICTION,
        )
        assertEquals(1 * min, v.creditedMs)
        assertTrue(v.tampered)
    }

    @Test
    fun `a backward wind credits the monotonic delta to a relief`() {
        // The pause attack. Taking the smaller delta here would hold the
        // relief open forever.
        val v = ClockTamperClamp.evaluate(
            gap(wallDelta = -5 * hour, elapsedDelta = 16 * min),
            ClockTamperClamp.Direction.RELIEF,
        )
        assertEquals(16 * min, v.creditedMs)
        assertTrue(v.tampered)
    }

    @Test
    fun `the same tampered gap is judged differently by each direction`() {
        val wound = gap(wallDelta = -5 * hour, elapsedDelta = 16 * min)
        val restriction =
            ClockTamperClamp.evaluate(wound, ClockTamperClamp.Direction.RESTRICTION).creditedMs
        val relief =
            ClockTamperClamp.evaluate(wound, ClockTamperClamp.Direction.RELIEF).creditedMs
        assertEquals("a restriction credits nothing", 0L, restriction)
        assertEquals("a relief credits the monotonic delta", 16 * min, relief)
        assertTrue(relief > restriction)
    }

    @Test
    fun `a relief never credits less than a restriction for the same gap`() {
        // The invariant that makes the rule safe by construction: relief is
        // always the more generous reading, so erring toward more friction
        // is always the restriction answer.
        val cases = listOf(
            gap(3 * hour, 3 * hour),
            gap(30 * hour, 1 * min),
            gap(-5 * hour, 16 * min),
            gap(1 * min, 1 * min),
            gap(-2 * hour, -2 * hour),
        )
        for (g in cases) {
            val r = ClockTamperClamp.evaluate(g, ClockTamperClamp.Direction.RESTRICTION).creditedMs
            val f = ClockTamperClamp.evaluate(g, ClockTamperClamp.Direction.RELIEF).creditedMs
            assertTrue("$g: relief $f < restriction $r", f >= r)
        }
    }

    @Test
    fun `agreeing clocks are judged the same by both directions`() {
        val honest = gap(wallDelta = 3 * hour, elapsedDelta = 3 * hour)
        assertEquals(
            ClockTamperClamp.evaluate(honest, ClockTamperClamp.Direction.RESTRICTION).creditedMs,
            ClockTamperClamp.evaluate(honest, ClockTamperClamp.Direction.RELIEF).creditedMs,
        )
    }

    @Test
    fun `a boot boundary is flagged so relief can expire instead of trusting it`() {
        val v = ClockTamperClamp.evaluate(gap(2 * hour, 60_000, boot = true))
        assertTrue(v.bootChanged)
        assertEquals(2 * hour, v.creditedMs)

        assertFalse(ClockTamperClamp.evaluate(gap(2 * hour, 2 * hour)).bootChanged)
    }

    @Test
    fun `credit is never negative in either direction`() {
        val backwards = gap(wallDelta = -9 * hour, elapsedDelta = -9 * hour)
        for (d in ClockTamperClamp.Direction.entries) {
            assertTrue(ClockTamperClamp.evaluate(backwards, d).creditedMs >= 0)
        }
    }
}
