package dev.molasses.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierPolicyTest {

    private val min = 60_000L

    @Test
    fun `ladder matches the brief`() {
        assertEquals(0, TierPolicy.indexFor(0))
        assertEquals(0, TierPolicy.indexFor(4 * min + 59_999))
        assertEquals(1, TierPolicy.indexFor(5 * min))
        assertEquals(1, TierPolicy.indexFor(9 * min))
        assertEquals(2, TierPolicy.indexFor(10 * min))
        assertEquals(3, TierPolicy.indexFor(15 * min))
        assertEquals(4, TierPolicy.indexFor(20 * min))
    }

    @Test
    fun `stall durations match the brief`() {
        assertEquals(0L, TierPolicy.stallMsFor(0))
        assertEquals(1_000L, TierPolicy.stallMsFor(1))
        assertEquals(3_000L, TierPolicy.stallMsFor(2))
        assertEquals(5_000L, TierPolicy.stallMsFor(3))
        assertEquals(5_000L, TierPolicy.stallMsFor(4))
    }

    @Test
    fun `terminal tier does not silently cap the index and pins the stall`() {
        // SS0.1 ambiguity 1: past 20 min the stall stays at 5000 ms and the
        // gate re-arms every 5 min indefinitely.
        assertEquals(5, TierPolicy.indexFor(25 * min))
        assertEquals(12, TierPolicy.indexFor(60 * min))
        assertEquals(5_000L, TierPolicy.stallMsFor(5))
        assertEquals(5_000L, TierPolicy.stallMsFor(48))
        assertTrue(TierPolicy.isTerminal(4))
        assertTrue(TierPolicy.isTerminal(99))
        assertFalse(TierPolicy.isTerminal(3))
    }

    @Test
    fun `gate is armed on every tier above zero`() {
        assertFalse(TierPolicy.tierFor(0).gateOnEntry)
        for (i in 1..8) assertTrue(TierPolicy.tierFor(i).gateOnEntry)
    }

    @Test
    fun `negative accumulation cannot produce a negative tier`() {
        assertEquals(0, TierPolicy.indexFor(-1))
        assertEquals(0, TierPolicy.indexFor(Long.MIN_VALUE))
    }
}
