package dev.molasses.core.lock

import dev.molasses.core.command.CommandRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockRequestTest {

    private val day = 24L * 60 * 60 * 1000
    private val threshold = CommandRegistry.CONFIRM_ABOVE_MS

    private fun evaluate(
        durationMs: Long,
        standingMs: Long = 0L,
        confirmed: Boolean = false,
    ) = LockRequest.evaluate(durationMs, standingMs, threshold, confirmed)

    @Test
    fun `a short lock arms outright`() {
        assertEquals(LockRequest.Verdict.Arm(60_000), evaluate(60_000))
    }

    @Test
    fun `exactly a day arms, a millisecond over confirms`() {
        assertTrue(evaluate(day) is LockRequest.Verdict.Arm)
        assertTrue(evaluate(day + 1) is LockRequest.Verdict.Confirm)
    }

    @Test
    fun `the second pass arms what the first echoed`() {
        assertEquals(
            LockRequest.Verdict.Arm(30 * day),
            evaluate(30 * day, confirmed = true),
        )
    }

    @Test
    fun `too short is reported before confirmation is asked for`() {
        // Asking someone to confirm thirty days and then telling them it
        // changed nothing is two steps to an outcome knowable at the first.
        val v = evaluate(30 * day, standingMs = 30 * day)
        assertEquals(LockRequest.Verdict.TooShort(30 * day), v)
    }

    @Test
    fun `an equal lock is too short, not an extension`() {
        assertTrue(evaluate(day, standingMs = day) is LockRequest.Verdict.TooShort)
    }

    @Test
    fun `a longer lock over a shorter one extends`() {
        assertTrue(evaluate(7 * day, standingMs = day) is LockRequest.Verdict.Confirm)
        assertTrue(evaluate(7 * day, standingMs = day, confirmed = true) is LockRequest.Verdict.Arm)
    }

    @Test
    fun `a zero or negative duration is never an unlock`() {
        assertEquals(LockRequest.Verdict.Invalid, evaluate(0))
        assertEquals(LockRequest.Verdict.Invalid, evaluate(-1))
        assertEquals(LockRequest.Verdict.Invalid, evaluate(-30 * day, standingMs = 30 * day))
    }

    @Test
    fun `a null threshold disables the step rather than always asking`() {
        assertTrue(
            LockRequest.evaluate(30 * day, 0L, confirmAboveMs = null) is LockRequest.Verdict.Arm,
        )
    }

    // ------------------------------------------------- the two paths agree

    @Test
    fun `the scrubber uses the same threshold as the typed path`() {
        // The guard this whole file exists for. If a second way to arm a lock
        // reads a different number, the confirmation step is decorative.
        val registry = CommandRegistry(
            CommandRegistry.Keys(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            ),
        )
        assertEquals(
            "the block row is the one source of the threshold",
            CommandRegistry.CONFIRM_ABOVE_MS,
            registry.specForVerb("block")?.requiresConfirmAboveMs,
        )
    }

    @Test
    fun `every ladder step splits the same way on both paths`() {
        // The scrubber can only offer LockLadder steps, so this enumerates
        // every duration it can actually produce.
        for (step in LockLadder.STEPS_MS) {
            val verdict = evaluate(step)
            val expectConfirm = step > CommandRegistry.CONFIRM_ABOVE_MS
            if (expectConfirm) {
                assertTrue("$step should confirm", verdict is LockRequest.Verdict.Confirm)
            } else {
                assertTrue("$step should arm", verdict is LockRequest.Verdict.Arm)
            }
        }
    }

    @Test
    fun `the ladder has steps on both sides of the threshold`() {
        // A vacuous pass above would be worse than a failure: it would mean
        // the confirmation step is never reachable from the scrubber.
        assertTrue(LockLadder.STEPS_MS.any { it <= CommandRegistry.CONFIRM_ABOVE_MS })
        assertTrue(LockLadder.STEPS_MS.any { it > CommandRegistry.CONFIRM_ABOVE_MS })
    }
}
