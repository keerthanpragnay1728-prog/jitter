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
}
