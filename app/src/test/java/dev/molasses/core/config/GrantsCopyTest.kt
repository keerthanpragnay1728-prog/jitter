package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Jitter asks for is described in the same words everywhere a user or a
 * store reader meets it: two permissions (accessibility, usage access) and
 * the home app. Not "three special-access grants", which the home app is
 * not, and never notification access, which the app does not use.
 */
class GrantsCopyTest {

    private val phrase = "two permissions (accessibility, usage access) and your home app"

    private val places = listOf(
        "README.md",
        "fastlane/metadata/android/en-US/full_description.txt",
        "fastlane/metadata/android/en-US/changelogs/200.txt",
        "app/src/main/res/values/strings.xml",
    )

    /** Whitespace runs collapsed, so a phrase wrapped across Markdown lines still matches. */
    private fun read(place: String): String = repoFile(place).readText().replace(Regex("""\s+"""), " ")

    @Test
    fun `every description of the grants uses the one phrase`() {
        for (place in places) {
            assertTrue("$place does not say: $phrase", read(place).contains(phrase, ignoreCase = true))
        }
    }

    @Test
    fun `nothing calls them special-access grants or mentions notification access`() {
        for (place in places) {
            val text = read(place)
            assertFalse("$place mentions notification access", text.contains("notification access", ignoreCase = true))
            assertFalse("$place says special access", Regex("special[- ]access grant", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }

    @Test
    fun `the flow states it on every step, the limits screen included`() {
        val screen = repoFile("app/src/main/java/dev/molasses/ui/setup/OnboardingScreen.kt").readText()
        val needs = screen.indexOf("R.string.onboarding_needs")
        val steps = screen.indexOf("when (current) {")
        assertTrue("the line is missing or drawn inside one step", needs in 0 until steps)
    }
}
