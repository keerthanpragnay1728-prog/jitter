package dev.molasses.core.bit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitDockTest {

    @Test
    fun `typing docks it immediately`() {
        // A face reacting beside a line the user is composing is the exact
        // "pet that demands attention" failure section 05 prevents.
        assertTrue(BitDock.isDocked(typing = true, msSinceInteraction = 0))
    }

    @Test
    fun `a recent interaction keeps it out`() {
        assertFalse(BitDock.isDocked(typing = false, msSinceInteraction = 0))
        assertFalse(BitDock.isDocked(typing = false, msSinceInteraction = BitDock.IDLE_MS - 1))
    }

    @Test
    fun `it retreats once idle`() {
        assertTrue(BitDock.isDocked(typing = false, msSinceInteraction = BitDock.IDLE_MS))
    }

    @Test
    fun `a bad clock read docks rather than un-docking`() {
        // Retreating is the safe direction: the failure is Bit sitting out in
        // the open demanding attention, never Bit being too unobtrusive.
        assertTrue(BitDock.isDocked(typing = false, msSinceInteraction = -1))
    }

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
            assertFalse("$visitMs", BitDock.isDocked(typing = false, msSinceInteraction = visitMs))
        }
    }
}
