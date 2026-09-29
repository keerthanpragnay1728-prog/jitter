package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/push-if-green.sh` is the only way to push, and it pushes only on a
 * green run. A failing suite was pushed twice before it existed, so its
 * conditions are asserted rather than trusted. Read as text: it is a shell
 * script and nothing here runs it.
 */
class PushGateTest {

    /** The code, without comment lines, which quote the commands they warn about. */
    private val script = repoFile("tools/push-if-green.sh").readLines()
        .filterNot { it.trimStart().startsWith("#") }
        .joinToString("\n")

    @Test
    fun `it pushes once, and only after every gate`() {
        assertEquals("exactly one push", 1, Regex("""\bgit push\b""").findAll(script).count())
        val push = script.indexOf("git push -u origin")
        for (gate in listOf(
            "git status --porcelain --untracked-files=all",
            "python3 tools/check-structure.py || fail",
            """[ "${'$'}last" = "check-all: PASS" ] || fail""",
        )) {
            val at = script.indexOf(gate)
            assertTrue("missing gate: $gate", at >= 0)
            assertTrue("gate after the push: $gate", at < push)
        }
    }

    @Test
    fun `the verdict is the printed last line, not the exit code of a pipe`() {
        assertTrue(script.contains("""last=${'$'}(tail -n 1 "${'$'}log")"""))
        assertTrue("check-all must not be piped", !Regex("""check-all\.sh[^\n]*\|""").containsMatchIn(script))
    }

    @Test
    fun `a failed gate exits non-zero`() {
        val fail = script.substring(script.indexOf("fail() {"), script.indexOf("}", script.indexOf("fail() {")))
        assertTrue(fail.contains("exit 1"))
    }

    @Test
    fun `CLAUDE md names it as the only way to push`() {
        val doc = repoFile("CLAUDE.md").readText()
        assertTrue(doc.contains("Push only with `tools/push-if-green.sh`"))
    }
}
