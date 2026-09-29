package dev.molasses.core.setup

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A completed setup step is marked `[✓]`, an open one `[ ]`, and the tick is
 * written in exactly one place, so falling back to ASCII `[+]` is one edit.
 */
class SetupMarkTest {

    private val strings = repoFile("app/src/main/res/values/strings.xml").readText()

    private fun value(name: String): String =
        Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)
            ?: throw AssertionError("no string $name")

    @Test
    fun `the markers are a tick and an empty box`() {
        assertEquals("[✓]", value("setup_mark_done"))
        assertEquals("[ ]", value("setup_mark_open"))
    }

    @Test
    fun `the tick is written once, and the old markers are gone`() {
        assertEquals("the tick appears in one string only", 1, strings.count { it == '✓' })
        assertFalse("the [x] marker is back", strings.contains("[x]"))
        assertFalse(strings.contains("onboarding_mark_done_fmt"))
        assertFalse(strings.contains("settings_checklist_done"))
    }

    @Test
    fun `the flow and CFG's setup checklist both read the one string`() {
        val flow = repoFile("app/src/main/java/dev/molasses/ui/setup/OnboardingScreen.kt").readText()
        val cfg = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText()
        assertTrue(flow.contains("R.string.setup_mark_done"))
        assertTrue(flow.contains("R.string.setup_mark_open"))
        assertTrue(cfg.contains("stringResource(R.string.setup_mark_done)"))
    }
}
