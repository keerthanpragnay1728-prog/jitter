package dev.molasses.core.launch

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the helper sets NEW_TASK itself, on a copy, and catches both refusals`() {
        // Sliced by hand: an expression body, whose first brace is a lambda's.
        val fn = helper.substring(helper.indexOf("internal fun Activity.startFromConsole(intent: Intent, refusal: ConsoleRefusal): Boolean =")).substringBefore("\n    )\n")
        assertTrue(fn.contains("ConsoleStart.attempt("))
        assertTrue(fn.contains("start = { startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },"))
        assertTrue(fn.contains("isRefusal = { it is ActivityNotFoundException || it is SecurityException },"))
        val refused = fn.substring(fn.indexOf("onRefused = {"))
        assertTrue("logged with the intent", refused.contains("Log.w(TAG, \"nothing could open \$intent\", it)"))
        assertTrue("then said on the console", refused.indexOf("refusal.say?.invoke()") > refused.indexOf("Log.w("))
        assertEquals("one start, the one above", 1, Regex("""startActivity\(""").findAll(code(helper)).count())
        assertFalse("no catch beside ConsoleStart's", code(helper).contains("runCatching") || code(helper).contains("catch ("))
        // The refusal is a required argument, so no call can leave it out.
        assertFalse(helper.contains("refusal: ConsoleRefusal ="))
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
    fun `no caller wraps it again`() {
        val c = code(launcher)
        assertFalse(Regex("""(runCatching|try)\s*\{[^{}]*startFromConsole""").containsMatchIn(c))
        assertFalse("the old wrapper is gone", c.contains("startIfHandled"))
        assertFalse(c.contains("wellbeing intent refused"))
        // Every call hands over the console's refusal.
        assertEquals(Regex("""startFromConsole\(""").findAll(c).count(), Regex("""consoleRefusal\)""").findAll(c).count() + Regex("""\n\s+consoleRefusal,\n""").findAll(c).count())
    }

    @Test
    fun `each kind of launch goes through it`() {
        // The drawer and quick launch.
        assertTrue(functionBody(launcher, "private fun launchPackage(").contains("startFromConsole(intent, consoleRefusal)"))
        assertTrue(launcher.contains("onLaunchPackage = ::launchPackage,"))
        assertTrue(launcher.contains("launchPackage(pkg)"))
        // [phone].
        assertTrue(launcher.contains("startFromConsole(Intent(Intent.ACTION_DIAL), consoleRefusal)"))
        // Wellbeing, its own catch folded into the helper.
        assertTrue(functionBody(launcher, "private fun openWellbeing()").contains("startFromConsole(resolved, consoleRefusal)"))
        // The shortcut ladder's rungs and the intents a command opens.
        assertTrue(functionBody(launcher, "private fun launchLadder(").contains("if (startFromConsole(intent, consoleRefusal)) return true"))
        assertTrue(launcher.contains("startIntent = { startFromConsole(it, consoleRefusal) },"))
        // CFG, both routes.
        assertTrue(launcher.contains("startFromConsole(Intent(this@LauncherActivity, SettingsActivity::class.java), consoleRefusal)"))
        assertTrue(launcher.contains(".putExtra(SettingsActivity.EXTRA_SETUP_DETOUR, true),\n                                    consoleRefusal,\n"))
        assertEquals(7, Regex("""startFromConsole\(""").findAll(code(launcher)).count())
    }

    @Test
    fun `the console page says the refusal while it is composed, in the ladder's line`() {
        val page = functionBody(launcher, "fun TerminalHomeView(")
        val effect = page.indexOf("DisposableEffect(refusal) {")
        assertTrue(effect >= 0)
        val body = page.substring(effect).substringBefore("\n    }\n")
        assertTrue(body.contains("refusal.say = { sayRefused() }"))
        assertTrue("and stops when it is not", body.contains("onDispose { refusal.say = null }"))
        assertTrue(page.contains("react(BitStateMachine.Reaction.Unavailable(context.getString(R.string.launcher_open_refused)))"))
        assertTrue(launcher.contains("refusal = consoleRefusal,"))
        assertTrue(launcher.contains("refusal = refusal,"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"launcher_open_refused\">Nothing on this phone can open that.</string>"))
        assertFalse("one line, not two for the same thing", strings.contains("name=\"launcher_fav_none\""))
    }
}
