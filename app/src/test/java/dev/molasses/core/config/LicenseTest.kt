package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README cannot claim a licence the repository does not carry.
 *
 * LICENSE is the official GPL-3.0 text, added by the owner. This checks that
 * it is there and is GPLv3, and that the README and the F-Droid note in
 * RELEASE.md name the same licence.
 */
class LicenseTest {

    private val spdx = "GPL-3.0-or-later"

    @Test
    fun `LICENSE exists and is the GPL version 3 text`() {
        val lines = repoFile("LICENSE").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("LICENSE is empty", lines.isNotEmpty())
        assertEquals("GNU GENERAL PUBLIC LICENSE", lines[0])
        assertEquals("Version 3, 29 June 2007", lines[1])
    }

    @Test
    fun `README states the licence LICENSE carries, and who holds the copyright`() {
        val readme = repoFile("README.md").readText()
        val section = readme.substringAfter("\n## License\n", missingDelimiterValue = "")
            .substringBefore("\n## ")
        assertTrue("README has no License section", section.isNotEmpty())
        assertTrue(section.contains(spdx))
        assertTrue(section.contains("[LICENSE](LICENSE)"))
        assertTrue(section.contains("Copyright Keerthan Pragnay."))
    }

    @Test
    fun `the F-Droid recipe note names the same licence`() {
        assertTrue(repoFile("RELEASE.md").readText().contains("`License: $spdx`"))
    }
}
