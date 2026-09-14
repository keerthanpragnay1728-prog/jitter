package dev.molasses.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetScopeTest {

    private val defaults = listOf(
        "com.instagram.android",
        "com.twitter.android",
        "com.google.android.youtube",
    )
    private val own = "dev.molasses"

    @Test
    fun `a stored list is used as given`() {
        assertEquals(setOf("com.foo"), TargetScope.resolve(listOf("com.foo"), defaults))
    }

    @Test
    fun `an empty stored list falls back to the defaults`() {
        // Otherwise packageNames widens to every app while the router accepts
        // none: maximum battery cost, zero behaviour, nothing in the log.
        assertEquals(defaults.toSet(), TargetScope.resolve(emptyList(), defaults))
    }

    @Test
    fun `a list of blanks counts as empty`() {
        assertEquals(defaults.toSet(), TargetScope.resolve(listOf("", "  ", "\t"), defaults))
        assertTrue(TargetScope.usedFallback(listOf("", "   ")))
        assertFalse(TargetScope.usedFallback(listOf("com.foo")))
    }

    @Test
    fun `entries are trimmed`() {
        assertEquals(setOf("com.foo"), TargetScope.resolve(listOf("  com.foo  "), defaults))
    }

    @Test
    fun `packageNames is never empty and always contains our own package`() {
        // Without our own package no event arrives when the user leaves a
        // target app, so the session never closes.
        for (stored in listOf(emptyList(), listOf("com.foo"), listOf("", " "))) {
            val targets = TargetScope.resolve(stored, defaults)
            val names = TargetScope.packageNames(targets, own, defaults)
            assertTrue("empty for $stored", names.isNotEmpty())
            assertTrue("own package missing for $stored", own in names)
        }
    }

    @Test
    fun `packageNames falls back even if handed an empty target set directly`() {
        // Belt and braces: the caller could resolve elsewhere and pass through.
        val names = TargetScope.packageNames(emptySet(), own, defaults)
        assertTrue(names.isNotEmpty())
        assertTrue(defaults.all { it in names })
        assertTrue(own in names)
    }

    @Test
    fun `packageNames does not duplicate our own package when it is a target`() {
        val names = TargetScope.packageNames(setOf(own, "com.foo"), own, defaults)
        assertEquals(1, names.count { it == own })
    }

    @Test
    fun `the resolved set is exactly what the router will accept`() {
        // The scope told to the platform and the scope matched by the router
        // must not drift; drift is the empty-list bug in a different shape.
        val targets = TargetScope.resolve(emptyList(), defaults)
        val names = TargetScope.packageNames(targets, own, defaults).toSet()
        assertEquals(targets, names - own)
    }
}
