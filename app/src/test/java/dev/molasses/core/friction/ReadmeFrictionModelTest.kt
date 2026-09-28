package dev.molasses.core.friction

import dev.molasses.core.lease.GateCountdown
import dev.molasses.core.lease.LeaseLadder
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The README's description of the friction model is computed from the code
 * here, so it cannot go stale the way the 5/10/15/20 tier table did: that
 * table described a fixed ladder of movement gates long after the curve had
 * become per-app horizons with probability.
 */
class ReadmeFrictionModelTest {

    private val readme = repoFile("README.md").readText()

    private fun min(ms: Long): Long = ms / 60_000L

    @Test
    fun `the horizon bounds and the onset share are the code's`() {
        assertEquals(25L, min(FrictionCurve.DEFAULT_HORIZON_MS))
        assertEquals(10L, min(FrictionCurve.MIN_HORIZON_MS))
        assertEquals(60L, min(FrictionCurve.MAX_HORIZON_MS))
        assertEquals(0.40, FrictionCurve.ONSET_FRACTION, 0.0)
        assertEquals(25L, min(FrictionCurve.TAPER_FROM_HORIZON_MS))
        assertEquals(60L, min(FrictionCurve.TAPER_TO_HORIZON_MS))
        assertEquals(450, FrictionCurve.DEFAULT_FLOOR_MS)
        assertTrue(readme.contains("defaults to 25 min and can be set from 10 to 60 min"))
        assertTrue(readme.contains("Friction starts at 40% of the horizon"))
        assertTrue(readme.contains("No stall shorter than 450 ms"))
    }

    @Test
    fun `each table row is what FrictionCurve computes for that horizon`() {
        val rows = mapOf(
            FrictionCurve.MIN_HORIZON_MS to "(minimum)",
            FrictionCurve.DEFAULT_HORIZON_MS to "(default)",
            FrictionCurve.MAX_HORIZON_MS to "(maximum)",
        )
        for ((horizon, label) in rows) {
            val row = "| ${min(horizon)} min $label | ${min(FrictionCurve.onsetMs(horizon))} min | " +
                "${FrictionCurve.terminalStallMs(horizon)} ms | " +
                "${(FrictionCurve.terminalProbability(horizon) * 100).roundToInt()}% |"
            assertTrue("README is missing or wrong on: $row", readme.contains(row))
        }
    }

    @Test
    fun `the lease figures are the code's`() {
        assertEquals(listOf(5L, 10L, 15L), LeaseLadder.OFFERED_MS.map { min(it) })
        assertEquals(8_000L, GateCountdown.FIRST_MS)
        assertEquals(4_000L, GateCountdown.STEP_MS)
        assertEquals(30_000L, GateCountdown.CAP_MS)
        assertTrue(readme.contains("a choice of 5, 10 or 15 minutes"))
        assertTrue(readme.contains("8 s the first time and 4 s longer for each lease taken this cycle, up to\n30 s"))
    }

    @Test
    fun `the old tier table is gone`() {
        assertFalse(readme.contains("| **at 5 min** |"))
        assertFalse(readme.contains("Phantom Stall: 1000 ms"))
        assertFalse(readme.contains("After five minutes in a target"))
    }
}
