package dev.molasses.core.remind

import dev.molasses.core.remind.ReminderArming.Armed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReminderArmingTest {

    @Test
    fun `exact when allowed and the exact call succeeds, and inexact is never tried`() {
        var inexactTried = false
        val armed = ReminderArming.arm(exactAllowed = true, tryExact = { true }, tryInexact = { inexactTried = true; true })
        assertEquals(Armed.EXACT, armed)
        assertFalse(inexactTried)
    }

    @Test
    fun `inexact when exact is not allowed, and exact is never tried`() {
        var exactTried = false
        val armed = ReminderArming.arm(exactAllowed = false, tryExact = { exactTried = true; true }, tryInexact = { true })
        assertEquals(Armed.INEXACT, armed)
        assertFalse(exactTried)
    }

    @Test
    fun `inexact when the exact call fails after the check allowed it`() {
        assertEquals(Armed.INEXACT, ReminderArming.arm(exactAllowed = true, tryExact = { false }, tryInexact = { true }))
    }

    @Test
    fun `not armed when nothing was scheduled, never inexact`() {
        assertEquals(Armed.NOT_ARMED, ReminderArming.arm(exactAllowed = true, tryExact = { false }, tryInexact = { false }))
        assertEquals(Armed.NOT_ARMED, ReminderArming.arm(exactAllowed = false, tryExact = { true }, tryInexact = { false }))
    }
}
