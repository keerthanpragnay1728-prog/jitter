package dev.molasses.sensing

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bucket, tested directly.
 *
 * Tick-level failure rates are asked of the accumulator rather than of a
 * synthetic signal, because splicing stillness into gait does not produce "10%
 * of ticks failing". Each 250 ms of stillness sits inside a 3500 ms analysis
 * window, so a 10% duty cycle of bad samples pollutes closer to 90% of
 * windows. Measured: that signal never clears at all. The question the bucket
 * is meant to answer is about tick outcomes, so it is asked here.
 */
class SustainAccumulatorTest {

    private val tick = 250L

    /** @return ticks taken to clear, or -1. */
    private fun ticksToClear(
        acc: SustainAccumulator,
        limit: Int = 10_000,
        passing: (Int) -> Boolean,
    ): Int {
        for (i in 0 until limit) {
            if (acc.tick(passing(i), tick)) return acc.ticks
        }
        return -1
    }

    @Test
    fun `clean ticks clear in exactly the required time`() {
        val acc = SustainAccumulator()
        val n = ticksToClear(acc) { true }
        assertEquals("8000 ms at 250 ms per tick", 32, n)
        assertEquals(8_000.0, acc.creditMs, 1e-9)
    }

    @Test
    fun `a failing tick costs half of what a passing tick earns`() {
        val acc = SustainAccumulator()
        acc.tick(true, tick)
        assertEquals(250.0, acc.creditMs, 1e-9)
        acc.tick(false, tick)
        assertEquals(125.0, acc.creditMs, 1e-9)
    }

    @Test
    fun `credit floors at zero and never goes negative`() {
        val acc = SustainAccumulator()
        repeat(100) { acc.tick(false, tick) }
        assertEquals(0.0, acc.creditMs, 1e-9)
        assertEquals(0f, acc.fraction, 1e-6f)
    }

    @Test
    fun `credit is capped at the requirement so a long walk cannot bank extra`() {
        // Otherwise a user who walked for a minute would arrive at the next
        // gate with it already paid.
        val acc = SustainAccumulator()
        repeat(200) { acc.tick(true, tick) }
        assertEquals(8_000.0, acc.creditMs, 1e-9)
    }

    @Test
    fun `a one second stumble costs one second of walking, not the session`() {
        val acc = SustainAccumulator()
        repeat(16) { acc.tick(true, tick) } // 4 s of credit
        val before = acc.creditMs
        repeat(4) { acc.tick(false, tick) } // 1 s stumble
        assertEquals("four failing ticks at 125 ms each", before - 500.0, acc.creditMs, 1e-9)
        assertTrue("most of the credit survives", acc.creditMs > before * 0.8)
    }

    @Test
    fun `ten percent of ticks failing still clears, and by how much it costs`() {
        val rnd = Random(20260912)
        val acc = SustainAccumulator()
        val n = ticksToClear(acc) { rnd.nextDouble() >= 0.10 }
        println("10% failing: $n ticks (clean baseline 32)")
        assertTrue("must still clear, got $n", n > 0)
        // Net gain per tick is 0.9*250 - 0.1*125 = 212.5 ms, so about 38 ticks.
        assertTrue("expected roughly 38 ticks, got $n", n in 33..45)
    }

    @Test
    fun `a fifty percent failure rate clears only very slowly`() {
        val rnd = Random(7)
        val acc = SustainAccumulator()
        val n = ticksToClear(acc) { rnd.nextDouble() >= 0.50 }
        println("50% failing: $n ticks")
        // Net 0.5*250 - 0.5*125 = 62.5 ms per tick, about 128 ticks, 32 s.
        assertTrue("expected well over 100 ticks, got $n", n > 100)
    }

    @Test
    fun `below the break-even pass rate the bucket never fills`() {
        // Net gain reaches zero at a 33% pass rate, where 0.333*250 equals
        // 0.667*125. Exactly at break-even the bucket random-walks and can
        // still reach the top given long enough; measured, a 33% pass rate
        // cleared after 3540 ticks, which is 15 minutes of shaking. Below
        // break-even it drains, which is what stops alternating pass and fail
        // from being a bypass.
        val rnd = Random(11)
        val acc = SustainAccumulator()
        val n = ticksToClear(acc, limit = 20_000) { rnd.nextDouble() >= 0.75 }
        println("75% failing: ${if (n < 0) "never cleared" else "$n ticks"}")
        assertEquals(-1, n)
        assertTrue("and the bucket sits near empty", acc.creditMs < 1_000)
    }

    @Test
    fun `the break-even pass rate is one third`() {
        // Stated as an assertion so the decay factor cannot be changed without
        // someone noticing what it does to the bypass margin.
        val acc = SustainAccumulator()
        val breakEven = acc.decayFactor / (1.0 + acc.decayFactor)
        assertEquals(1.0 / 3.0, breakEven, 1e-9)
    }

    @Test
    fun `a longer tick is worth proportionally more`() {
        // What makes the result independent of delivery rate.
        val a = SustainAccumulator()
        a.tick(true, 250)
        val b = SustainAccumulator()
        b.tick(true, 500)
        assertEquals(2 * a.creditMs, b.creditMs, 1e-9)
    }

    @Test
    fun `reset empties the bucket and the tick count`() {
        val acc = SustainAccumulator()
        repeat(10) { acc.tick(true, tick) }
        acc.reset()
        assertEquals(0.0, acc.creditMs, 1e-9)
        assertEquals(0, acc.ticks)
        assertFalse(acc.passed)
    }
}
