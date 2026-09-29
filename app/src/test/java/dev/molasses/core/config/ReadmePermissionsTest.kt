package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README's permission list is the manifest's, exactly.
 *
 * It listed READ_PHONE_STATE and QUERY_ALL_PACKAGES long after both had left
 * the manifest, next to a Distribution section saying neither was requested.
 * A reader had two answers and no way to tell which was current. Now the list
 * is compared to the manifest in both directions.
 */
class ReadmePermissionsTest {

    private val marker = "**Permissions in the manifest**"

    private fun manifestPermissions(): Set<String> {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        return Regex("""<uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
    }

    /** The permission named at the start of each bullet, up to the next blank-line paragraph that is not a bullet. */
    private fun readmePermissions(): List<String> {
        val readme = repoFile("README.md").readText()
        val start = readme.indexOf(marker)
        assertTrue("README has no `$marker` section", start >= 0)
        val section = readme.substring(start + marker.length)
            .lineSequence()
            .dropWhile { !it.startsWith("- ") }
            .takeWhile { it.startsWith("- ") || it.startsWith("  ") }
            .toList()
        return section.mapNotNull { Regex("""^- `([A-Z_]+)`""").find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `the README lists exactly the manifest's permissions`() {
        val listed = readmePermissions()
        assertEquals("a permission is listed twice", listed.size, listed.toSet().size)
        assertEquals(manifestPermissions(), listed.toSet())
    }

    @Test
    fun `the section does not list permissions the app does not request`() {
        val listed = readmePermissions()
        for (gone in listOf(
            "READ_PHONE_STATE", "QUERY_ALL_PACKAGES", "SYSTEM_ALERT_WINDOW", "INTERNET",
            "POST_NOTIFICATIONS", "HIGH_SAMPLING_RATE_SENSORS",
        )) {
            assertFalse("$gone is listed but not requested", gone in listed)
        }
    }

    @Test
    fun `the old tier section is only under DESIGN HISTORY`() {
        val readme = repoFile("README.md").readText()
        assertFalse(readme.contains("### 1. What happens after 20 minutes?"))
        val history = readme.indexOf("## DESIGN HISTORY")
        assertTrue(history >= 0)
        assertTrue(readme.indexOf("What happened after 20 minutes") > history)
        assertTrue(readme.substring(history).contains("Nothing under this heading describes the app as it is."))
    }
}
