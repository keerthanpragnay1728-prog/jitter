package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OverlayExitTest {

    /** Every state a countdown can be in, from the longest gate down to zero. */
    private val states = listOf(30_000L, 20_000L, 8_000L, 1_000L, 1L, 0L)

    @Test
    fun `every overlay shows an exit in every state`() {
        // Every overlay sends its app home and outlives the session, so
        // every one needs its own way out. The count is pinned so a new kind
        // is a visible decision here as well as a branch in OverlayExit.
        assertEquals(5, OverlayKind.entries.size)
        for (overlay in OverlayKind.entries) {
            for (ms in states) {
                assertNotNull("$overlay at ${ms}ms has no way out", OverlayExit.shown(overlay, ms))
            }
        }
    }

    @Test
    fun `the walking gate's exit is the expired gate's, ARCHITECT'S SPACE`() {
        for (ms in states) {
            assertEquals(OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(OverlayKind.WALK_GATE, ms))
            assertEquals(OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(OverlayKind.EXPIRED_GATE, ms))
        }
    }

    @Test
    fun `the entry gate shows ARCHITECT'S SPACE in every state, not only at zero`() {
        for (ms in states) {
            assertEquals("entry gate at ${ms}ms", OverlayExit.Control.ARCHITECTS_SPACE, OverlayExit.shown(OverlayKind.ENTRY_GATE, ms))
        }
    }
}
