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
        leasesTaken = 0,
        leaseUntilAccumulatedMs = 40 * minute,
        penaltyMs = penaltyMs,
    )

    // ------------------------------------------------------------ the line

    @Test
    fun `the ordinary line reads as specced`() {
        val f = CycleLine.fields(app(), 4 * hour + 12 * minute)
        assertEquals("38m", f.cycle)
        // 38 of a 25 minute default horizon, floored.
        assertEquals("152%", f.horizon)
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
        assertEquals(CycleLine.UNKNOWN, f.horizon)
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
        assertEquals(CycleLine.UNKNOWN, f.horizon)
        assertEquals(CycleLine.UNKNOWN, f.resets)
        assertNull(f.penalty)
    }

    // ---------------------------------------------------------- the horizon

    @Test
    fun `the horizon counts the penalty, because the curve does`() {
        // 38 + 4 = 42 of 25: what friction is read against.
        assertEquals("168%", CycleLine.horizon(app(penaltyMs = 4 * minute)))
    }

    @Test
    fun `the horizon is the app's own, not the default`() {
        assertEquals("63%", CycleLine.horizon(app().copy(horizonMs = 60 * minute)))
    }

    @Test
    fun `100 percent is exactly where the engine turns terminal`() {
        val atHorizon = app(accumulatedMs = 25 * minute)
        val justShort = app(accumulatedMs = 25 * minute - 1)
        assertEquals("100%", CycleLine.horizon(atHorizon))
        assertEquals("99%", CycleLine.horizon(justShort))
        assertTrue(dev.molasses.core.friction.FrictionCurve.isTerminal(atHorizon.accumulatedMs, 0, atHorizon.horizonMs))
        assertTrue(!dev.molasses.core.friction.FrictionCurve.isTerminal(justShort.accumulatedMs, 0, justShort.horizonMs))
    }

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
