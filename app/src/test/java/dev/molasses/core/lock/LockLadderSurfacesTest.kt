package dev.molasses.core.lock

import dev.molasses.core.repoFile
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One ladder, read by every tapped surface, and no saved index into it.
 * Read as text, because the surfaces are compiled by nothing here.
 */
class LockLadderSurfacesTest {

    @Test
    fun `the scrubber saves a duration, not a position on the ladder`() {
        val screen = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText()
        assertTrue(screen.contains("var stepMs by rememberSaveable { mutableLongStateOf(LockLadder.MIN_MS) }"))
        assertFalse("a saved ladder index would point at a different rung after a change",
            screen.contains("var stepIndex by rememberSaveable"))
    }

    @Test
    fun `no main source keeps its own list of lock rungs`() {
        // A second list is spotted by its shape: an hour-based listOf outside
        // LockLadder and HorizonPolicy, whose steps are a different thing.
        val main = File(repoFile("app/src/main/AndroidManifest.xml").parentFile, "java")
        val allowed = setOf("LockLadder.kt", "HorizonPolicy.kt")
        val offenders = main.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }
            .filter { Regex("""listOf\(\s*\d+\s*\*\s*(HOUR|DAY)\b""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("these keep their own rung list: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the proto stores no ladder index`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        assertFalse(Regex("""ladder|step_index|rung""", RegexOption.IGNORE_CASE).containsMatchIn(proto))
    }
}
