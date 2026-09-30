package dev.molasses.core.settings

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back and reopening inside CFG. Read as text: the activity and its screens
 * compile nowhere here.
 */
class CfgBackWiringTest {

    private val activity by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsActivity.kt").readText() }

    @Test
    fun `DBG has a back handler that routes to CFG's root`() {
        val branch = activity.substring(activity.indexOf("if (showDebug) {")).substringBefore("} else {")
        val back = branch.indexOf("BackHandler { showDebug = false }")
        assertTrue("a back handler inside the DBG branch", back >= 0)
        assertTrue("registered alongside DBG itself", back < branch.indexOf("DebugScreen(onBack = { showDebug = false })"))
    }

    @Test
    fun `DBG's open state is never restored`() {
        assertFalse(activity.contains("showDebug by rememberSaveable"))
        assertFalse(activity.contains("rememberSaveable {"))
        assertTrue(activity.contains("private var showDebug by mutableStateOf(false)"))
        val onNew = functionBody(activity, "override fun onNewIntent(")
        assertTrue("a new visit leaves DBG", onNew.contains("showDebug = false"))
        assertFalse("not in onStop: the export's file picker stops this activity", functionBody(activity, "override fun onStop()").contains("showDebug"))
    }

    @Test
    fun `each visit keys CFG so it starts at its root`() {
        assertTrue(activity.contains("key(cfgVisit) {\n                            SettingsScreen("))
        assertTrue(functionBody(activity, "override fun onNewIntent(").contains("cfgVisit++"))
    }

    @Test
    fun `the three screens that handle Back on purpose are unchanged`() {
        val setup = repoFile("app/src/main/java/dev/molasses/ui/setup/SetupFlow.kt").readText()
        assertTrue("setup flow: Back is LATER", setup.contains("BackHandler { SetupSession.update(Onboarding::later) }"))
        val panel = repoFile("app/src/main/java/dev/molasses/ui/settings/UntrackCoolingOffPanel.kt").readText()
        assertTrue("cooling-off: Back abandons it", panel.contains("BackHandler { leave(UntrackCoolingOff.Leave.BACK) }"))
        // The lock scrubber's confirm step is cleared by any other action on
        // the screen and has no back handler of its own; this pins that no
        // one added one here by accident.
        val screen = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText()
        assertEquals(0, Regex("""BackHandler""").findAll(screen).count())
        assertTrue(screen.contains("var awaitingConfirm by rememberSaveable { mutableStateOf<Long?>(null) }"))
    }
}
