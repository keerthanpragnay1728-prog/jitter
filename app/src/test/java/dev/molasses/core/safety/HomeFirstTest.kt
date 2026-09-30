package dev.molasses.core.safety

import dev.molasses.core.safety.HomeFirst.Overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFirstTest {

    @Test
    fun `the expired gate, the walking gate and a mid-session lock send home`() {
        assertTrue(HomeFirst.sendsHome(Overlay.EXPIRED_GATE))
        assertTrue(HomeFirst.sendsHome(Overlay.WALK_GATE))
        assertTrue(HomeFirst.sendsHome(Overlay.LOCK_MID_SESSION))
    }

    @Test
    fun `the entry gate and a lock at entry send home too, so autoplay behind them stops`() {
        assertTrue(HomeFirst.sendsHome(Overlay.ENTRY_GATE))
        assertTrue(HomeFirst.sendsHome(Overlay.LOCK_AT_ENTRY))
    }

    @Test
    fun `every overlay sends home`() {
        for (overlay in Overlay.entries) assertTrue("$overlay", HomeFirst.sendsHome(overlay))
    }

    @Test
    fun `kinds come from the flags the overlays are shown with`() {
        assertEquals(Overlay.EXPIRED_GATE, HomeFirst.leaseGate(expired = true))
        assertEquals(Overlay.ENTRY_GATE, HomeFirst.leaseGate(expired = false))
        assertEquals(Overlay.LOCK_AT_ENTRY, HomeFirst.lock(atEntry = true))
        assertEquals(Overlay.LOCK_MID_SESSION, HomeFirst.lock(atEntry = false))
    }
}
