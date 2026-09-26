package dev.molasses.core.safety

import dev.molasses.core.safety.MediaPause.Overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauseTest {

    @Test
    fun `the entry gate never sends pause`() {
        assertFalse(MediaPause.sendsPause(Overlay.ENTRY_GATE))
        assertEquals(Overlay.ENTRY_GATE, MediaPause.leaseGate(expired = false))
    }

    @Test
    fun `the lease expired gate sends pause`() {
        assertTrue(MediaPause.sendsPause(Overlay.EXPIRED_GATE))
        assertEquals(Overlay.EXPIRED_GATE, MediaPause.leaseGate(expired = true))
    }

    @Test
    fun `the walking gate sends pause`() {
        assertTrue(MediaPause.sendsPause(Overlay.WALK_GATE))
    }

    @Test
    fun `a lock screen raised as the app opens never sends pause`() {
        assertFalse(MediaPause.sendsPause(Overlay.LOCK_AT_ENTRY))
        assertEquals(Overlay.LOCK_AT_ENTRY, MediaPause.lock(atEntry = true))
    }

    @Test
    fun `a lock screen raised mid-session sends pause`() {
        assertTrue(MediaPause.sendsPause(Overlay.LOCK_MID_SESSION))
        assertEquals(Overlay.LOCK_MID_SESSION, MediaPause.lock(atEntry = false))
    }
}
