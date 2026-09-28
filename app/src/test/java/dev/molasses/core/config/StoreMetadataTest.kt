package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fastlane metadata F-Droid reads, held to the limits it enforces.
 *
 * These files are `.txt`, which the dash check does not cover, so the prose
 * rule is asserted here instead.
 */
class StoreMetadataTest {

    private val dir = "fastlane/metadata/android/en-US"

    private fun text(name: String): String = repoFile("$dir/$name").readText().trimEnd('\n')

    @Test
    fun `title, short description and full description fit the store limits`() {
        assertTrue(text("title.txt").length in 1..50)
        assertTrue("short_description.txt is capped at 80", text("short_description.txt").length in 1..80)
        assertTrue(text("full_description.txt").length in 1..4000)
    }

    @Test
    fun `there is a changelog for the current versionCode, within the limit`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        val version = Regex("""\nappVersion = "(\d+)\.(\d+)\.(\d+)"""").find(catalog)
            ?: throw AssertionError("appVersion is not in the catalog")
        val (major, minor, patch) = version.destructured
        // The formula in app/build.gradle.kts, versionCodeOf.
        val code = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        val log = repoFile("$dir/changelogs/$code.txt")
        assertTrue("no changelog for versionCode $code", log.isFile)
        assertTrue(log.readText().trimEnd('\n').length in 1..500)
    }

    @Test
    fun `the metadata is plain ASCII, so no em or en dash can hide in it`() {
        val files = File(repoRoot(), dir).walkTopDown().filter { it.isFile && it.extension == "txt" }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val bad = file.readText().filter { it.code > 127 }
            assertTrue("${file.name} carries non-ASCII: $bad", bad.isEmpty())
        }
    }
}
