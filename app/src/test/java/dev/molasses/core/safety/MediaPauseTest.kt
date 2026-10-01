package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauseTest {

    @Test
    fun `the expired gate sends pause`() {
        assertTrue(MediaPause.sendsPause(OverlayKind.EXPIRED_GATE))
    }

    @Test
    fun `the walking gate sends pause`() {
        assertTrue(MediaPause.sendsPause(OverlayKind.WALK_GATE))
    }

    @Test
    fun `the entry gate never sends pause, so the user's own music is left alone`() {
        assertFalse(MediaPause.sendsPause(OverlayKind.ENTRY_GATE))
    }

    @Test
    fun `no lock sends pause, at entry or mid-session`() {
        assertFalse(MediaPause.sendsPause(OverlayKind.LOCK_AT_ENTRY))
        assertFalse(MediaPause.sendsPause(OverlayKind.LOCK_MID_SESSION))
    }

    @Test
    fun `exactly the two overlays raised over a session in progress`() {
        assertEquals(
            setOf(OverlayKind.EXPIRED_GATE, OverlayKind.WALK_GATE),
            OverlayKind.entries.filter(MediaPause::sendsPause).toSet(),
        )
    }
}
