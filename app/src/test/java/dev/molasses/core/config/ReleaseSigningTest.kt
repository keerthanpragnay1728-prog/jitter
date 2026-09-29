package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release signing reads its key from outside the repository, fails closed,
 * and no key file is ever tracked.
 */
class ReleaseSigningTest {

    private val build by lazy { repoFile("app/build.gradle.kts").readText() }

    @Test
    fun `the four properties are read from gradle properties or the environment`() {
        for (name in listOf("JITTER_STORE_FILE", "JITTER_STORE_PASSWORD", "JITTER_KEY_ALIAS", "JITTER_KEY_PASSWORD")) {
            assertTrue(name, build.contains("\"$name\""))
        }
        assertTrue(build.contains("providers.gradleProperty(name).orElse(providers.environmentVariable(name))"))
    }

    @Test
    fun `release never falls back to the debug key or to unsigned`() {
        assertTrue(build.contains("signingConfig = signingConfigs.findByName(\"release\")"))
        assertFalse(build.contains("getByName(\"debug\")"))
        assertFalse(build.contains("signingConfigs.debug"))
        val check = build.substring(build.indexOf("tasks.matching { it.name.matches(Regex(\"(package|sign)Release(Bundle)?\")) }"))
        assertTrue(check.contains("if (missingReleaseSigning.isNotEmpty())"))
        assertTrue("the message names what is missing", check.contains("Missing: \${missingReleaseSigning.joinToString(\", \")}"))
        assertTrue(check.contains("throw GradleException("))
    }

    @Test
    fun `no product flavors, one channel`() {
        assertFalse(build.contains("productFlavors"))
        assertFalse(build.contains("flavorDimensions"))
    }

    @Test
    fun `no keystore is tracked, and the ignore file keeps it that way`() {
        val tracked = ProcessBuilder("git", "ls-files")
            .directory(repoRoot())
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readLines()
        val keys = tracked.filter { f -> listOf(".jks", ".keystore", ".p12").any { f.lowercase().endsWith(it) } }
        assertEquals(emptyList<String>(), keys)
        val ignore = repoFile(".gitignore").readText().lines()
        for (p in listOf("*.jks", "*.keystore")) assertTrue(p, p in ignore)
        assertFalse("no exception that lets a key back in", ignore.any { it.startsWith("!") && (it.endsWith(".keystore") || it.endsWith(".jks")) })
    }

    @Test
    fun `RELEASE md records the one signing certificate fingerprint, unchanged`() {
        // The key never changes, so neither does this. A different value here
        // is either a typo or a new key, and a new key strands every install.
        val release = repoFile("RELEASE.md").readText()
        val fingerprint = "876cbba2c4cdab9f0020ab8fdb6bc66dc28c991158a776b28911f26ff40152ec"
        assertTrue(release.contains("Release signing certificate fingerprint (SHA-256)"))
        assertTrue(release.contains(fingerprint))
        val recorded = Regex("""\b[0-9a-f]{64}\b""").findAll(release).map { it.value }.toSet()
        assertEquals("exactly one 64-hex digest is recorded", setOf(fingerprint), recorded)
    }
}
