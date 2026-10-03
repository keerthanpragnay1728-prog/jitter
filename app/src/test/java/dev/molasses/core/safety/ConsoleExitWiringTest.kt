package dev.molasses.core.safety

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ ARCHITECT'S SPACE ] and back, on every gate and the lock, land on
 * Jitter's console through one function. Read as text: the overlays and the
 * service compile nowhere here.
 */
class ConsoleExitWiringTest {

    private fun overlay(name: String) = repoFile("app/src/main/java/dev/molasses/overlay/$name.kt").readText()
    private val service by lazy { repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText() }

    private fun count(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()

    @Test
    fun `the one function starts the console by name, and falls back to home only on a throw`() {
        val fn = functionBody(service, "private fun openConsole()")
        assertTrue(fn.contains("ConsoleExit.open("))
        assertTrue(fn.contains("Intent(this, LauncherActivity::class.java)"))
        assertTrue(fn.contains(".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)"))
        assertTrue("the fallback is the home action", fn.contains("fallback = ::goHomeQuietly,"))
        assertTrue("and the refusal is logged with its exception", fn.contains("onRefused = { Log.w(HOME_FIRST_TAG, \"console start refused; sending GLOBAL_ACTION_HOME instead\", it) },"))
        assertTrue(fn.contains("Log.i(HOME_FIRST_TAG, \"overlay exit: route=\$route\")"))
        assertFalse("no catch of its own beside ConsoleExit's", fn.contains("runCatching") || fn.contains("catch ("))
        assertEquals("defined once", 1, count(service, "private fun openConsole()"))
    }

    @Test
    fun `all three managers are handed the same function, and nothing else is`() {
        assertEquals(3, count(service, "openConsole = ::openConsole,"))
        assertEquals("no lambda of its own in any manager's wiring", 0, count(service, "openConsole = {"))
        for (name in listOf("LeaseGateOverlayManager", "GateOverlayManager", "LockOverlayManager")) {
            assertTrue(name, overlay(name).contains("private val openConsole: () -> Unit,"))
        }
    }

    @Test
    fun `the lease gates leave through decline, which starts the console before the window comes down`() {
        val manager = overlay("LeaseGateOverlayManager")
        val show = functionBody(manager, "fun show(")
        val back = show.substring(show.indexOf("onBackPressed = {")).substringBefore("},")
        assertTrue("back is the exit", back.contains("else -> exit()"))
        assertTrue("[ ARCHITECT'S SPACE ] is the exit", show.contains("onExit = { exit() },"))
        assertTrue(functionBody(manager, "private fun exit()").contains("decline(\"exit\")"))
        val decline = functionBody(manager, "private fun decline(")
        val start = decline.indexOf("openConsole()")
        assertTrue(start >= 0)
        assertTrue("held, then dismissed", start < decline.indexOf("delay(LockEnforcement.HOME_SETTLE_MS)"))
        assertTrue(start < decline.indexOf("dismissInternal()"))
        assertFalse("the exit no longer sends the home action", decline.contains("goHome()"))
        assertEquals("one console start in this class", 1, count(manager, "openConsole()"))
        assertEquals("home only at the first draw", 1, count(manager, "goHome()"))
        assertTrue(functionBody(manager, "private fun sendHome(").contains("goHome()"))
    }

    @Test
    fun `the walking gate's exit and back start the console before the window comes down`() {
        val manager = overlay("GateOverlayManager")
        val show = functionBody(manager, "fun show(")
        assertTrue(show.contains("onBackPressed = { exit() },"))
        assertTrue(show.contains("onExit = { exit() },"))
        val exit = functionBody(manager, "private fun exit()")
        val start = exit.indexOf("openConsole()")
        assertTrue(start >= 0 && start < exit.indexOf("dismissInternal()"))
        assertFalse(exit.contains("goHome()"))
        assertEquals(1, count(manager, "openConsole()"))
        assertEquals("home only at the first draw", 1, count(manager, "goHome()"))
    }

    @Test
    fun `the lock's exit and back start the console, then hold, then dismiss`() {
        val manager = overlay("LockOverlayManager")
        val flash = functionBody(manager, "fun flash(")
        assertTrue("back is the exit", flash.contains("onBackPressed = { exit(\"back\") },"))
        assertTrue("[ ARCHITECT'S SPACE ] is the exit", flash.contains("onExit = { exit(\"user\") },"))
        val exit = functionBody(manager, "private fun exit(")
        val start = exit.indexOf("openConsole()")
        assertTrue(start >= 0)
        assertTrue(start < exit.indexOf("delay(LockEnforcement.HOME_SETTLE_MS)"))
        assertTrue(start < exit.indexOf("dismiss(reason)"))
        assertFalse(exit.contains("goHome()"))
        assertEquals(1, count(manager, "openConsole()"))
        // The first draw, and the failed attach that must still bounce the
        // user out of a locked app. Neither is an exit.
        assertEquals(2, count(manager, "goHome()"))
    }
}
