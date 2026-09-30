package dev.molasses.core.lease

import dev.molasses.core.lease.GateControls.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateControlsTest {

    private val full = 30_000L

    @Test
    fun `on the expired gate the exit is visible at the full countdown and at zero`() {
        assertEquals(Exit.ARCHITECTS_SPACE, GateControls.visible(expired = true, remainingMs = full).exit)
        assertEquals(Exit.ARCHITECTS_SPACE, GateControls.visible(expired = true, remainingMs = 0L).exit)
    }

    @Test
    fun `the lease options appear only at zero`() {
        assertFalse(GateControls.visible(expired = true, remainingMs = full).leases)
        assertFalse(GateControls.visible(expired = true, remainingMs = 1L).leases)
        assertTrue(GateControls.visible(expired = true, remainingMs = 0L).leases)
        assertFalse(GateControls.visible(expired = false, remainingMs = full).leases)
        assertTrue(GateControls.visible(expired = false, remainingMs = 0L).leases)
    }

    @Test
    fun `block is visible throughout the expired gate and never on the entry gate`() {
        for (ms in listOf(full, 12_345L, 1L, 0L)) {
            assertTrue(GateControls.visible(expired = true, remainingMs = ms).block)
            assertFalse(GateControls.visible(expired = false, remainingMs = ms).block)
        }
    }

    @Test
    fun `the entry gate's exit is ARCHITECT'S SPACE from the first frame, as on the expired gate`() {
        for (ms in listOf(full, 12_345L, 1L, 0L)) {
            assertEquals(Exit.ARCHITECTS_SPACE, GateControls.visible(expired = false, remainingMs = ms).exit)
            assertEquals(Exit.ARCHITECTS_SPACE, GateControls.visible(expired = true, remainingMs = ms).exit)
        }
        assertEquals("one exit, not two", listOf(Exit.ARCHITECTS_SPACE), Exit.entries.toList())
    }
}
