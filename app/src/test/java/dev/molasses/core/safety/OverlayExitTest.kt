package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OverlayExitTest {

    /** Every state a countdown can be in, from the longest gate down to zero. */
    private val states = listOf(30_000L, 20_000L, 8_000L, 1_000L, 1L, 0L)

    @Test
    fun `every home-first overlay shows an exit in every state`() {
        val homeFirst = HomeFirst.Overlay.entries.filter(HomeFirst::sendsHome)
        // The list is not empty by accident: the invariant has to have
        // something to hold over.
        assertEquals(5, homeFirst.size)
        for (overlay in homeFirst) {
            for (ms in states) {
                assertNotNull("$overlay at ${ms}ms has no way out", OverlayExit.shown(overlay, ms))
            }
        }
    }

    @Test
    fun `the walking gate's exit is the expired gate's, ARCHITECT'S SPACE`() {
        for (ms in states) {
            assertEquals(OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(HomeFirst.Overlay.WALK_GATE, ms))
            assertEquals(OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(HomeFirst.Overlay.EXPIRED_GATE, ms))
        }
    }

    @Test
    fun `the entry gate shows ARCHITECT'S SPACE in every state, not only at zero`() {
        for (ms in states) {
            assertEquals("entry gate at ${ms}ms", OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(HomeFirst.Overlay.ENTRY_GATE, ms))
        }
    }

    @Test
    fun `every overlay, home-first or not, shows an exit in every state`() {
        for (overlay in HomeFirst.Overlay.entries) {
            for (ms in states) {
                assertNotNull("$overlay at ${ms}ms has no way out", OverlayExit.shown(overlay, ms))
            }
        }
    }
}
