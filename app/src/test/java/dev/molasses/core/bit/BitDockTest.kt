package dev.molasses.core.bit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitDockTest {

    /** No text and no keystroke to speak of, which is the ordinary console. */
    private fun idle(msSinceInteraction: Long) =
        BitDock.isDocked(hasText = false, msSinceInteraction, msSinceKeystroke = Long.MAX_VALUE)

    @Test
    fun `typing docks it immediately`() {
        // A face reacting beside a line the user is composing is the exact
        // "pet that demands attention" failure section 05 prevents.
        assertTrue(
            BitDock.isDocked(hasText = true, msSinceInteraction = 0, msSinceKeystroke = 0),
        )
    }

    @Test
    fun `a recent interaction keeps it out`() {
        assertFalse(idle(0))
        assertFalse(idle(BitDock.IDLE_MS - 1))
    }

    @Test
    fun `it retreats once idle`() {
        assertTrue(idle(BitDock.IDLE_MS))
    }

    @Test
    fun `a bad clock read docks rather than un-docking`() {
        // Retreating is the safe direction: the failure is Bit sitting out in
        // the open demanding attention, never Bit being too unobtrusive.
        assertTrue(idle(-1))
    }

    // ------------------------------------- the text retreat and its override

    @Test
    fun `a touch more recent than the keystroke hands Bit back`() {
        // The lockup this is the fix for. The text clause used to be
        // unconditional, so it was a second reason to be docked that no
        // gesture could clear: BitTap's closing tap reports an interaction,
        // the host writes it to the interaction clock, and the answer was
        // docked anyway. The readout cycled forever and no number of taps
        // returned a face, which is the exact trap BitTap's exit exists to
        // prevent, reached through the other clause.
        assertFalse(
            BitDock.isDocked(hasText = true, msSinceInteraction = 0, msSinceKeystroke = 2_000),
        )
    }

    @Test
    fun `the next keystroke retreats again`() {
        // The override is not a mode. It lasts until the user goes back to
        // composing, which is the moment the keystroke clock overtakes.
        assertTrue(
            BitDock.isDocked(hasText = true, msSinceInteraction = 2_000, msSinceKeystroke = 0),
        )
    }

    @Test
    fun `a tie docks, because retreating is the safe direction`() {
        // A tap landing in the same millisecond as a keystroke is not a
        // request for anything, and the ambient rule wins the tie for the
        // same reason a bad clock read does.
        assertTrue(
            BitDock.isDocked(hasText = true, msSinceInteraction = 5, msSinceKeystroke = 5),
        )
    }

    @Test
    fun `the override cannot outlive the idle retreat`() {
        // Ordering, stated as a test rather than trusted to the order of two
        // branches. Idle is checked first, so a stale touch on a prompt with
        // text is still a retreat and not a summons that never expires.
        assertTrue(
            BitDock.isDocked(
                hasText = true,
                msSinceInteraction = BitDock.IDLE_MS,
                msSinceKeystroke = BitDock.IDLE_MS + 10_000,
            ),
        )
    }

    @Test
    fun `an empty prompt ignores the keystroke clock entirely`() {
        // Submitting a command clears the prompt and leaves the keystroke
        // clock wherever it was. That must not decide anything.
        assertFalse(
            BitDock.isDocked(hasText = false, msSinceInteraction = 0, msSinceKeystroke = 0),
        )
    }

    // ----------------------------------------------------------- the numbers

    @Test
    fun `the idle threshold outlasts a multi-tap gesture`() {
        // Five rapid taps is a real gesture in this app. Docking between two
        // of them would swap the behaviour mid-gesture.
        assertTrue(BitDock.IDLE_MS > 5 * 1_200L)
    }

    @Test
    fun `the face is on screen long enough to blink more than once`() {
        // The reasoning behind thirty seconds, made structural. The blink
        // band is three to seven, so a window shorter than two full maximum
        // intervals can hold one blink and reads as a twitch rather than as
        // breathing. At eight seconds, which is what this used to be, the
        // face was the exception and the blink was nearly unobservable.
        assertTrue(
            "IDLE_MS must hold at least two maximum blink intervals",
            BitDock.IDLE_MS >= 2 * BitStateMachine.BLINK_MAX_INTERVAL_MS,
        )
    }

    @Test
    fun `an ordinary visit to the launcher does not end in a retreat`() {
        // Coming home between two apps is a second or two; a deliberate look
        // is five to fifteen. The retreat is meant to mean the user went
        // away, not that they paused.
        for (visitMs in listOf(1_000L, 3_000L, 15_000L)) {
            assertFalse("$visitMs", idle(visitMs))
        }
    }

    @Test
    fun `three taps on a prompt with text end with a face`() {
        // The reported failure, walked end to end across both pure pieces.
        // BitTap decides the step and whether it counted; BitDock decides
        // what that means for the face. Neither one alone could have caught
        // this, because BitTap is handed a Boolean and never learns why.
        // The user typed a second ago and last touched Bit five seconds ago,
        // so the keystroke is the more recent clock and Bit has retreated.
        var interactionMs = 5_000L
        val keystrokeMs = 1_000L
        var step = HudStep.NONE
        repeat(3) { i ->
            val docked = BitDock.isDocked(
                hasText = true,
                msSinceInteraction = interactionMs,
                msSinceKeystroke = keystrokeMs,
            )
            assertTrue("tap ${i + 1} should be a readout step", docked)
            val action = BitTap.onTap(docked, step, taps = i + 1)
            step = (action as BitTap.Action.StepHud).step
            if (action.undocks) interactionMs = 0L
        }
        assertFalse(
            "the closing tap must hand Bit back, text in the prompt or not",
            BitDock.isDocked(
                hasText = true,
                msSinceInteraction = interactionMs,
                msSinceKeystroke = keystrokeMs,
            ),
        )
    }
}
