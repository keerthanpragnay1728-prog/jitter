package dev.molasses.core.bit

import dev.molasses.core.model.AppSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitStatusTest {

    private val minute = 60_000L

    private fun app(
        accumulatedMs: Long,
        tierUnlockedUntilMs: Long,
        penaltyMs: Long = 0L,
    ) = AppSnapshot(
        pkg = "com.instagram.android",
        accumulatedMs = accumulatedMs,
        tierIndex = 0,
        gatesCleared = 0,
        tierUnlockedUntilMs = tierUnlockedUntilMs,
        gatePending = false,
        penaltyMs = penaltyMs,
    )

    @Test
    fun `nothing overdue is nothing to report`() {
        assertFalse(BitStatus.penaltyAccruing(emptyList()))
        assertFalse(BitStatus.penaltyAccruing(listOf(app(4 * minute, 5 * minute))))
    }

    @Test
    fun `passing a checkpoint without clearing it raises the alert`() {
        assertTrue(BitStatus.penaltyAccruing(listOf(app(5 * minute + 1, 5 * minute))))
    }

    @Test
    fun `exactly at the checkpoint is not yet overdue`() {
        assertFalse(BitStatus.penaltyAccruing(listOf(app(5 * minute, 5 * minute))))
    }

    @Test
    fun `one overdue app among several is enough`() {
        val apps = listOf(
            app(1 * minute, 5 * minute),
            app(12 * minute, 10 * minute),
            app(2 * minute, 5 * minute),
        )
        assertTrue(BitStatus.penaltyAccruing(apps))
    }

    @Test
    fun `a paid off cycle clears the alert even with penalty on the clock`() {
        // The ratchet only grows, so a non-zero penaltyMs says a checkpoint
        // went unpaid at some point. The glyph is about now: an alert the
        // user can still act on by clearing the gate.
        assertFalse(
            BitStatus.penaltyAccruing(
                listOf(app(12 * minute, 15 * minute, penaltyMs = 4 * minute)),
            ),
        )
    }
}
