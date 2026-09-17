package dev.molasses.core.friction

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionCurveTest {

    private val floor = FrictionCurve.DEFAULT_FLOOR_MS
    private fun min(m: Double): Long = (m * 60_000).toLong()

    /** The default horizon, which is what the unqualified cases describe. */
    private val default = FrictionCurve.DEFAULT_HORIZON_MS

    private fun at(m: Double, horizonMs: Long = default) =
        FrictionCurve.frictionAt(min(m), horizonMs, floor)

    private fun atMs(ms: Long, horizonMs: Long = default) =
        FrictionCurve.frictionAt(ms, horizonMs, floor)

    /** The three horizons every shape assertion is run at. */
    private val horizons = listOf(18L, 30L, 60L).map { it * 60_000 }

    /**
     * Where a knot sits, in milliseconds, for a given horizon.
     *
     * The knots are fractions of the onset-to-terminal ramp, so their absolute
     * positions are irrational multiples of the horizon and cannot be written
     * as round numbers. This is the same arithmetic the curve does, which is
     * the point: the assertion is about the value at the knot, not about the
     * curve agreeing with a second copy of the position formula.
     */
    private fun knot(horizonMs: Long, fraction: Double): Long {
        val onset = FrictionCurve.onsetMs(horizonMs)
        return onset + Math.round(fraction * (horizonMs - onset))
    }

    /** The source table's fractions, in order. See FrictionCurve.KNOTS. */
    private val fractions = listOf(6.0, 7.0, 8.0, 9.0, 10.0, 12.0, 14.0, 18.0, 22.0, 25.0)
        .map { (it - 6.0) / 19.0 }

    /** The untapered column values, which every horizon scales. */
    private val sourceProbabilities =
        listOf(0.10f, 0.15f, 0.20f, 0.30f, 0.40f, 0.60f, 0.70f, 0.90f, 0.98f, 1.00f)
    private val sourceStalls =
        listOf(150, 150, 150, 350, 450, 800, 1200, 2700, 4200, 5000)

    // ------------------------------------------------------------- onset

    @Test
    fun `nothing at all before the onset, at every horizon`() {
        for (h in horizons) {
            val onset = FrictionCurve.onsetMs(h)
            for (t in listOf(0L, 1_000L, onset / 2, onset - 1)) {
                val p = atMs(t, h)
                assertEquals("horizon $h at ${t}ms", 0, p.stallMs)
                assertEquals("horizon $h at ${t}ms", 0f, p.probability, 0f)
                assertFalse(p.stalls)
            }
        }
    }

    @Test
    fun `friction begins exactly at the onset, at every horizon`() {
        for (h in horizons) {
            val p = atMs(FrictionCurve.onsetMs(h), h)
            assertEquals("horizon $h", floor, p.stallMs)
            assertEquals(
                "horizon $h",
                0.10f * FrictionCurve.terminalProbability(h),
                p.probability,
                0.002f,
            )
            assertTrue(p.stalls)
        }
    }

    @Test
    fun `the onset is forty percent of the horizon and the terminal is the horizon`() {
        // One rule, no free parameters, no per horizon table.
        assertEquals(7 * 60_000L + 12_000L, FrictionCurve.onsetMs(18 * 60_000L))
        assertEquals(12 * 60_000L, FrictionCurve.onsetMs(30 * 60_000L))
        assertEquals(24 * 60_000L, FrictionCurve.onsetMs(60 * 60_000L))
        for (h in horizons) {
            assertEquals(h, FrictionCurve.terminalMs(h))
            assertEquals((h * FrictionCurve.ONSET_FRACTION).toLong(), FrictionCurve.onsetMs(h))
        }
    }

    @Test
    fun `a horizon outside the offered range is clamped, not rejected`() {
        assertEquals(FrictionCurve.MIN_HORIZON_MS, FrictionCurve.clampHorizon(0))
        assertEquals(FrictionCurve.MIN_HORIZON_MS, FrictionCurve.clampHorizon(-1))
        assertEquals(FrictionCurve.MAX_HORIZON_MS, FrictionCurve.clampHorizon(Long.MAX_VALUE))
        // A 90 minute horizon is a 60 minute horizon in every respect.
        val ninety = 90 * 60_000L
        assertEquals(
            FrictionCurve.terminalStallMs(60 * 60_000L),
            FrictionCurve.terminalStallMs(ninety),
        )
        assertEquals(
            FrictionCurve.terminalProbability(60 * 60_000L),
            FrictionCurve.terminalProbability(ninety),
            0f,
        )
        assertEquals(atMs(50 * 60_000L, 60 * 60_000L), atMs(50 * 60_000L, ninety))
    }

    // ------------------------------------------------- the floor and B2

    @Test
    fun `no band ever commands less than the floor, at every horizon`() {
        for (h in horizons) {
            var t = 0L
            while (t <= h + min(10.0)) {
                val p = atMs(t, h)
                if (p.stallMs > 0) {
                    assertTrue("horizon $h at ${t}ms got ${p.stallMs}", p.stallMs >= floor)
                }
                t += 1_000
            }
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
    fun `the early ramp is flat in duration and rises only in probability`() {
        // B2's design, falling out of the arithmetic rather than special
        // cased, and it holds at every horizon because the floor does.
        for (h in horizons) {
            val points = (0..3).map { atMs(knot(h, fractions[it]), h) }
            assertTrue(
                "horizon $h durations were ${points.map { it.stallMs }}",
                points.all { it.stallMs == floor },
            )
            val probabilities = points.map { it.probability }
            assertEquals("horizon $h", probabilities.sorted(), probabilities)
            assertTrue("horizon $h", probabilities.last() > probabilities.first())
        }
    }

    @Test
    fun `a lower floor lets the early bands separate in duration too`() {
        // Proves the floor is doing the flattening, not a hardcoded table.
        val low = FrictionCurve.frictionAt(knot(default, fractions[3]), default, floorMs = 100)
        val high = FrictionCurve.frictionAt(knot(default, fractions[3]), default, floorMs = floor)
        assertTrue(low.stallMs < high.stallMs)
    }

    // --------------------------------------------------- band boundaries

    @Test
    fun `probability at every band boundary, at every horizon`() {
        for (h in horizons) {
            val scale = FrictionCurve.terminalProbability(h)
            for (i in fractions.indices) {
                assertEquals(
                    "horizon ${h / 60_000}m knot $i",
                    sourceProbabilities[i] * scale,
                    atMs(knot(h, fractions[i]), h).probability,
                    0.003f,
                )
            }
        }
    }

    @Test
    fun `duration at every band boundary, floor clamped, at every horizon`() {
        for (h in horizons) {
            val scale = FrictionCurve.terminalStallMs(h).toDouble() / FrictionCurve.TERMINAL_STALL_MS
            for (i in fractions.indices) {
                val expected = maxOf((sourceStalls[i] * scale).toInt(), floor)
                assertEquals(
                    "horizon ${h / 60_000}m knot $i",
                    expected.toDouble(),
                    atMs(knot(h, fractions[i]), h).stallMs.toDouble(),
                    2.0,
                )
            }
        }
    }

    @Test
    fun `both sides of every boundary are continuous, at every horizon`() {
        // No step discontinuities: one millisecond either side of a knot must
        // not jump. The onset is the deliberate exception, so it is skipped.
        for (h in horizons) {
            for (i in 1 until fractions.size) {
                val mark = knot(h, fractions[i])
                val before = atMs(mark - 1, h)
                val after = atMs(mark + 1, h)
                assertTrue(
                    "horizon $h knot $i duration jumped: ${before.stallMs} to ${after.stallMs}",
                    after.stallMs - before.stallMs <= 5,
                )
                assertTrue(
                    "horizon $h knot $i probability jumped",
                    after.probability - before.probability <= 0.01f,
                )
            }
        }
    }

    // ------------------------------------------------------ the taper

    @Test
    fun `at or below twenty minutes the ceiling is exactly what it always was`() {
        for (h in listOf(10L, 15L, 18L, 20L).map { it * 60_000 }) {
            assertEquals("horizon $h", 5000, FrictionCurve.terminalStallMs(h))
            assertEquals("horizon $h", 1.0f, FrictionCurve.terminalProbability(h), 0f)
            val p = atMs(h, h)
            assertEquals(5000, p.stallMs)
            assertEquals(1.0f, p.probability, 0f)
        }
    }

    @Test
    fun `at sixty minutes the ceiling is three seconds at seventy percent`() {
        val h = 60 * 60_000L
        assertEquals(3000, FrictionCurve.terminalStallMs(h))
        assertEquals(0.70f, FrictionCurve.terminalProbability(h), 0.001f)
        val p = atMs(h, h)
        assertEquals(3000, p.stallMs)
        assertEquals(0.70f, p.probability, 0.001f)
    }

    @Test
    fun `the taper is linear between its endpoints`() {
        // Halfway between 20 and 60 minutes is halfway between the ceilings.
        val h = 40 * 60_000L
        assertEquals(4000, FrictionCurve.terminalStallMs(h))
        assertEquals(0.85f, FrictionCurve.terminalProbability(h), 0.001f)
    }

    @Test
    fun `a longer horizon is never more severe at any instant`() {
        // The property that makes declaring a long session safe to offer. If
        // a longer horizon were harsher anywhere, lying about the session
        // would be strictly better than describing it.
        val short = 18 * 60_000L
        val long = 60 * 60_000L
        var t = 0L
        while (t <= long) {
            val a = atMs(t, short)
            val b = atMs(t, long)
            assertTrue(
                "at ${t}ms the 60m horizon stalled longer: ${b.stallMs} vs ${a.stallMs}",
                b.stallMs <= a.stallMs,
            )
            assertTrue(
                "at ${t}ms the 60m horizon was likelier: ${b.probability} vs ${a.probability}",
                b.probability <= a.probability + 1e-6f,
            )
            t += 1_000
        }
    }

    // ------------------------------------------------------ no refunds

    @Test
    fun `forty minutes in on a sixty minute horizon is minute forty, not minute zero`() {
        // The rule a horizon must never break. Widening is not a reset, and
        // someone already deep in the ramp stays deep in it.
        val p = atMs(40 * 60_000L, 60 * 60_000L)
        assertTrue("expected real friction, got $p", p.stalls)
        assertTrue("stall was ${p.stallMs}", p.stallMs >= 800)
        assertTrue("probability was ${p.probability}", p.probability >= 0.45f)
    }

    @Test
    fun `changing the horizon changes only the curve`() {
        // frictionAt is a pure function of the two inputs and a floor. There
        // is no accumulated total or tier index for it to touch, which is the
        // structural half of the no-refund rule; the engine half is asserted
        // in FrictionEngineTest.
        val t = 20 * 60_000L
        val before = atMs(t, 18 * 60_000L)
        atMs(t, 60 * 60_000L)
        assertEquals(before, atMs(t, 18 * 60_000L))
    }

    // ------------------------------------------------------- terminal

    @Test
    fun `the terminal holds from the horizon onward`() {
        for (h in horizons) {
            val expectedStall = FrictionCurve.terminalStallMs(h)
            val expectedProbability = FrictionCurve.terminalProbability(h)
            for (t in listOf(h, h + 60_000, h * 2, h * 20)) {
                val p = atMs(t, h)
                assertEquals("horizon $h at ${t}ms", expectedStall, p.stallMs)
                assertEquals("horizon $h at ${t}ms", expectedProbability, p.probability, 0f)
            }
        }
    }

    @Test
    fun `an absurd accumulated time does not overflow or wrap`() {
        val p = FrictionCurve.frictionAt(Long.MAX_VALUE, default, floor)
        assertEquals(5000, p.stallMs)
        assertEquals(1.0f, p.probability, 0f)
    }

    @Test
    fun `a negative accumulated time is treated as zero`() {
        val p = FrictionCurve.frictionAt(-5_000L, default, floor)
        assertEquals(0, p.stallMs)
        assertEquals(0f, p.probability, 0f)
    }

    // -------------------------------------------------- monotonicity

    @Test
    fun `both dimensions are non-decreasing across the whole range`() {
        // The source table dips at four band edges. Read literally it would
        // refund friction; KNOTS resolves each boundary upward. This is the
        // assertion that keeps that true, now at every horizon, because the
        // taper scales the shape rather than moving the last knot alone.
        for (h in horizons) {
            var previous = atMs(0, h)
            var t = 0L
            while (t <= h + min(15.0)) {
                val p = atMs(t, h)
                assertTrue(
                    "horizon $h duration fell at ${t}ms: ${previous.stallMs} to ${p.stallMs}",
                    p.stallMs >= previous.stallMs,
                )
                assertTrue(
                    "horizon $h probability fell at ${t}ms: " +
                        "${previous.probability} to ${p.probability}",
                    p.probability >= previous.probability - 1e-6f,
                )
                previous = p
                t += 1_000
            }
        }
    }

    @Test
    fun `monotonic at every floor value and every horizon, not just the default`() {
        for (h in horizons) {
            for (f in listOf(0, 100, 450, 1000, 3000)) {
                var previous = 0
                var t = 0L
                while (t <= h) {
                    val stall = FrictionCurve.frictionAt(t, h, f).stallMs
                    assertTrue("horizon $h floor $f fell at ${t}ms", stall >= previous)
                    previous = stall
                    t += 5_000
                }
            }
        }
    }

    // ---------------------------------------------------------- report

    /**
     * The three horizons side by side.
     *
     * Reported rather than asserted, the same way the run length distribution
     * is. The shape assertions above pin the knots; this is for reading the
     * consequence, which is the thing a person actually has to judge: whether
     * minute fifty five of a declared hour is heavy enough to still be
     * friction and light enough to still be a lecture.
     */
    @Test
    fun `report the curve at three horizons`() {
        println("friction curve by horizon (floor ${floor}ms)")
        println("  horizon  onset   terminal  ceiling")
        for (h in horizons) {
            println(
                "  %5dm  %6s  %7s  %4dms @ %3.0f%%".format(
                    h / 60_000,
                    clock(FrictionCurve.onsetMs(h)),
                    clock(FrictionCurve.terminalMs(h)),
                    FrictionCurve.terminalStallMs(h),
                    FrictionCurve.terminalProbability(h) * 100,
                ),
            )
        }
        println()
        println("  share  " + horizons.joinToString("      ") { "${it / 60_000}m horizon" })
        for (share in listOf(0.25, 0.50, 0.75, 1.00)) {
            val cells = horizons.joinToString("  ") { h ->
                val t = (h * share).toLong()
                val p = atMs(t, h)
                "%6s %4dms @ %3.0f%%".format(clock(t), p.stallMs, p.probability * 100)
            }
            println("  %4.0f%%  %s".format(share * 100, cells))
        }
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val s = total % 60
        return if (s == 0L) "${total / 60}m" else "${total / 60}m${s}s"
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
