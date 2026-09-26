package dev.molasses.core.safety

import dev.molasses.core.safety.OverlayAudio.Overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAudioTest {

    @Test
    fun `the entry gate takes no focus and sends no pause`() {
        assertFalse(OverlayAudio.silences(Overlay.ENTRY_GATE))
        assertEquals(Overlay.ENTRY_GATE, OverlayAudio.leaseGate(expired = false))
    }

    @Test
    fun `the lease expired gate silences`() {
        assertTrue(OverlayAudio.silences(Overlay.EXPIRED_GATE))
        assertEquals(Overlay.EXPIRED_GATE, OverlayAudio.leaseGate(expired = true))
    }

    @Test
    fun `the walking gate silences`() {
        assertTrue(OverlayAudio.silences(Overlay.WALK_GATE))
    }

    @Test
    fun `a lock screen raised as the app opens takes no focus and sends no pause`() {
        assertFalse(OverlayAudio.silences(Overlay.LOCK_AT_ENTRY))
        assertEquals(Overlay.LOCK_AT_ENTRY, OverlayAudio.lock(atEntry = true))
    }

    @Test
    fun `a lock screen raised mid-session silences`() {
        assertTrue(OverlayAudio.silences(Overlay.LOCK_MID_SESSION))
        assertEquals(Overlay.LOCK_MID_SESSION, OverlayAudio.lock(atEntry = false))
    }

    @Test
    fun `exactly the two entry overlays are left unsilenced`() {
        assertEquals(
            setOf(Overlay.ENTRY_GATE, Overlay.LOCK_AT_ENTRY),
            Overlay.entries.filterNot(OverlayAudio::silences).toSet(),
        )
    }
}
