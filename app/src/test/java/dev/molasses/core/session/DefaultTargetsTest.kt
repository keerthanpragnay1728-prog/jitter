package dev.molasses.core.session

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTargetsTest {

    private val snapchat = "com.snapchat.android"
    private fun untouched(vararg pkgs: String) = TargetScope.Selection(pkgs.toList(), chosen = false)
    private fun chosen(vararg pkgs: String) = TargetScope.Selection(pkgs.toList(), chosen = true)

    @Test
    fun `the defaults are Instagram, X, YouTube and Snapchat`() {
        assertEquals(
            listOf("com.instagram.android", "com.twitter.android", "com.google.android.youtube", snapchat),
            DefaultTargets.CURRENT,
        )
        assertTrue(snapchat !in DefaultTargets.BEFORE_SNAPCHAT)
    }

    @Test
    fun `a fresh install resolves to the new defaults`() {
        assertEquals(DefaultTargets.CURRENT.toSet(), TargetScope.resolve(untouched(), DefaultTargets.CURRENT))
    }

    @Test
    fun `an install still on the old defaults, never chosen, gains Snapchat`() {
        assertEquals(DefaultTargets.CURRENT, DefaultTargets.migrate(untouched(*DefaultTargets.BEFORE_SNAPCHAT.toTypedArray())))
        // In any order, and with stray whitespace.
        assertEquals(
            DefaultTargets.CURRENT,
            DefaultTargets.migrate(untouched(" com.google.android.youtube", "com.instagram.android", "com.twitter.android ")),
        )
    }

    @Test
    fun `a chosen list is the user's and does not move, even if it equals the old defaults`() {
        assertNull(DefaultTargets.migrate(chosen(*DefaultTargets.BEFORE_SNAPCHAT.toTypedArray())))
        assertNull(DefaultTargets.migrate(chosen()))
        assertNull(DefaultTargets.migrate(chosen("com.instagram.android")))
    }

    @Test
    fun `an unchosen list that differs from the old defaults predates the flag and does not move`() {
        assertNull(DefaultTargets.migrate(untouched("com.instagram.android")))
        assertNull(DefaultTargets.migrate(untouched("com.instagram.android", "com.twitter.android", "com.google.android.youtube", "com.reddit.frontpage")))
        assertNull(DefaultTargets.migrate(untouched("com.instagram.android", "com.instagram.android", "com.twitter.android")))
    }

    @Test
    fun `an empty unchosen list needs no migration, it already resolves to the new defaults`() {
        assertNull(DefaultTargets.migrate(untouched()))
    }

    @Test
    fun `an install already on the new defaults is left alone`() {
        assertNull(DefaultTargets.migrate(untouched(*DefaultTargets.CURRENT.toTypedArray())))
    }

    @Test
    fun `the data layer's defaults are these, and the declared profile lists exactly them`() {
        val serializer = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateSerializer.kt").readText()
        assertTrue(serializer.contains("val DEFAULT_TARGETS: List<String> = DefaultTargets.CURRENT"))
        val xml = repoFile("app/src/main/res/xml/accessibility_service_config.xml").readText()
        val declared = Regex("""android:packageNames="([^"]+)"""").find(xml)!!.groupValues[1].split(",").map { it.trim() }
        assertEquals(listOf("org.jitteros.app") + DefaultTargets.CURRENT, declared)
    }

    @Test
    fun `the migration is step two, runs only for older files, and leaves step one alone`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        assertTrue(store.contains("const val SCHEMA_VERSION = 2"))
        val migrate = functionBody(store, "suspend fun migrate()")
        val one = migrate.indexOf("if (old.schemaVersion < 1) {")
        val two = migrate.indexOf("if (old.schemaVersion < 2) {")
        assertTrue(one in 0 until two)
        assertTrue("the policy only in step one", migrate.indexOf("setResetPolicy(") in one until two)
        assertTrue(migrate.indexOf("DefaultTargets.migrate(selection)?.let { b.clearTargetPackages().addAllTargetPackages(it) }") > two)
        assertTrue(migrate.contains("chosen = old.targetsChosen,"))
    }
}
