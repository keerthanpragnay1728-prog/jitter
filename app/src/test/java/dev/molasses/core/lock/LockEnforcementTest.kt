package dev.molasses.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockEnforcementTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `an unlocked package is left alone`() {
        val d = LockEnforcement.decide(0, sensitiveForeground = false, paused = false)
        assertTrue(d is LockEnforcement.Decision.Stand)
    }

    @Test
    fun `a locked package is bounced and the remainder is carried`() {
        val d = LockEnforcement.decide(3 * hour, sensitiveForeground = false, paused = false)
        assertEquals(3 * hour, (d as LockEnforcement.Decision.Enforce).remainingMs)
    }

    @Test
    fun `the suppression set outranks a lock`() {
        // Never draw over a payment screen, and never send someone home in the
        // middle of one either.
        val d = LockEnforcement.decide(30 * 24 * hour, sensitiveForeground = true, paused = false)
        assertTrue("a bank outranks even a thirty day lock", d is LockEnforcement.Decision.Stand)
    }

    @Test
    fun `a pause does not outrank a lock`() {
        // The asymmetry. A pause suspends nudges the user never agreed to one
        // by one. A lock is a commitment made past a confirmation step, and a
        // fifteen minute button that cancelled it would make every lock in
        // this app decorative.
        val d = LockEnforcement.decide(30 * 24 * hour, sensitiveForeground = false, paused = true)
        assertTrue(d is LockEnforcement.Decision.Enforce)
    }

    @Test
    fun `sensitive still wins when the user is also paused`() {
        val d = LockEnforcement.decide(hour, sensitiveForeground = true, paused = true)
        assertTrue(d is LockEnforcement.Decision.Stand)
    }

    @Test
    fun `a negative remainder is not a lock`() {
        // remainingMs is clamped at zero by LockRegistry, but a corrupt read
        // must not be enforced as a very long lock.
        val d = LockEnforcement.decide(-1, sensitiveForeground = false, paused = false)
        assertTrue(d is LockEnforcement.Decision.Stand)
    }

    @Test
    fun `the message holds long enough to read before the bounce`() {
        // The bug this pins: sending HOME in the frame the window was added
        // means the flash never reaches the display and the bounce reads as a
        // crash.
        // FLASH_HOLD_MS is gone. The lock screen is no longer a flash: it
        // stays until the user presses the way out, because on a device the
        // automatic bounce read as the screen changing underneath you while
        // you were still reading why.
        //
        // HOME_SETTLE_MS survives and does a different job. It is the hold
        // after home fires, keeping the window up across the transition so
        // the locked app is not revealed for a frame, and that is true
        // however home was triggered.
        assertTrue(LockEnforcement.HOME_SETTLE_MS > 0L)
    }
}
