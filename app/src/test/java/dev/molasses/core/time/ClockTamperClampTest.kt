package dev.molasses.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTamperClampTest {

    private fun gap(
        wallDelta: Long,
        elapsedDelta: Long,
        bootChanged: Boolean = false,
    ) = ClockTamperClamp.Gap(
        lastSeenWallMs = 1_000_000,
        lastSeenElapsedMs = 50_000,
        nowWallMs = 1_000_000 + wallDelta,
        nowElapsedMs = 50_000 + elapsedDelta,
        bootIdChanged = bootChanged,
    )

    @Test
    fun `agreeing clocks credit the elapsed delta`() {
        val v = ClockTamperClamp.evaluate(gap(wallDelta = 120_000, elapsedDelta = 120_000))
        assertFalse(v.tampered)
        assertEquals(120_000, v.creditedMs)
        assertEquals(120_000, v.maxAnchorAdvanceMs)
    }

    @Test
    fun `small disagreement within tolerance is not treated as tampering`() {
        // NTP correction of 30 s.
        val v = ClockTamperClamp.evaluate(gap(wallDelta = 150_000, elapsedDelta = 120_000))
        assertFalse(v.tampered)
        assertEquals(120_000, v.creditedMs)
    }

    @Test
    fun `clock jumped forward credits the smaller delta`() {
        // The attack: 7 hours of "abstinence" fabricated in 2 minutes.
        val sevenHours = 7L * 60 * 60 * 1000
        val v = ClockTamperClamp.evaluate(gap(wallDelta = sevenHours, elapsedDelta = 120_000))
        assertTrue(v.tampered)
        assertEquals("must credit monotonic, not wall", 120_000, v.creditedMs)
        assertEquals(120_000, v.maxAnchorAdvanceMs)
        assertTrue(v.reason.contains("forward"))
    }

    @Test
    fun `clock jumped backward credits the smaller delta and never goes negative`() {
        val v = ClockTamperClamp.evaluate(gap(wallDelta = -3_600_000, elapsedDelta = 120_000))
        assertTrue(v.tampered)
        assertEquals(0, v.creditedMs)
        assertEquals(120_000, v.maxAnchorAdvanceMs)
        assertTrue(v.reason.contains("backward"))
    }

    @Test
    fun `anchor advance is capped at the monotonic delta under tampering`() {
        val day = 24L * 60 * 60 * 1000
        val v = ClockTamperClamp.evaluate(gap(wallDelta = day, elapsedDelta = 300_000))
        assertTrue(v.maxAnchorAdvanceMs <= 300_000)
    }

    @Test
    fun `across a boot boundary only the wall clock is trusted`() {
        // elapsedRealtime reset to near zero, so elapsedDelta is very negative.
        val v = ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = 1_000_000,
                lastSeenElapsedMs = 9_000_000,
                nowWallMs = 1_000_000 + 600_000,
                nowElapsedMs = 30_000,
                bootIdChanged = true,
            ),
        )
        assertEquals(600_000, v.creditedMs)
        assertFalse("a reboot is not tampering", v.tampered)
        assertTrue(v.reason.contains("boot"))
    }

    @Test
    fun `a backward wall clock across a boot credits zero`() {
        val v = ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = 1_000_000,
                lastSeenElapsedMs = 9_000_000,
                nowWallMs = 900_000,
                nowElapsedMs = 30_000,
                bootIdChanged = true,
            ),
        )
        assertEquals(0, v.creditedMs)
    }

    @Test
    fun `tolerance boundary is exactly sixty seconds`() {
        val at = ClockTamperClamp.evaluate(gap(wallDelta = 180_000, elapsedDelta = 120_000))
        assertFalse("60s exactly is within tolerance", at.tampered)
        val over = ClockTamperClamp.evaluate(gap(wallDelta = 180_001, elapsedDelta = 120_000))
        assertTrue("60.001s is tampering", over.tampered)
    }
}
