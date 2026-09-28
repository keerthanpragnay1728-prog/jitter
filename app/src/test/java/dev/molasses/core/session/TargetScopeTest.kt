package dev.molasses.core.session

import dev.molasses.core.session.TargetScope.Selection
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

    /** An install that has never been configured. */
    private fun fresh(vararg stored: String) = Selection(stored.toList(), chosen = false)

    /** An install whose list is the user's answer. */
    private fun chosen(vararg stored: String) = Selection(stored.toList(), chosen = true)

    @Test
    fun `a stored list is used as given`() {
        assertEquals(setOf("com.foo"), TargetScope.resolve(chosen("com.foo"), defaults))
    }

    @Test
    fun `an untouched install falls back to the defaults`() {
        // Otherwise packageNames widens to every app while the router accepts
        // none: maximum battery cost, zero behaviour, nothing in the log.
        assertEquals(defaults.toSet(), TargetScope.resolve(fresh(), defaults))
    }

    @Test
    fun `a list of blanks counts as empty`() {
        assertEquals(defaults.toSet(), TargetScope.resolve(fresh("", "  ", "\t"), defaults))
        assertTrue(TargetScope.usedFallback(fresh("", "   ")))
        assertFalse(TargetScope.usedFallback(fresh("com.foo")))
    }

    @Test
    fun `entries are trimmed`() {
        assertEquals(setOf("com.foo"), TargetScope.resolve(chosen("  com.foo  "), defaults))
    }

    // ------------------------------------------------- tracking nothing

    @Test
    fun `an empty list the user chose is honoured rather than replaced`() {
        // The state that was not expressible. Unticking the last target wrote
        // empty, empty meant "use the defaults", and the five came back.
        assertEquals(emptySet<String>(), TargetScope.resolve(chosen(), defaults))
    }

    @Test
    fun `chosen-empty and untouched-empty are different answers`() {
        // The whole point of the flag, stated as the one comparison that
        // would have failed before it existed.
        assertEquals(defaults.toSet(), TargetScope.resolve(fresh(), defaults))
        assertEquals(emptySet<String>(), TargetScope.resolve(chosen(), defaults))
    }

    @Test
    fun `trackingNothing is true only for the chosen empty case`() {
        assertTrue(TargetScope.trackingNothing(chosen()))
        assertTrue("blanks are empty here too", TargetScope.trackingNothing(chosen("", " ")))
        assertFalse("a fresh install is not a choice", TargetScope.trackingNothing(fresh()))
        assertFalse("nor is a populated list", TargetScope.trackingNothing(chosen("com.foo")))
    }

    @Test
    fun `usedFallback and trackingNothing are never both true`() {
        // They describe the two ways a list can be empty, and a screen that
        // could be told both at once would have nothing to render.
        for (selection in listOf(fresh(), chosen(), fresh("com.foo"), chosen("com.foo"))) {
            assertFalse(
                "$selection",
                TargetScope.usedFallback(selection) && TargetScope.trackingNothing(selection),
            )
        }
    }

    @Test
    fun `a populated list wins whatever the flag says`() {
        // The flag is only ever a tiebreak for empty. An install that predates
        // it reads as untouched, and its list must still be its list.
        assertEquals(setOf("com.foo"), TargetScope.resolve(fresh("com.foo"), defaults))
        assertEquals(setOf("com.foo"), TargetScope.resolve(chosen("com.foo"), defaults))
    }

    @Test
    fun `the migration is a no-op for every install that already exists`() {
        // Proto3 defaults the flag to false, so every stored file reads as
        // untouched. Both shapes it can be in must behave exactly as before.
        assertEquals(defaults.toSet(), TargetScope.resolve(fresh(), defaults))
        assertEquals(setOf("a", "b"), TargetScope.resolve(fresh("a", "b"), defaults))
    }

    // ------------------------------------------------------- packageNames

    @Test
    fun `packageNames is never empty and always contains our own package`() {
        // Without our own package no event arrives when the user leaves a
        // target app, so the session never closes.
        for (selection in listOf(fresh(), chosen(), chosen("com.foo"), fresh("", " "))) {
            val targets = TargetScope.resolve(selection, defaults)
            val names = TargetScope.packageNames(targets, own)
            assertTrue("empty for $selection", names.isNotEmpty())
            assertTrue("own package missing for $selection", own in names)
        }
    }

    @Test
    fun `tracking nothing narrows the scope to our own package alone`() {
        // The replaced behaviour, and the reason the defaults fallback had to
        // come out of packageNames. Substituting here would have handed the
        // platform every default's events for a user who asked for none.
        val targets = TargetScope.resolve(chosen(), defaults)
        val names = TargetScope.packageNames(targets, own)
        assertEquals(setOf(own), names.toSet())
        assertTrue(defaults.none { it in names })
    }

    @Test
    fun `packageNames does not duplicate our own package when it is a target`() {
        val names = TargetScope.packageNames(setOf(own, "com.foo"), own)
        assertEquals(1, names.count { it == own })
    }

    @Test
    fun `the resolved set is exactly what the router will accept`() {
        // The scope told to the platform and the scope matched by the router
        // must not drift; drift is the empty-list bug in a different shape.
        for (selection in listOf(fresh(), chosen(), chosen("com.foo"))) {
            val targets = TargetScope.resolve(selection, defaults)
            val names = TargetScope.packageNames(targets, own).toSet()
            assertEquals("$selection", targets, names - own)
        }
    }

    // ------------------------------------------------ gateable: installed only

    private val ig = "com.instagram.android"
    private val tw = "com.twitter.android"
    private val yt = "com.google.android.youtube"

    @Test
    fun `gateable is tracked and installed, once each`() {
        assertEquals(listOf(ig), TargetScope.gateable(listOf(ig, tw, ig), setOf(ig, yt)))
        assertEquals(emptyList<String>(), TargetScope.gateable(listOf(tw), setOf(ig)))
    }

    @Test
    fun `an installed list that has not loaded counts every tracked package`() {
        assertEquals(listOf(ig, tw), TargetScope.gateable(listOf(ig, tw), null))
        assertEquals(null, TargetScope.installedOrUnknown(emptyList()))
        assertEquals(setOf(ig), TargetScope.installedOrUnknown(listOf(ig)))
    }

    @Test
    fun `last only when it is the one gateable package`() {
        val all = setOf(ig, tw, yt)
        assertTrue(TargetScope.isLastGateable(ig, listOf(ig), all))
        assertFalse(TargetScope.isLastGateable(ig, listOf(ig, tw), all))
        assertFalse("not tracked at all", TargetScope.isLastGateable(ig, emptyList(), all))
        assertFalse("another app is the last", TargetScope.isLastGateable(ig, listOf(tw), all))
    }

    @Test
    fun `a tracked package that is not installed does not stop it being the last`() {
        assertTrue(TargetScope.isLastGateable(ig, listOf(ig, tw), setOf(ig, yt)))
        assertFalse(TargetScope.isLastGateable(ig, listOf(ig, tw, yt), setOf(ig, yt)))
    }

    @Test
    fun `the app itself counts even when the installed list does not name it`() {
        assertTrue(TargetScope.isLastGateable(ig, listOf(ig), setOf(yt)))
    }

    @Test
    fun `before the installed list loads, an uninstalled other target still blocks last`() {
        // Conservative: without the list there is no telling tw apart from
        // an installed app, and a false LAST TARGET is worse than a missing one.
        assertFalse(TargetScope.isLastGateable(ig, listOf(ig, tw), null))
    }
}
