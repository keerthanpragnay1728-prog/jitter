package dev.molasses.core.launch

import dev.molasses.core.functionBody
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every start from the console goes through `startFromConsole`, which sets
 * FLAG_ACTIVITY_NEW_TASK itself. Read as text: the console compiles nowhere
 * here.
 */
class ConsoleLaunchWiringTest {

    private val dir by lazy { File(repoRoot(), "app/src/main/java/dev/molasses/ui/launcher").also { assertTrue(it.isDirectory) } }
    private val helper by lazy { File(dir, "ConsoleLaunch.kt").readText() }
    private val launcher by lazy { File(dir, "LauncherActivity.kt").readText() }

    /** Code only: a comment that recalls a bare startActivity is not a call. */
    private fun code(text: String) = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `the helper sets NEW_TASK itself, on a copy`() {
        val fn = functionBody(helper, "internal fun Activity.startFromConsole(")
        assertTrue(fn.contains("startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))"))
        assertEquals("one start, the one above", 1, Regex("""startActivity\(""").findAll(code(helper)).count())
    }

    @Test
    fun `no start in the console bypasses the helper`() {
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ConsoleLaunch.kt" }.toList()
        assertTrue("the console's files were found", files.any { it.name == "LauncherActivity.kt" })
        val bypass = listOf(
            "startActivity(", "startActivities(", "startActivityForResult(",
            "startActivityIfNeeded(", "startNextMatchingActivity(", "registerForActivityResult(",
        )
        val offenders = files.flatMap { f ->
            val t = code(f.readText())
            bypass.filter { t.contains(it) }.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `each kind of launch goes through it`() {
        // The drawer and quick launch.
        assertTrue(functionBody(launcher, "private fun launchPackage(").contains("startFromConsole(intent)"))
        assertTrue(launcher.contains("onLaunchPackage = ::launchPackage,"))
        assertTrue(launcher.contains("launchPackage(pkg)"))
        // [phone].
        assertTrue(launcher.contains("startFromConsole(Intent(Intent.ACTION_DIAL))"))
        // Wellbeing.
        assertTrue(functionBody(launcher, "private fun openWellbeing()").contains("runCatching { startFromConsole(resolved) }"))
        // The shortcut ladder's rungs and the intents a command opens.
        assertTrue(functionBody(launcher, "private fun startIfHandled(").contains("startFromConsole(intent)"))
        assertTrue(functionBody(launcher, "private fun launchLadder(").contains("startIfHandled(intent)"))
        assertTrue(launcher.contains("startIntent = ::startIfHandled,"))
        // CFG, both routes.
        assertTrue(launcher.contains("startFromConsole(Intent(this@LauncherActivity, SettingsActivity::class.java))"))
        assertTrue(launcher.contains("startFromConsole(\n                                    Intent(this@LauncherActivity, SettingsActivity::class.java)\n                                        .putExtra(SettingsActivity.EXTRA_OPEN_SECTION"))
        assertEquals(6, Regex("""startFromConsole\(""").findAll(code(launcher)).count())
    }
}
