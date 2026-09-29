package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every adb component we print, `<pkg>/<class>`, names the applicationId and
 * a fully qualified class.
 *
 * The applicationId (`org.jitteros.app`) and the code namespace
 * (`dev.molasses`) differ. The short form `pkg/.monitor.X` expands against the
 * package, so under the new id it names `org.jitteros.app.monitor.X`, which
 * does not exist, and `dumpsys` answers with nothing. A stale package in front
 * of the slash names an app that is no longer installed. Both read as a
 * plausible command, which is why they are asserted.
 */
class AdbComponentTest {

    private val places = listOf(
        "app/src/main/res/values/strings.xml",
        "README.md",
        "RELEASE.md",
    )

    private fun applicationId(): String =
        Regex("""applicationId\s*=\s*"([^"]+)"""").find(repoFile("app/build.gradle.kts").readText())
            ?.groupValues?.get(1)
            ?: error("no applicationId in app/build.gradle.kts")

    /**
     * Each `adb` command, with a trailing backslash continuation joined, and
     * every `pkg/class` token in it. A package is dotted lowercase; the class
     * follows the slash.
     */
    private fun components(text: String): List<String> {
        val joined = text.replace(Regex("""\\\s*\n\s*"""), " ")
        return Regex("""\badb\b[^\n<]*""").findAll(joined).flatMap { cmd ->
            Regex("""\b([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)/([.\w$]+)""").findAll(cmd.value).map { it.value }
        }.toList()
    }

    @Test
    fun `every adb component uses the applicationId and a fully qualified class`() {
        val id = applicationId()
        val found = places.flatMap { place -> components(repoFile(place).readText()).map { place to it } }
        assertTrue("found no adb components to check; the parser has drifted from the docs", found.isNotEmpty())
        for ((place, component) in found) {
            val (pkg, cls) = component.split("/", limit = 2)
            assertEquals("$place: $component names the wrong package", id, pkg)
            assertTrue("$place: $component uses the short form; write the class in full", !cls.startsWith("."))
            assertTrue("$place: $component class is not fully qualified", cls.contains('.'))
        }
    }

    @Test
    fun `the parser sees the forms it must reject`() {
        // So a pass above is not a parser that never matches.
        assertEquals(listOf("dev.molasses/.monitor.X"), components("adb shell dumpsys activity service dev.molasses/.monitor.X"))
        assertEquals(
            listOf("org.jitteros.app/dev.molasses.monitor.X"),
            components("adb shell dumpsys activity service \\\n    org.jitteros.app/dev.molasses.monitor.X"),
        )
    }
}
