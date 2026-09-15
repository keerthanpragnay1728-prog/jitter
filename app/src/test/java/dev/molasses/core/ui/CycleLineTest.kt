package dev.molasses.core.ui

import dev.molasses.core.model.AppSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleLineTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun app(
        pkg: String = "com.instagram.android",
        accumulatedMs: Long = 38 * minute,
        tierIndex: Int = 2,
        penaltyMs: Long = 0L,
    ) = AppSnapshot(
        pkg = pkg,
        accumulatedMs = accumulatedMs,
        tierIndex = tierIndex,
        gatesCleared = 0,
        tierUnlockedUntilMs = 40 * minute,
        gatePending = false,
        penaltyMs = penaltyMs,
    )

    // ------------------------------------------------------------ the line

    @Test
    fun `the ordinary line reads as specced`() {
        val f = CycleLine.fields(app(), 4 * hour + 12 * minute)
        assertEquals("38m", f.cycle)
        assertEquals("02", f.tier)
        assertEquals("4h12m", f.resets)
        assertNull("no penalty means no segment, not a zero", f.penalty)
    }

    @Test
    fun `a running ratchet shows`() {
        // The one number here a user can still act on, and the only way they
        // can find out their curve has been accelerated.
        val f = CycleLine.fields(app(penaltyMs = 4 * minute), hour)
        assertEquals("4m", f.penalty)
    }

    @Test
    fun `a zero ratchet is omitted rather than printed`() {
        assertNull(CycleLine.fields(app(penaltyMs = 0), hour).penalty)
    }

    // ----------------------------------------------------- nothing recorded

    @Test
    fun `with no app every engine field is unknown`() {
        // Zero and "nothing recorded" are different, and a confident 0 is the
        // kind of small lie that makes the rest of a readout untrustworthy.
        val f = CycleLine.fields(null, 4 * hour)
        assertEquals(CycleLine.UNKNOWN, f.cycle)
        assertEquals(CycleLine.UNKNOWN, f.tier)
        assertNull(f.penalty)
        assertEquals("4h", f.resets)
    }

    @Test
    fun `an unanchored cycle has no remainder, which is not zero`() {
        val f = CycleLine.fields(app(), null)
        assertEquals(CycleLine.UNKNOWN, f.resets)
        assertEquals("38m", f.cycle)
    }

    @Test
    fun `nothing at all is four unknowns`() {
        val f = CycleLine.fields(null, null)
        assertEquals(CycleLine.UNKNOWN, f.cycle)
        assertEquals(CycleLine.UNKNOWN, f.tier)
        assertEquals(CycleLine.UNKNOWN, f.resets)
        assertNull(f.penalty)
    }

    // ------------------------------------------------------------- the tier

    @Test
    fun `the tier is two digits so the line does not reflow at ten`() {
        assertEquals("00", CycleLine.tier(0))
        assertEquals("02", CycleLine.tier(2))
        assertEquals("09", CycleLine.tier(9))
        assertEquals("10", CycleLine.tier(10))
    }

    @Test
    fun `a tier past two digits is printed, not clamped`() {
        // The index is unbounded past the terminal by design. A reflow at
        // tier 100 is a better price than a column that lies.
        assertEquals("104", CycleLine.tier(104))
    }

    @Test
    fun `a negative tier is unknown, not a negative number`() {
        assertEquals(CycleLine.UNKNOWN, CycleLine.tier(-1))
    }

    // --------------------------------------------------------- the duration

    @Test
    fun `durations drop seconds`() {
        // Nobody reads 38m14s differently from 38m.
        assertEquals("38m", CycleLine.duration(38 * minute + 14_000))
        assertEquals("4h12m", CycleLine.duration(4 * hour + 12 * minute + 59_000))
    }

    @Test
    fun `durations keep two units at most`() {
        assertEquals("1d1h", CycleLine.duration(day + hour + 59 * minute))
        assertEquals("2d", CycleLine.duration(2 * day))
    }

    @Test
    fun `under a minute reads zero, never empty`() {
        // An empty field and a missing field must not look the same.
        assertEquals("0m", CycleLine.duration(0))
        assertEquals("0m", CycleLine.duration(59_000))
        assertEquals("0m", CycleLine.duration(-1))
    }

    @Test
    fun `no duration is ever blank`() {
        val samples = (0L..(3 * day) step (7 * minute + 11_000L)).toList()
        for (ms in samples) assertTrue("$ms", CycleLine.duration(ms).isNotEmpty())
    }
}
