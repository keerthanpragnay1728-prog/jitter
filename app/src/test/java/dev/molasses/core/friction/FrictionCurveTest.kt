package dev.molasses.core.friction

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionCurveTest {

    private val floor = FrictionCurve.DEFAULT_FLOOR_MS
    private fun min(m: Double): Long = (m * 60_000).toLong()
    private fun at(m: Double) = FrictionCurve.frictionAt(min(m), floor)

    // ------------------------------------------------------------- onset

    @Test
    fun `nothing at all before six minutes`() {
        for (m in listOf(0.0, 1.0, 5.0, 5.99)) {
            val p = at(m)
            assertEquals("at ${m}m", 0, p.stallMs)
            assertEquals("at ${m}m", 0f, p.probability, 0f)
            assertFalse(p.stalls)
        }
    }

    @Test
    fun `friction begins exactly at six minutes`() {
        val p = at(6.0)
        assertEquals(floor, p.stallMs)
        assertEquals(0.10f, p.probability, 0.001f)
        assertTrue(p.stalls)
    }

    // ------------------------------------------------- the floor and B2

    @Test
    fun `no band ever commands less than the floor`() {
        var t = min(6.0)
        while (t <= min(30.0)) {
            val p = FrictionCurve.frictionAt(t, floor)
            if (p.stallMs > 0) {
                assertTrue("at ${t}ms got ${p.stallMs}", p.stallMs >= floor)
            }
            t += 1_000
        }
    }

    @Test
    fun `the default floor is above the measured segment D p50`() {
        // A stall shorter than D p50 arms after the gesture it belonged to
        // and is never felt. That is the entire reason the floor exists.
        assertTrue(FrictionCurve.DEFAULT_FLOOR_MS > FrictionCurve.MEASURED_SEGMENT_D_P50_MS)
        assertEquals(450, FrictionCurve.DEFAULT_FLOOR_MS)
    }

    @Test
    fun `six to ten minutes is flat in duration and rises only in probability`() {
        // B2's design, falling out of the arithmetic rather than special cased.
        val points = listOf(6.0, 7.0, 8.0, 9.0, 9.9).map { at(it) }
        assertTrue("durations were ${points.map { it.stallMs }}", points.all { it.stallMs == floor })
        val probabilities = points.map { it.probability }
        assertEquals(probabilities.sorted(), probabilities)
        assertTrue(probabilities.last() > probabilities.first())
    }

    @Test
    fun `a lower floor lets the early bands separate in duration too`() {
        // Proves the floor is doing the flattening, not a hardcoded table.
        val low = FrictionCurve.frictionAt(min(9.5), floorMs = 100)
        val high = FrictionCurve.frictionAt(min(9.5), floorMs = floor)
        assertTrue(low.stallMs < high.stallMs)
    }

    // --------------------------------------------------- band boundaries

    @Test
    fun `probability at each specified band boundary`() {
        val expected = listOf(
            6.0 to 0.10f, 7.0 to 0.15f, 8.0 to 0.20f, 9.0 to 0.30f,
            10.0 to 0.40f, 12.0 to 0.60f, 14.0 to 0.70f, 18.0 to 0.90f,
            22.0 to 0.98f, 25.0 to 1.00f,
        )
        for ((m, p) in expected) {
            assertEquals("at ${m}m", p, at(m).probability, 0.001f)
        }
    }

    @Test
    fun `duration at each specified band boundary, floor clamped`() {
        val expected = listOf(
            6.0 to floor, 7.0 to floor, 8.0 to floor, 9.0 to floor,
            10.0 to 450, 12.0 to 800, 14.0 to 1200, 18.0 to 2700,
            22.0 to 4200, 25.0 to 5000,
        )
        for ((m, d) in expected) {
            assertEquals("at ${m}m", d, at(m).stallMs)
        }
    }

    @Test
    fun `both sides of every boundary are continuous`() {
        // No step discontinuities: one millisecond either side of a knot must
        // not jump. The onset at six minutes is the deliberate exception.
        val boundaries = listOf(7.0, 8.0, 9.0, 10.0, 12.0, 14.0, 18.0, 22.0, 25.0)
        for (m in boundaries) {
            val before = FrictionCurve.frictionAt(min(m) - 1, floor)
            val after = FrictionCurve.frictionAt(min(m) + 1, floor)
            assertTrue(
                "duration jumped at ${m}m: ${before.stallMs} to ${after.stallMs}",
                after.stallMs - before.stallMs <= 5,
            )
            assertTrue(
                "probability jumped at ${m}m: ${before.probability} to ${after.probability}",
                after.probability - before.probability <= 0.01f,
            )
        }
    }

    // ------------------------------------------------------ terminal

    @Test
    fun `terminal from twenty five minutes on`() {
        for (m in listOf(25.0, 30.0, 60.0, 600.0)) {
            val p = at(m)
            assertEquals("at ${m}m", 5000, p.stallMs)
            assertEquals("at ${m}m", 1.0f, p.probability, 0f)
        }
    }

    @Test
    fun `an absurd accumulated time does not overflow or wrap`() {
        val p = FrictionCurve.frictionAt(Long.MAX_VALUE, floor)
        assertEquals(5000, p.stallMs)
        assertEquals(1.0f, p.probability, 0f)
    }

    @Test
    fun `a negative accumulated time is treated as zero`() {
        val p = FrictionCurve.frictionAt(-5_000L, floor)
        assertEquals(0, p.stallMs)
        assertEquals(0f, p.probability, 0f)
    }

    // -------------------------------------------------- monotonicity

    @Test
    fun `both dimensions are non-decreasing across the whole range`() {
        // The specified table dips at four band edges. Read literally it
        // would refund friction; KNOTS resolves each boundary upward. This is
        // the assertion that keeps that true.
        var previous = FrictionCurve.frictionAt(0, floor)
        var t = 0L
        while (t <= min(40.0)) {
            val p = FrictionCurve.frictionAt(t, floor)
            assertTrue(
                "duration fell at ${t}ms: ${previous.stallMs} to ${p.stallMs}",
                p.stallMs >= previous.stallMs,
            )
            assertTrue(
                "probability fell at ${t}ms: ${previous.probability} to ${p.probability}",
                p.probability >= previous.probability - 1e-6f,
            )
            previous = p
            t += 1_000
        }
    }

    @Test
    fun `monotonic at every floor value, not just the default`() {
        for (f in listOf(0, 100, 450, 1000, 3000)) {
            var previous = 0
            var t = 0L
            while (t <= min(30.0)) {
                val stall = FrictionCurve.frictionAt(t, f).stallMs
                assertTrue("floor $f fell at ${t}ms", stall >= previous)
                previous = stall
                t += 5_000
            }
        }
    }

    // ------------------------------------------------------- Bernoulli

    @Test
    fun `shouldStall respects the probability`() {
        val p = FrictionPoint(500, 0.30f)
        assertTrue(FrictionCurve.shouldStall(p, 0.0f))
        assertTrue(FrictionCurve.shouldStall(p, 0.299f))
        assertFalse(FrictionCurve.shouldStall(p, 0.30f))
        assertFalse(FrictionCurve.shouldStall(p, 0.99f))
    }

    @Test
    fun `a zero duration never stalls whatever the roll`() {
        assertFalse(FrictionCurve.shouldStall(FrictionPoint(0, 1.0f), 0.0f))
    }

    @Test
    fun `certainty and impossibility are exact`() {
        assertTrue(FrictionCurve.shouldStall(FrictionPoint(500, 1.0f), 0.9999f))
        assertFalse(FrictionCurve.shouldStall(FrictionPoint(500, 0f), 0.0f))
    }

    @Test
    fun `the observed rate matches the commanded probability`() {
        val rng = Random(20260914)
        for (target in listOf(0.10f, 0.30f, 0.50f, 0.90f)) {
            val point = FrictionPoint(500, target)
            val n = 200_000
            val hits = (0 until n).count { FrictionCurve.shouldStall(point, rng.nextFloat()) }
            val observed = hits.toDouble() / n
            assertEquals("p=$target", target.toDouble(), observed, 0.005)
        }
    }

    /**
     * Run lengths of consecutive stalls under plain Bernoulli.
     *
     * Reported rather than asserted. The question this answers is whether
     * three stalls in a row at 10% is common enough to read as a freeze
     * rather than a hitch, which decides whether a minimum-gap rule is worth
     * the complexity. Printing it keeps the decision evidence-based.
     */
    @Test
    fun `report the run length distribution`() {
        val rng = Random(20260914)
        val trials = 1_000_000
        println("run-length distribution, plain Bernoulli, n=$trials per rate")
        println("  p     mean   max   P(run>=2)  P(run>=3)  P(run>=5)  runs")
        for (p in listOf(0.10f, 0.30f, 0.50f)) {
            val point = FrictionPoint(500, p)
            val runs = mutableListOf<Int>()
            var current = 0
            repeat(trials) {
                if (FrictionCurve.shouldStall(point, rng.nextFloat())) {
                    current += 1
                } else if (current > 0) {
                    runs += current
                    current = 0
                }
            }
            if (current > 0) runs += current
            val total = runs.size.toDouble()
            println(
                "  %.2f  %5.2f  %4d  %8.3f%%  %8.3f%%  %8.3f%%  %d".format(
                    p,
                    runs.average(),
                    runs.max(),
                    runs.count { it >= 2 } / total * 100,
                    runs.count { it >= 3 } / total * 100,
                    runs.count { it >= 5 } / total * 100,
                    runs.size,
                ),
            )
        }
        // A geometric distribution: P(run >= k) = p^(k-1). Sanity check the
        // simulation rather than the design.
        val point = FrictionPoint(500, 0.50f)
        val runs = mutableListOf<Int>()
        var current = 0
        repeat(trials) {
            if (FrictionCurve.shouldStall(point, rng.nextFloat())) current += 1
            else if (current > 0) { runs += current; current = 0 }
        }
        val pGe3 = runs.count { it >= 3 }.toDouble() / runs.size
        assertEquals("P(run>=3) at p=0.5 should be 0.25", 0.25, pGe3, 0.01)
    }
}
