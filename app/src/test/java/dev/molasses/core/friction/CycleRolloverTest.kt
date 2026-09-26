package dev.molasses.core.friction

import dev.molasses.core.model.AppSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CycleRolloverTest {

    private val min = 60_000L

    private fun spent(horizon: Long, pending: Long) = AppSnapshot(
        pkg = "com.instagram.android",
        accumulatedMs = 40 * min,
        tierIndex = 8,
        leasesTaken = 3,
        leaseUntilAccumulatedMs = 30 * min,
        penaltyMs = 9 * min,
        horizonMs = horizon,
        pendingHorizonMs = pending,
        penaltyAnchorMs = 40 * min,
    )

    @Test
    fun `every counter starts over`() {
        val next = CycleRollover.carryOver(spent(25 * min, HorizonPolicy.NONE))
        assertEquals(0L, next.accumulatedMs)
        assertEquals(0, next.tierIndex)
        assertEquals(0, next.leasesTaken)
        assertEquals(0L, next.leaseUntilAccumulatedMs)
        assertEquals(0L, next.penaltyMs)
        assertNull(next.penaltyAnchorMs)
    }

    @Test
    fun `a pending widen lands`() {
        val next = CycleRollover.carryOver(spent(25 * min, 40 * min))
        assertEquals(40 * min, next.horizonMs)
        assertEquals(HorizonPolicy.NONE, next.pendingHorizonMs)
    }

    @Test
    fun `a horizon with nothing pending crosses unchanged`() {
        val next = CycleRollover.carryOver(spent(15 * min, HorizonPolicy.NONE))
        assertEquals(15 * min, next.horizonMs)
        assertEquals(HorizonPolicy.NONE, next.pendingHorizonMs)
    }

    @Test
    fun `an unset stored horizon reads as the default`() {
        val next = CycleRollover.carryOver(spent(0, HorizonPolicy.NONE))
        assertEquals(FrictionCurve.DEFAULT_HORIZON_MS, next.horizonMs)
    }

    @Test
    fun `the map form keeps every app`() {
        val a = spent(25 * min, 40 * min)
        val b = a.copy(pkg = "com.google.android.youtube", pendingHorizonMs = HorizonPolicy.NONE)
        val next = CycleRollover.carryOver(mapOf(a.pkg to a, b.pkg to b))
        assertEquals(setOf(a.pkg, b.pkg), next.keys)
        assertEquals(40 * min, next.getValue(a.pkg).horizonMs)
        assertEquals(25 * min, next.getValue(b.pkg).horizonMs)
    }
}
