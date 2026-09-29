package dev.molasses.core.friction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band selection, at each boundary and on both sides of it.
 *
 * The strings are not tested here and deliberately are not testable here:
 * this module is pure and the copy lives in `strings.xml`. What is worth
 * pinning is which of three things is true, because that is the only decision
 * this file makes and it is the one that would go wrong silently. A band
 * chosen one millisecond early prints "no stall yet" over a curve that has
 * started, which is the readout lying on the screen built to stop it lying.
 */
class NextScrollTest {

    private val horizon = FrictionCurve.DEFAULT_HORIZON_MS
    private val onset = FrictionCurve.onsetMs(horizon)

    private fun at(ms: Long, penalty: Long = 0L) =
        NextScroll.readingAt(ms, penalty, horizon)

    @Test
    fun `below the onset there is no reading, only the fact that there is none`() {
        assertEquals(NextScroll.Reading.BeforeOnset, at(0))
        assertEquals(NextScroll.Reading.BeforeOnset, at(onset / 2))
    }

    @Test
    fun `the last millisecond before the onset is still before it`() {
        // The boundary, from below. frictionAt returns 0/0 here, so a band
        // selector that trusted the numbers instead of the threshold would
        // agree by accident and then disagree the moment the floor changed.
        assertEquals(NextScroll.Reading.BeforeOnset, at(onset - 1))
    }

    @Test
    fun `the onset itself is on the ramp`() {
        val reading = at(onset)
        assertTrue("expected OnTheRamp at the onset, got $reading", reading is NextScroll.Reading.OnTheRamp)
    }

    @Test
    fun `the horizon itself is pinned, and the millisecond before it is not`() {
        assertTrue(at(horizon - 1) is NextScroll.Reading.OnTheRamp)
        assertTrue(at(horizon) is NextScroll.Reading.Pinned)
        assertTrue(at(horizon * 3) is NextScroll.Reading.Pinned)
    }

    @Test
    fun `the ramp is where the numbers move, and probability moves first`() {
        // The reason the copy leads with probability. Across the ten to twelve
        // minute band the stall sits at the floor and only the chance climbs,
        // so a readout showing milliseconds alone would be flat exactly where
        // the experience changes.
        val early = at(10 * 60_000) as NextScroll.Reading.OnTheRamp
        val later = at(12 * 60_000) as NextScroll.Reading.OnTheRamp
        assertEquals(
            "the stall is at the floor across this band",
            early.stallMs,
            FrictionCurve.DEFAULT_FLOOR_MS,
        )
        assertTrue(
            "probability must climb where the duration does not: " +
                "${early.probability} -> ${later.probability}",
            later.probability > early.probability,
        )
    }

    @Test
    fun `the penalty moves the reading, because the curve is read against it`() {
        // Sitting past a lease is what the ratchet charges for, and the gate
        // has to quote the price the next scroll will actually pay. A readout
        // that ignored the penalty would under-quote exactly the user who has
        // earned the higher number.
        assertEquals(NextScroll.Reading.BeforeOnset, at(onset - 60_000))
        assertTrue(at(onset - 60_000, penalty = 120_000) is NextScroll.Reading.OnTheRamp)
    }

    @Test
    fun `a pinned reading still carries its numbers`() {
        // The copy does not print them; the type does not throw them away.
        val pinned = at(horizon) as NextScroll.Reading.Pinned
        assertTrue(pinned.stallMs > 0)
        assertTrue(pinned.probability > 0f)
    }

    @Test
    fun `a wide horizon is pinned below one hundred percent`() {
        // The reason the pinned copy does not say "always". Past the taper
        // point the terminal probability comes down toward 0.70, so the one
        // word that would read most naturally is wrong on exactly the
        // horizons a heavy user picks.
        val wide = FrictionCurve.MAX_HORIZON_MS
        val pinned = NextScroll.readingAt(wide, 0L, wide) as NextScroll.Reading.Pinned
        assertTrue(
            "expected a tapered terminal probability, got ${pinned.probability}",
            pinned.probability < 1.0f,
        )
    }

    @Test
    fun `every band is reachable at every offered horizon`() {
        // A band nothing can reach is a phrase nobody will ever see, and the
        // sweep is cheap. Onset is a fraction of the horizon, so this holds
        // by construction rather than by luck, and this says so.
        for (minutes in listOf(10L, 15L, 25L, 40L, 60L)) {
            val h = minutes * 60_000
            val o = FrictionCurve.onsetMs(h)
            assertEquals("$minutes", NextScroll.Reading.BeforeOnset, NextScroll.readingAt(0, 0, h))
            assertTrue("$minutes", NextScroll.readingAt(o, 0, h) is NextScroll.Reading.OnTheRamp)
            assertTrue("$minutes", NextScroll.readingAt(h, 0, h) is NextScroll.Reading.Pinned)
        }
    }

    @Test
    fun `percent rounds rather than truncates`() {
        // 0.615 shown as 61 rather than 62 is a rounding choice; 0.999 shown
        // as 99 rather than 100 is the one that would look like a bug.
        assertEquals(0, NextScroll.percent(0f))
        assertEquals(100, NextScroll.percent(1f))
        assertEquals(61, NextScroll.percent(0.614f))
        assertEquals(100, NextScroll.percent(0.999f))
        assertEquals(100, NextScroll.percent(1.4f))
        assertEquals(0, NextScroll.percent(-0.2f))
    }

    @Test
    fun `the reading agrees with the curve the engine reads`() {
        // The whole point of going through FrictionCurve rather than keeping
        // a copy of the thresholds. If these ever disagree, the gate is
        // quoting a price the engine will not charge.
        for (minutes in 0..30) {
            val ms = minutes * 60_000L
            val point = FrictionCurve.frictionAt(ms, horizon)
            when (val reading = at(ms)) {
                NextScroll.Reading.BeforeOnset ->
                    assertEquals("at ${minutes}m", 0, point.stallMs)
                is NextScroll.Reading.OnTheRamp -> {
                    assertEquals("at ${minutes}m", point.stallMs, reading.stallMs)
                    assertEquals("at ${minutes}m", point.probability, reading.probability, 0f)
                }
                is NextScroll.Reading.Pinned -> {
                    assertEquals("at ${minutes}m", point.stallMs, reading.stallMs)
                    assertEquals("at ${minutes}m", point.probability, reading.probability, 0f)
                }
            }
        }
    }
}
