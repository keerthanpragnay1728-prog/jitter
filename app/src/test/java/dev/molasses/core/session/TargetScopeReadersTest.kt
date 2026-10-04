package dev.molasses.core.session

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every read of the stored target list builds a `TargetScope.Selection`
 * with its flag, so it can only reach the tracked set through
 * `TargetScope.resolve`. Read as text: the readers compile nowhere here.
 *
 * See CLAUDE.md, "The stored target list is not the tracked set". A reader
 * that takes the raw list is wrong on a fresh install, where it is empty
 * while five defaults are tracked. The reconciler was the fourth such
 * reader: after a process death on a fresh install it credited nothing.
 */
class TargetScopeReadersTest {

    private fun code(text: String) = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    private val readers by lazy {
        File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to code(it.readText()) }
            .filterValues { it.contains("targetPackagesList") }
    }

    @Test
    fun `every read of the stored list is the stored half of a Selection, with its flag`() {
        assertTrue("found no readers at all", readers.isNotEmpty())
        val read = Regex("""(\S+\s*)targetPackagesList""")
        for ((name, text) in readers) {
            for (m in read.findAll(text)) {
                val line = text.substring(text.lastIndexOf('\n', m.range.first) + 1, text.indexOf('\n', m.range.last))
                assertTrue("$name reads the raw list: ${line.trim()}", Regex("""^\s*(TargetScope\.Selection\()?stored = \w+\.targetPackagesList(\.toList\(\))?,.*$""").matches(line))
            }
            assertTrue("$name builds a Selection", text.contains("TargetScope.Selection("))
            assertTrue("$name passes the flag with the list", Regex("""chosen = \w+\.targetsChosen""").containsMatchIn(text))
        }
    }

    @Test
    fun `the readers are the known ones, the reconciler among them`() {
        assertEquals(
            listOf("CycleStateStore.kt", "ForegroundReconciler.kt", "MolassesAccessibilityService.kt", "SettingsRepository.kt"),
            readers.keys.sorted(),
        )
        // The repository hands the Selection on whole; every other reader
        // resolves it where it reads it.
        for (name in readers.keys - "SettingsRepository.kt") {
            assertTrue("$name resolves", readers.getValue(name).contains("TargetScope.resolve("))
        }
    }

    @Test
    fun `the reconciler replays the resolved set`() {
        val reconcile = functionBody(
            repoFile("app/src/main/java/dev/molasses/monitor/ForegroundReconciler.kt").readText(),
            "suspend fun reconcile()",
        )
        val resolved = reconcile.indexOf("val targets = TargetScope.resolve(")
        assertTrue(resolved >= 0)
        assertTrue(reconcile.contains("TargetScope.Selection(stored = state.targetPackagesList, chosen = state.targetsChosen),"))
        assertTrue(reconcile.indexOf("DEFAULT_TARGETS", resolved) > resolved)
        assertTrue(reconcile.indexOf("targets = targets,") > resolved)
    }
}
