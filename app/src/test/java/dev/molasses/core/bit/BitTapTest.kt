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
    fun `reading the readout is not an interaction, but closing it is`() {
        // The rule, both halves. Opening and advancing leave Bit retreated,
        // so the next tap is still a step and not a poke. The step that
        // arrives back at NONE is the user putting it away, and that hands
        // Bit back.
        assertFalse(
            "opening it",
            BitTap.onTap(docked = true, hudStep = HudStep.NONE, taps = 1).undocks,
        )
        assertFalse(
            "advancing it",
            BitTap.onTap(docked = true, hudStep = HudStep.PRIMARY, taps = 1).undocks,
        )
        assertTrue(
            "closing it",
            BitTap.onTap(docked = true, hudStep = HudStep.SECONDARY, taps = 1).undocks,
        )
    }

    @Test
    fun `tap tap tap on a docked Bit returns a face`() {
        // The lockup this exists to prevent, walked the way the host walks it.
        //
        // isDocked is derived from a clock only an interaction moves, and the
        // tap was the only thing that moved it. With no exit from the readout
        // cycle, a docked Bit stepped three readouts forever and no number of
        // taps ever reached the reaction ladder. The faces were gone.
        var docked = true
        var step = HudStep.NONE
        val steps = mutableListOf<HudStep>()
        repeat(3) { i ->
            val action = BitTap.onTap(docked, step, taps = i + 1)
            assertTrue("tap ${i + 1} should still be a readout step", action is BitTap.Action.StepHud)
            step = (action as BitTap.Action.StepHud).step
            steps += step
            if (action.undocks) docked = false
        }
        assertEquals(listOf(HudStep.PRIMARY, HudStep.SECONDARY, HudStep.NONE), steps)
        assertFalse("three taps must hand Bit back", docked)

        // And the ladder is live again from the next tap.
        assertTrue(BitTap.onTap(docked, step, taps = 4) is BitTap.Action.React)
    }

    @Test
    fun `a poke is reachable once the burst window has lapsed`() {
        // Within one burst the fourth tap reads as pestering, which is
        // correct: four rapid taps is four rapid taps. A tap after the host's
        // window resets the counter, and that is an ordinary poke.
        var docked = true
        var step = HudStep.NONE
        repeat(3) { i ->
            val action = BitTap.onTap(docked, step, taps = i + 1) as BitTap.Action.StepHud
            step = action.step
            if (action.undocks) docked = false
        }
        assertEquals(
            BitTap.Action.React(BitStateMachine.Reaction.Poked),
            BitTap.onTap(docked, step, taps = 1),
        )
    }

    @Test
    fun `a tap burst does not turn a docked Bit away mid-readout`() {
        // Rapid taps while the readout is open step it rather than reaching
        // the reaction ladder, which is the point of the split: one state,
        // two behaviours. Only the closing step crosses over.
        for (taps in 1..6) {
            assertTrue("$taps", BitTap.onTap(true, HudStep.NONE, taps) is BitTap.Action.StepHud)
            assertTrue("$taps", BitTap.onTap(true, HudStep.PRIMARY, taps) is BitTap.Action.StepHud)
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
