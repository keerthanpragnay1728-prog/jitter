package dev.molasses.core.diag

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DBG's screen time line: debug builds only, both totals for the same window
 * with our package left out, and nothing written. Read as text: DBG compiles
 * nowhere here.
 */
class ScreenTimeDriftWiringTest {

    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/DebugScreen.kt").readText() }

    @Test
    fun `the line is behind the debug surface, so release never shows it`() {
        val row = screen.indexOf("item { ScreenTimeDriftRow() }")
        assertTrue(row >= 0)
        val gate = screen.lastIndexOf("if (DebugSurface.ENABLED) {", row)
        assertTrue(gate >= 0)
        assertEquals("inside that block", -1, screen.substring(gate, row).indexOf("\n        }\n"))
        assertEquals("one call site", 1, Regex("""ScreenTimeDriftRow\(\)""").findAll(screen).count() - 1)
        val release = repoFile("app/src/release/java/dev/molasses/debug/DebugSurface.kt").readText()
        assertTrue(release.contains("const val ENABLED: Boolean = false"))
    }

    @Test
    fun `both totals cover the same window and leave our package out`() {
        val read = functionBody(screen, "private fun readScreenTimeDrift(")
        assertTrue(read.contains("val own = setOf(context.packageName)"))
        assertTrue("ours is the ledger's rule", read.contains("DayUsage.replay(usm.foregroundEvents(start, now), start, now, interactive, own).totalMs"))
        assertTrue(read.contains("usm.queryAndAggregateUsageStats(start, now).mapValues { it.value.totalTimeInForeground }"))
        assertTrue(read.contains("exclude = own,"))
    }

    @Test
    fun `it writes nothing, and the aggregate is read nowhere else`() {
        for (fn in listOf("private fun readScreenTimeDrift(", "private fun ScreenTimeDriftRow(")) {
            val body = functionBody(screen, fn)
            for (write in listOf("vm.", "cycleStore", "updateData", "ledger.log", "edit {")) {
                assertFalse("$fn: $write", body.contains(write))
            }
        }
        val main = File(repoRoot(), "app/src/main/java")
        val readers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "").contains("queryAndAggregateUsageStats(") }
            .map { it.name }.toList()
        assertEquals("the ledger does not go back to it", listOf("DebugScreen.kt"), readers)
    }
}
