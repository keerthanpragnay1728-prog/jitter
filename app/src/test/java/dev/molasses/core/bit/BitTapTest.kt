package dev.molasses.core.bit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitTapTest {

    private fun tap(docked: Boolean, step: HudStep = HudStep.NONE, taps: Int = 1) =
        BitTap.onTap(docked, step, taps)

    // --------------------------------------------- the defect this prevents

    @Test
    fun `all three readout steps are reachable`() {
        // The bug: the host set "last interacted" before choosing a branch,
        // so the first tap un-docked Bit and the second took the other
        // branch. Two thirds of the readout was unreachable.
        var step = HudStep.NONE
        val walked = mutableListOf<HudStep>()
        repeat(3) {
            val action = BitTap.onTap(docked = true, hudStep = step, taps = 1)
            assertTrue("tap $it should step the readout", action is BitTap.Action.StepHud)
            step = (action as BitTap.Action.StepHud).step
            walked += step
        }
        assertEquals(listOf(HudStep.PRIMARY, HudStep.SECONDARY, HudStep.NONE), walked)
    }

    @Test
    fun `stepping the readout is not an interaction`() {
        // The rule that was missing. Reading a retreated Bit leaves it
        // retreated, so the next tap is still a step and not a poke.
        for (step in HudStep.entries) {
            assertFalse("$step", BitTap.onTap(docked = true, hudStep = step, taps = 1).undocks)
        }
    }

    @Test
    fun `a fourth tap starts the readout again`() {
        // Three taps close it; the burst counter is irrelevant while docked.
        var step = HudStep.NONE
        repeat(3) { step = (BitTap.onTap(true, step, 1) as BitTap.Action.StepHud).step }
        assertEquals(HudStep.PRIMARY, (BitTap.onTap(true, step, 4) as BitTap.Action.StepHud).step)
    }

    @Test
    fun `a tap burst does not turn a docked Bit away`() {
        // Five rapid taps on a retreated Bit steps the readout five times.
        // It cannot reach the reaction ladder at all, which is the point of
        // the split: one state, two behaviours.
        for (taps in 1..6) {
            assertTrue("$taps", BitTap.onTap(true, HudStep.NONE, taps) is BitTap.Action.StepHud)
        }
    }

    // ----------------------------------------------- the undocked behaviour

    @Test
    fun `an undocked Bit keeps the startle ladder`() {
        assertEquals(
            BitTap.Action.React(BitStateMachine.Reaction.Poked),
            tap(docked = false, taps = 1),
        )
        assertEquals(
            BitTap.Action.React(BitStateMachine.Reaction.Irritated),
            tap(docked = false, taps = 2),
        )
        assertEquals(
            BitTap.Action.React(BitStateMachine.Reaction.TurnedAway),
            tap(docked = false, taps = 5),
        )
    }

    @Test
    fun `the ladder holds past its top rung`() {
        assertEquals(
            BitTap.Action.React(BitStateMachine.Reaction.TurnedAway),
            tap(docked = false, taps = 99),
        )
    }

    @Test
    fun `a reaction is an interaction`() {
        for (taps in 1..6) {
            assertTrue("$taps", tap(docked = false, taps = taps).undocks)
        }
    }

    @Test
    fun `an undocked tap does not step the readout`() {
        // It dismisses it instead, which the host does by acting on the
        // branch rather than by carrying a step forward.
        for (step in HudStep.entries) {
            assertTrue("$step", BitTap.onTap(false, step, 1) is BitTap.Action.React)
        }
    }
}
