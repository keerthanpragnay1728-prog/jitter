package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivePackagesTest {

    @Test
    fun `each shipped default matches itself`() {
        for (prefix in SensitivePackages.DEFAULT_PREFIXES) {
            assertTrue(prefix, SensitivePackages.isSensitive(prefix))
        }
    }

    @Test
    fun `a prefix matches a package below it`() {
        assertTrue(SensitivePackages.isSensitive("com.icicibank.imobile"))
        assertTrue(SensitivePackages.isSensitive("com.phonepe.app"))
        assertTrue(SensitivePackages.isSensitive("net.one97.paytm.upi"))
    }

    @Test
    fun `a prefix does not match a package that merely starts with the same letters`() {
        // The permissive failure here would silently disable friction for an
        // unrelated app, so it is worth a test of its own.
        assertFalse(SensitivePackages.isSensitive("com.icicibankruptcy.game"))
        assertFalse(SensitivePackages.isSensitive("com.phonepedometer"))
    }

    @Test
    fun `target apps are not sensitive`() {
        assertFalse(SensitivePackages.isSensitive("com.instagram.android"))
        assertFalse(SensitivePackages.isSensitive("com.google.android.youtube"))
        assertFalse(SensitivePackages.isSensitive("dev.molasses"))
    }

    @Test
    fun `null and blank are not sensitive`() {
        assertFalse(SensitivePackages.isSensitive(null))
        assertFalse(SensitivePackages.isSensitive(""))
    }

    @Test
    fun `an empty prefix does not match everything`() {
        // A blank line in the settings field must not suppress every overlay
        // in the app, which is what a bare startsWith("") would do.
        assertFalse(SensitivePackages.isSensitive("com.instagram.android", setOf("")))
    }

    @Test
    fun `user entries extend the defaults and are normalised`() {
        val resolved = SensitivePackages.resolve(listOf("  com.Axis.Mobile  ", "", "   "))
        assertTrue(SensitivePackages.isSensitive("com.axis.mobile.upi", resolved))
        assertTrue("defaults survive", SensitivePackages.isSensitive("com.phonepe.app", resolved))
        assertEquals(SensitivePackages.DEFAULT_PREFIXES.size + 1, resolved.size)
    }

    @Test
    fun `the defaults cannot be removed by a user list`() {
        val resolved = SensitivePackages.resolve(emptyList())
        assertTrue(resolved.containsAll(SensitivePackages.DEFAULT_PREFIXES))
    }
}
