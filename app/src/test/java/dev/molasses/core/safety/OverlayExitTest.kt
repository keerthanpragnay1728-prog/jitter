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
        assertEquals(3, homeFirst.size)
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
    fun `the entry gate is unchanged, its exit is TAKE ME OUT at zero only`() {
        assertEquals(null, OverlayExit.shown(HomeFirst.Overlay.ENTRY_GATE, 8_000L))
        assertEquals(OverlayExit.Control.TAKE_ME_OUT, OverlayExit.shown(HomeFirst.Overlay.ENTRY_GATE, 0L))
    }
}
