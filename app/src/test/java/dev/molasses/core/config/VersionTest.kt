package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One version source, and a versionCode that moves.
 *
 * Android refuses to install an update whose versionCode is not higher than
 * the installed one. The code is derived from appVersion in the catalog by
 * app/build.gradle.kts; this re-derives it with the same formula and holds it
 * above the last code that shipped. Raise [LAST_SHIPPED_CODE] when a build
 * goes out, and this fails until the version moves past it.
 */
class VersionTest {

    private companion object {
        /** 1.0.1 shipped as versionCode 10001. */
        const val LAST_SHIPPED_CODE = 10001
    }

    private fun appVersion(): String {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        return Regex("""^appVersion = "([^"]+)"""", RegexOption.MULTILINE).find(catalog)!!.groupValues[1]
    }

    private fun codeOf(version: String): Int {
        val (major, minor, patch) = version.split('.').map { it.toInt() }
        return major * 10_000 + minor * 100 + patch
    }

    @Test
    fun `the version is plain MAJOR dot MINOR dot PATCH`() {
        val parts = appVersion().split('.')
        assertEquals(3, parts.size)
        parts.forEach { assertTrue("$it is not a number", it.toIntOrNull() != null) }
        assertTrue(parts[1].toInt() in 0..99 && parts[2].toInt() in 0..99)
    }

    @Test
    fun `the derived code is above the last one shipped`() {
        assertTrue(codeOf(appVersion()) > LAST_SHIPPED_CODE)
    }

    @Test
    fun `the build derives both values and writes neither by hand`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(build.contains("versionCode = versionCodeOf(appVersion)"))
        assertTrue(build.contains("versionName = appVersion"))
        assertTrue(build.contains("return major * 10_000 + minor * 100 + patch"))
        assertFalse("versionCode must not be a literal", Regex("""versionCode = \d""").containsMatchIn(build))
    }
}
