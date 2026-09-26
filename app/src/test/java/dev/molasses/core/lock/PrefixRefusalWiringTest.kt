package dev.molasses.core.lock

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused prefix is said on screen, not only dropped.
 *
 * The settings layer is compiled by nothing here, so the wiring is read as
 * text: the ViewModel computes the refusals with [PrefixLock], and the editor
 * renders each one with the same LOCKED remainder the target row uses.
 */
class PrefixRefusalWiringTest {

    private val vm by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsViewModel.kt").readText() }
    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText() }

    @Test
    fun `the save computes refusals with the store's own function`() {
        val body = functionBody(vm, "fun setSensitivePrefixes(")
        val compute = body.indexOf("PrefixLock.admitted(")
        val write = body.indexOf("repo.setSensitivePrefixes(")
        assertTrue("the ViewModel must compute refusals with PrefixLock", compute >= 0)
        assertTrue("and still hand the write to the store, which decides", write > compute)
        assertTrue(body.contains("_prefixRefusals.value ="))
    }

    @Test
    fun `the editor renders each refusal with the lock's remainder`() {
        val body = functionBody(screen, "private fun SensitivePrefixEditor(")
        assertTrue(body.contains("refusals.forEach"))
        assertTrue(body.contains("R.string.settings_safety_prefix_refused_row"))
        assertTrue("the remainder must use the target row's LOCKED format", body.contains("R.string.settings_lock_remaining_fmt"))
        assertTrue(screen.contains("refusals = prefixRefusals,"))
    }
}
