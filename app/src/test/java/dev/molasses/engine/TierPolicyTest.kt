package dev.molasses.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tier index is a count of five minute blocks and nothing else. The
 * ladder's stall, entry and terminal functions were deleted with their last
 * callers; friction is `FrictionCurveTest`'s, and terminal is
 * `FrictionCurve.isTerminal`'s.
 */
class TierPolicyTest {

    private val min = 60_000L

    @Test
    fun `the index counts whole five minute blocks`() {
        assertEquals(0, TierPolicy.indexFor(0))
        assertEquals(0, TierPolicy.indexFor(4 * min + 59_999))
        assertEquals(1, TierPolicy.indexFor(5 * min))
        assertEquals(1, TierPolicy.indexFor(9 * min))
        assertEquals(2, TierPolicy.indexFor(10 * min))
    }

    @Test
    fun `the index does not saturate`() {
        // A faithful count of use rather than a capped rung.
        assertEquals(5, TierPolicy.indexFor(25 * min))
        assertEquals(12, TierPolicy.indexFor(60 * min))
    }

    @Test
    fun `negative accumulation cannot produce a negative tier`() {
        assertEquals(0, TierPolicy.indexFor(-1))
        assertEquals(0, TierPolicy.indexFor(Long.MIN_VALUE))
    }
}
