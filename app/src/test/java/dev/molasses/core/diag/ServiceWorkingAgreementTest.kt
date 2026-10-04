package dev.molasses.core.diag

import dev.molasses.core.repoFile
import dev.molasses.core.setup.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFG's service row, the first-run flow and the console's off line answer
 * "is the service working" with one function, so they cannot disagree.
 *
 * They did once: the row ticked on `acceptingEvents`, which admits STALE, and
 * the flow required HEALTHY. Pure over every state, and as text over the two
 * call sites, because both live in files nothing here compiles.
 */
class ServiceWorkingAgreementTest {

    private val cfg = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText()
    private val flow = repoFile("app/src/main/java/dev/molasses/ui/setup/SetupFlow.kt").readText()
    private val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    private val vm = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsViewModel.kt").readText()

    private val states = ServiceHealth.entries.flatMap { h -> listOf(h to true, h to false) }

    @Test
    fun `working is HEALTHY and switched on, and nothing else`() {
        for ((health, enabled) in states) {
            assertEquals(
                "$health enabled=$enabled",
                health == ServiceHealth.HEALTHY && enabled,
                ServiceHealthPolicy.working(health, enabled),
            )
        }
        assertFalse(ServiceHealthPolicy.working(ServiceHealth.STALE, true))
        assertFalse(ServiceHealthPolicy.working(ServiceHealth.CONNECTING, true))
    }

    @Test
    fun `the flow's accessibility step is satisfied exactly when CFG's row ticks`() {
        for ((health, enabled) in states) {
            val working = ServiceHealthPolicy.working(health, enabled)
            val facts = Onboarding.Facts(
                serviceWorking = working,
                accessibilityEnabled = enabled,
                usageAccess = true,
                defaultHome = true,
                targetsSeen = true,
                limitsSeen = true,
            )
            assertEquals("$health enabled=$enabled", working, Onboarding.satisfied(Onboarding.Step.ACCESSIBILITY, facts))
        }
    }

    @Test
    fun `CFG's service row ticks through the shared function`() {
        val start = cfg.indexOf("CfgRowKey.body(Section.SETUP, \"service-state\")")
        assertTrue("the service row has moved", start >= 0)
        val row = cfg.substring(start, cfg.indexOf("CfgRowKey.body(", start + 1))
        assertTrue(row.contains("satisfied = ServiceHealthPolicy.working(diag.health, permissions.accessibility)"))
        assertFalse(row.contains("acceptingEvents"))
        assertFalse(row.contains("== ServiceHealth.HEALTHY"))
    }

    @Test
    fun `the flow reads the shared function and has no rule of its own`() {
        assertTrue(flow.contains("serviceWorking = ServiceHealthPolicy.working(ServiceDiagnostics.health(), permissions.accessibility)"))
        assertFalse(flow.contains("acceptingEvents"))
        assertFalse(flow.contains("ServiceHealth.HEALTHY"))
    }

    @Test
    fun `the console's off line reads the flow's field, so it agrees with CFG's row`() {
        // CFG's health and permission come from the same two sources the
        // flow reads.
        assertTrue(vm.contains("health = ServiceDiagnostics.health(),"))
        assertTrue(vm.contains("repo.permissionState()"))
        assertTrue(launcher.contains("val serviceOff = !setup.grants.serviceWorking"))
        assertTrue(launcher.contains("serviceOff = serviceOff,"))
        assertTrue(launcher.contains("onOpenAccessibility = setup::openAccessibility,"))
        // Code only: the comments name the rule they defer to.
        val code = launcher.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
        assertFalse("no rule of its own", code.contains("ServiceHealthPolicy") || code.contains("ServiceHealth.HEALTHY"))
        val workspace = dev.molasses.core.functionBody(launcher, "fun MainLauncherWorkspace(")
        val line = workspace.indexOf("if (serviceOff) {")
        assertTrue(line >= 0 && line < workspace.indexOf("HorizontalPager("))
        val body = workspace.substring(line).substringBefore("\n        }\n")
        assertTrue(body.contains("R.string.launcher_service_off"))
        assertTrue(body.contains(".clickable { onOpenAccessibility() }"))
        assertTrue(flow.contains("fun openAccessibility() = openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"launcher_service_off\">Jitter is off: accessibility is disabled. Tap to fix.</string>"))
    }

    @Test
    fun `the console re-reads the service only while the off line shows`() {
        val effect = launcher.substring(launcher.indexOf("LaunchedEffect(serviceOff) {")).substringBefore("\n            }\n            }\n")
        assertTrue(effect.contains("while (serviceOff) {"))
        assertTrue(effect.contains("delay(SERVICE_OFF_POLL_MS)"))
        assertTrue(effect.contains("setup.refresh()"))
        assertTrue(launcher.contains("private const val SERVICE_OFF_POLL_MS = 1_000L"))
        for (banned in listOf("NotificationManager", "NotificationCompat", "AlarmManager", "WorkManager")) {
            assertFalse(banned, launcher.contains(banned))
        }
    }
}
