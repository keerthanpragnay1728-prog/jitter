package dev.molasses.core.friction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonReadingTest {

    private val min = 60_000L

    @Test
    fun `terminal is FrictionCurve's, penalty included`() {
        for (acc in listOf(0L, 9 * min, 24 * min, 25 * min - 1, 25 * min, 40 * min)) {
            for (pen in listOf(0L, 1 * min, 10 * min)) {
                for (h in listOf(FrictionCurve.MIN_HORIZON_MS, FrictionCurve.DEFAULT_HORIZON_MS, FrictionCurve.MAX_HORIZON_MS)) {
                    assertEquals(
                        FrictionCurve.isTerminal(acc, pen, h),
                        HorizonReading(acc, pen, h).terminal,
                    )
                }
            }
        }
        assertFalse(HorizonReading(24 * min, 0, FrictionCurve.DEFAULT_HORIZON_MS).terminal)
        assertTrue(HorizonReading(24 * min, 1 * min, FrictionCurve.DEFAULT_HORIZON_MS).terminal)
    }

    @Test
    fun `100 percent and terminal are the same line`() {
        for (acc in (20 * min)..(30 * min) step 30_000L) {
            val r = HorizonReading(acc, 0, FrictionCurve.DEFAULT_HORIZON_MS)
            assertEquals(r.terminal, r.percentOfHorizon >= 100)
        }
    }

    @Test
    fun `used minutes are true time, the penalty is separate`() {
        val r = HorizonReading(38 * min + 59_000, 4 * min, FrictionCurve.DEFAULT_HORIZON_MS)
        assertEquals(38L, r.usedMinutes)
        assertEquals(4L, r.penaltyMinutes)
        assertEquals(25L, r.horizonMinutes)
    }

    @Test
    fun `the CFG summary is the curve's own numbers`() {
        val h = FrictionCurve.DEFAULT_HORIZON_MS
        val s = FrictionSummary.of(h)
        val first = FrictionCurve.frictionAt(FrictionCurve.onsetMs(h), h)
        val top = FrictionCurve.frictionAt(FrictionCurve.terminalMs(h), h)
        assertEquals(FrictionCurve.terminalMs(h) / min, s.horizonMinutes)
        assertEquals(FrictionCurve.onsetMs(h) / min, s.onsetMinutes)
        assertEquals(Math.round(FrictionCurve.ONSET_FRACTION * 100).toInt(), s.onsetPercent)
        assertEquals(first.stallMs, s.firstStallMs)
        assertEquals(Math.round(first.probability * 100), s.firstProbabilityPercent)
        assertEquals(top.stallMs, s.ceilingStallMs)
        assertEquals(Math.round(top.probability * 100), s.ceilingProbabilityPercent)
        // What CFG says today, so a change to the curve is seen here first.
        assertEquals(FrictionSummary(25, 10, 40, 450, 10, 5000, 100), s)
    }
}
