package dev.molasses.core.bit

import dev.molasses.core.model.AppSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitStatusTest {

    private val minute = 60_000L
    private val horizon = dev.molasses.core.friction.FrictionCurve.DEFAULT_HORIZON_MS

    private fun app(
        accumulatedMs: Long,
        leaseUntilAccumulatedMs: Long,
        penaltyMs: Long = 0L,
        leasesTaken: Int = 1,
    ) = AppSnapshot(
        pkg = "com.instagram.android",
        accumulatedMs = accumulatedMs,
        tierIndex = 0,
        leasesTaken = leasesTaken,
        leaseUntilAccumulatedMs = leaseUntilAccumulatedMs,
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

    // --------------------------------------------------- what the mood reads

    @Test
    fun `two apps at ten minutes each are not twenty minutes deep`() {
        // The defect this exists to prevent: feeding moodFor the sum across
        // apps put Bit in a mood no app's friction justified. The curve is
        // per package.
        val apps = listOf(app(10 * minute, 10 * minute), app(10 * minute, 10 * minute))
        assertEquals(10 * minute, BitStatus.deepestMs(apps))
        assertEquals(
            BitStateMachine.Mood.VIGILANT,
            BitStateMachine.moodFor(BitStatus.deepestMs(apps), horizon),
        )
        // What the bug produced, for contrast.
        assertEquals(
            BitStateMachine.Mood.ANNOYED,
            BitStateMachine.moodFor(apps.sumOf { it.accumulatedMs }, horizon),
        )
    }

    @Test
    fun `a tie breaks on the package name, not on iteration order`() {
        // The ledger reports one app's time, tier and penalty on one line.
        // Three fields describing two different apps would be worse than
        // showing none of them.
        val a = AppSnapshot("com.a", 10 * minute, 2, 0, 10 * minute, 0)
        val b = AppSnapshot("com.b", 10 * minute, 3, 0, 10 * minute, 0)
        assertEquals("com.a", BitStatus.deepest(listOf(a, b))?.pkg)
        assertEquals("com.a", BitStatus.deepest(listOf(b, a))?.pkg)
    }

    @Test
    fun `no apps has no deepest app`() {
        assertEquals(null, BitStatus.deepest(emptyList()))
    }

    @Test
    fun `the deepest app is what counts, not the first or the last`() {
        val apps = listOf(
            app(2 * minute, 5 * minute),
            app(21 * minute, 25 * minute),
            app(4 * minute, 5 * minute),
        )
        assertEquals(21 * minute, BitStatus.deepestMs(apps))
    }

    @Test
    fun `no apps is zero, not a crash`() {
        assertEquals(0L, BitStatus.deepestMs(emptyList()))
        assertEquals(
            BitStateMachine.Mood.IDLE,
            BitStateMachine.moodFor(BitStatus.deepestMs(emptyList()), horizon),
        )
    }

    // ------------------------------------------------------- the burst latch

    private val terminal = dev.molasses.core.friction.FrictionCurve.TERMINAL_MS

    @Test
    fun `crossing into the terminal fires once`() {
        assertTrue(BitStatus.crossedTerminal(terminal - 1, terminal, horizon))
    }

    @Test
    fun `staying past the terminal does not re-fire`() {
        // Once per entry, not once per scroll and not once per foreground.
        assertFalse(BitStatus.crossedTerminal(terminal, terminal, horizon))
        assertFalse(BitStatus.crossedTerminal(terminal, terminal + 60_000, horizon))
        assertFalse(BitStatus.crossedTerminal(terminal + 60_000, terminal + 120_000, horizon))
    }

    @Test
    fun `the crossing is measured against that app's horizon`() {
        // A fixed terminal here would burst at a moment the engine did not
        // saturate at. Eighteen minutes crosses on the default horizon and is
        // nowhere near it on a sixty minute one.
        val long = 60 * minute
        assertTrue(BitStatus.crossedTerminal(terminal - 1, terminal, horizon))
        assertFalse(BitStatus.crossedTerminal(terminal - 1, terminal, long))
        assertTrue(BitStatus.crossedTerminal(long - 1, long, long))
    }

    @Test
    fun `a first observation already past the terminal does not fire`() {
        // A process that has just started did not see the crossing, and a
        // burst for a threshold passed twenty minutes ago is a lie about when.
        assertFalse(BitStatus.crossedTerminal(null, terminal, horizon))
        assertFalse(BitStatus.crossedTerminal(null, terminal + 600_000, horizon))
    }

    @Test
    fun `a cycle rollover re-arms it, and nothing else does`() {
        // The deepest app's accumulated time only ever falls at a rollover,
        // so the next crossing after one is the next burst, and there is no
        // separate latch to reset.
        assertFalse(BitStatus.crossedTerminal(terminal + 60_000, 0, horizon))
        assertTrue(BitStatus.crossedTerminal(0, terminal, horizon))
    }

    @Test
    fun `approaching the terminal without reaching it does not fire`() {
        assertFalse(BitStatus.crossedTerminal(terminal - 120_000, terminal - 1, horizon))
    }
}
