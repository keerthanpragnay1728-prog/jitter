package dev.molasses.core.setup

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.setup.Onboarding.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run flow shows on a fresh install from whichever screen opens
 * first, and not once setup is complete.
 *
 * The decision is pure and [OnboardingTest] covers it. What no pure test can
 * see is that both activities ask it, through one gate, with the stored flag,
 * and draw the flow instead of their own screen. Those files are ones nothing
 * here compiles, so this reads them as text, the way `FontScaleWiringTest`
 * does.
 *
 * "Fresh install" is the proto default: a bool field nobody has written reads
 * false, and a new process starts with an empty [Session].
 */
class OnboardingWiringTest {

    private val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    private val settings = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsActivity.kt").readText()
    private val gate = repoFile("app/src/main/java/dev/molasses/ui/setup/SetupFlow.kt").readText()
    private val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
    private val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()

    /** The gate's own body, sliced by hand: its parameter defaults defeat functionBody. */
    private val gateBody = gate.substring(gate.indexOf("fun SetupFlowGate("))

    @Test
    fun `a fresh install reads as not onboarded`() {
        // proto3 has no explicit defaults: an unwritten bool is false.
        assertTrue(Regex("""\n\s*bool onboarding_complete = \d+;""").containsMatchIn(proto))
        assertTrue(store.contains("store.data.map { it.onboardingComplete }"))
        assertTrue(Onboarding.shouldShow(completedOnce = false, session = Session()))
    }

    @Test
    fun `the gate decides from the stored flag and the process session, through shouldShow`() {
        assertTrue(gateBody.contains("repository.onboardingComplete"))
        assertTrue(gateBody.contains("SetupSession.state"))
        assertTrue(gateBody.contains("Onboarding.shouldShow(it, session)"))
        // The session is one per process, not per activity, so LATER on one
        // screen holds on the other.
        assertTrue(gate.contains("object SetupSession"))
    }

    @Test
    fun `the gate draws the flow instead of the content, and the content only when the flow is not showing`() {
        val showing = gateBody.indexOf("showing -> {")
        val flow = gateBody.indexOf("OnboardingScreen(", showing)
        val content = gateBody.indexOf("else -> content()")
        assertTrue(showing >= 0 && flow > showing)
        assertTrue("content must be the fallback branch, after the flow", content > flow)
        assertEquals("content is drawn in one place", 1, Regex("""\bcontent\(\)""").findAll(gateBody).count())
    }

    @Test
    fun `CFG opened first on a fresh install shows the flow`() {
        // The app drawer opens SettingsActivity; the console is only reachable
        // as the home app. So a fresh sideload meets CFG first, and CFG's
        // screen has to sit inside the gate.
        val setContent = settings.indexOf("setContent {")
        val gateAt = settings.indexOf("SetupFlowGate(", setContent)
        val screen = settings.indexOf("SettingsScreen(", setContent)
        assertTrue("CFG is not wrapped in the gate", gateAt > setContent)
        assertTrue("SettingsScreen must be inside the gate's content", screen > gateAt)
        assertTrue(settings.contains("SetupFlowController(this)"))
        assertTrue(functionBody(settings, "override fun onResume(").contains("setup.onResume()"))
        assertTrue(Onboarding.shouldShow(completedOnce = false, session = Session()))
    }

    @Test
    fun `the console opened first shows it too`() {
        val setContent = launcher.indexOf("setContent {")
        val gateAt = launcher.indexOf("SetupFlowGate(", setContent)
        val workspace = launcher.indexOf("MainLauncherWorkspace(", setContent)
        assertTrue(gateAt > setContent && workspace > gateAt)
        assertEquals(1, Regex("""\bMainLauncherWorkspace\(\s*\n\s*appList =""").findAll(launcher).count())
        assertTrue(launcher.contains("SetupFlowController(this)"))
        assertTrue(functionBody(launcher, "override fun onResume(").contains("setup.onResume()"))
    }

    @Test
    fun `completing the flow persists it, so it does not show again`() {
        val done = gateBody.substring(gateBody.indexOf("onDone = {"), gateBody.indexOf("onLater = {"))
        assertTrue(done.contains("repository.setOnboardingComplete()"))
        assertTrue(done.contains("Onboarding::done"))
        assertTrue(store.contains("setOnboardingComplete(true)"))
        assertFalse(Onboarding.shouldShow(completedOnce = true, session = Session()))
    }

    @Test
    fun `LATER and Back both put it away for the session`() {
        assertTrue(gateBody.contains("onLater = { SetupSession.update(Onboarding::later) }"))
        assertTrue(gateBody.contains("BackHandler { SetupSession.update(Onboarding::later) }"))
    }

    @Test
    fun `CFG SETUP reopens it on CFG itself`() {
        assertTrue(settings.contains("onOpenOnboarding = { SetupSession.update(Onboarding::request) }"))
        // The old route handed over to the launcher; nothing sends it now,
        // and a receiver with no sender is a guard with no caller.
        assertFalse(launcher.contains("EXTRA_ONBOARDING"))
        assertFalse(launcher.contains("override fun onNewIntent("))
    }

    @Test
    fun `the home step asks for the role as an activity result`() {
        // The role request reads the calling package, which startActivity
        // does not carry.
        assertTrue(gate.contains("registerForActivityResult"))
        assertTrue(gate.contains("createRequestRoleIntent(RoleManager.ROLE_HOME)"))
        assertTrue(gate.contains("Settings.ACTION_HOME_SETTINGS"))
        assertTrue(functionBody(gate, "private fun isDefaultHome(").contains("isRoleHeld(RoleManager.ROLE_HOME)"))
        assertTrue(functionBody(gate, "fun refresh(").contains("defaultHome = isDefaultHome()"))
    }
}
