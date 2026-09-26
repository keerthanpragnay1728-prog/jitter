package dev.molasses.core.command

import dev.molasses.core.functionBody
import dev.molasses.core.lock.BedtimeWindow
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LockConfirmationTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a block names its app and its duration`() {
        val cmd = Command.Block("ig", 3 * day)
        assertEquals(
            LockConfirmation.Panel(cmd, LockConfirmation.Target.App("Instagram"), 3 * day),
            LockConfirmation.panelFor(cmd, "Instagram"),
        )
    }

    @Test
    fun `a block whose app did not resolve has no panel`() {
        assertNull(LockConfirmation.panelFor(Command.Block("zz", 3 * day), null))
    }

    @Test
    fun `focus names every tracked app`() {
        val cmd = Command.Focus(3 * day)
        assertEquals(LockConfirmation.Target.AllTracked, LockConfirmation.panelFor(cmd, null)!!.target)
    }

    @Test
    fun `nothing else gets a panel`() {
        assertNull(LockConfirmation.panelFor(Command.Bedtime, null))
        assertNull(LockConfirmation.panelFor(Command.Status, null))
    }

    @Test
    fun `bedtime can never exceed the threshold, so it never needs the panel`() {
        for (minute in 0 until 24 * 60) {
            assertTrue("minute $minute", BedtimeWindow.durationMs(minute) <= CommandRegistry.CONFIRM_ABOVE_MS)
        }
    }

    // ---------------------------------------------------------- wiring, as text

    private val launcher by lazy {
        repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    }

    @Test
    fun `back aborts the panel before anything else`() {
        val back = launcher.substring(launcher.indexOf("BackHandler(enabled = true) {"))
        val abort = back.indexOf("lockConfirm != null -> lockConfirm = null")
        val drawer = back.indexOf("showDrawer -> showDrawer = false")
        assertTrue("back must abort the panel first", abort in 0 until drawer)
    }

    @Test
    fun `the commit dispatches confirmed and records nothing`() {
        val body = functionBody(launcher, "fun handleOutcome(")
        val branch = body.substring(body.indexOf("is DispatchResult.NeedsConfirmation ->"))
        assertTrue(branch.contains("dispatch.dispatch(panel.command, confirmed = true)"))
        assertFalse("the confirming action must never reach history", branch.contains("onRecordCommand"))
    }

    @Test
    fun `the panel has no cancel button`() {
        val panel = repoFile("app/src/main/java/dev/molasses/ui/launcher/LockConfirmPanel.kt").readText()
        assertFalse("no cancel copy", Regex("""R\.string\.\w*cancel""", RegexOption.IGNORE_CASE).containsMatchIn(panel))
        assertEquals("the commit is the only button", 1, Regex("""\.clickable\(onClick = """).findAll(panel).count())
    }

    @Test
    fun `the old echo confirmation is gone`() {
        val main = File(repoFile("app/src/main/AndroidManifest.xml").parentFile, "java")
        val offenders = main.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("ConfirmPrompt") }.map { it.name }.toList()
        assertTrue("still referenced in $offenders", offenders.isEmpty())
    }
}
