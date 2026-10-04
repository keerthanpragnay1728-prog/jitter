package dev.molasses.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRoleTest {

    private val own = "org.jitteros.app"
    private var resolves = 0

    private fun resolver(pkg: String?): () -> String? = {
        resolves++
        pkg
    }

    @Test
    fun `the role decides when it answers, and the resolver is not asked`() {
        assertTrue(HomeRole.isDefault(roleHeld = true, ownPackage = own, resolvedHomePackage = resolver("com.android.launcher3")))
        assertFalse(HomeRole.isDefault(roleHeld = false, ownPackage = own, resolvedHomePackage = resolver(own)))
        assertEquals("no IPC when the role answered", 0, resolves)
    }

    @Test
    fun `with no role answer, the default resolution decides`() {
        assertTrue(HomeRole.isDefault(roleHeld = null, ownPackage = own, resolvedHomePackage = resolver(own)))
        assertFalse(HomeRole.isDefault(roleHeld = null, ownPackage = own, resolvedHomePackage = resolver("com.sec.android.app.launcher")))
        assertEquals(2, resolves)
    }

    @Test
    fun `the resolver itself, or nothing at all, is not us`() {
        // MATCH_DEFAULT_ONLY with no default chosen resolves to the system's
        // chooser, which lives in the android package.
        assertFalse(HomeRole.isDefault(roleHeld = null, ownPackage = own, resolvedHomePackage = resolver("android")))
        assertFalse(HomeRole.isDefault(roleHeld = null, ownPackage = own, resolvedHomePackage = resolver(null)))
    }

    @Test
    fun `an empty own package never matches, even an empty resolution`() {
        assertFalse(HomeRole.isDefault(roleHeld = null, ownPackage = "", resolvedHomePackage = resolver("")))
    }
}
