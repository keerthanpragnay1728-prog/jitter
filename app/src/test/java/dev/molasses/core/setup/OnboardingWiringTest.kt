package dev.molasses.core.setup

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run flow shows on a fresh install and not once setup is complete.
 *
 * The decision is pure and [OnboardingTest] covers it. What no pure test can
 * see is that the launcher asks it, with the stored flag, and draws the flow
 * instead of the console. LauncherActivity is one of the files nothing here
 * compiles, so this reads it as text, the way `FontScaleWiringTest` does.
 *
 * "Fresh install" is the proto default: a bool field nobody has written reads
 * false, so the chain is proto default, store flow, shouldShow, branch.
 */
class OnboardingWiringTest {

    private val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    private val settings = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsActivity.kt").readText()
    private val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
    private val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()

    @Test
    fun `a fresh install reads as not onboarded`() {
        // proto3 has no explicit defaults: an unwritten bool is false.
        assertTrue(Regex("""\n\s*bool onboarding_complete = \d+;""").containsMatchIn(proto))
        assertTrue(store.contains("store.data.map { it.onboardingComplete }"))
        assertTrue(Onboarding.shouldShow(completedOnce = false, requested = false))
    }

    @Test
    fun `the launcher decides from the stored flag through shouldShow`() {
        assertTrue(launcher.contains("settingsRepository.onboardingComplete"))
        assertTrue(
            "the flag must reach shouldShow, not be read beside it",
            launcher.contains("Onboarding.shouldShow(completedOnce, onboardingRequested)"),
        )
    }

    @Test
    fun `the flow is drawn instead of the console, and the console only once setup is complete`() {
        val branch = launcher.indexOf("when (showOnboarding) {")
        assertTrue("the root no longer branches on showOnboarding", branch >= 0)
        val flow = launcher.indexOf("true -> OnboardingScreen(", branch)
        val console = launcher.indexOf("false -> {", branch)
        val workspace = launcher.indexOf("MainLauncherWorkspace(", branch)
        assertTrue("the flow is not the true branch", flow > branch)
        assertTrue("the console is not the false branch", console > flow)
        assertTrue("the workspace is not inside the false branch", workspace > console)
        // Exactly one call site: a second one outside the branch would draw
        // the console under the flow, or on a fresh install.
        assertEquals(1, Regex("""\bMainLauncherWorkspace\(\s*\n\s*appList =""").findAll(launcher).count())
    }

    @Test
    fun `completing the flow persists it, so it does not show again`() {
        val done = launcher.substring(launcher.indexOf("onDone = {"), launcher.indexOf("onLater = {"))
        assertTrue(done.contains("settingsRepository.setOnboardingComplete()"))
        assertTrue(store.contains("setOnboardingComplete(true)"))
        assertFalse(Onboarding.shouldShow(completedOnce = true, requested = false))
    }

    @Test
    fun `CFG SETUP can bring it back, including into a launcher that is already running`() {
        assertTrue(settings.contains("putExtra(EXTRA_ONBOARDING, true)"))
        val onNewIntent = functionBody(launcher, "override fun onNewIntent(")
        assertTrue(
            "singleTask: the re-entry arrives here, not in onCreate",
            onNewIntent.contains("EXTRA_ONBOARDING") && onNewIntent.contains("requestOnboarding()"),
        )
        assertTrue(Onboarding.shouldShow(completedOnce = true, requested = true))
    }

    @Test
    fun `step one reads bound and ready, not merely enabled, and is re-read on resume`() {
        val refresh = functionBody(launcher, "private fun refreshSetupGrants(")
        assertTrue(refresh.contains("ServiceDiagnostics.health() == ServiceHealth.HEALTHY"))
        assertFalse("acceptingEvents admits STALE", refresh.contains("acceptingEvents"))
        val onResume = functionBody(launcher, "override fun onResume(")
        assertTrue(onResume.contains("refreshSetupGrants()"))
        assertTrue("the unlock needs the return recorded", onResume.contains("returnedFromSettings = true"))
    }
}
