package dev.molasses.core.diag

import dev.molasses.core.repoFile
import dev.molasses.core.setup.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFG's service row and the first-run flow answer "is the service working"
 * with one function, so they cannot disagree.
 *
 * They did once: the row ticked on `acceptingEvents`, which admits STALE, and
 * the flow required HEALTHY. Pure over every state, and as text over the two
 * call sites, because both live in files nothing here compiles.
 */
class ServiceWorkingAgreementTest {

    private val cfg = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText()
    private val flow = repoFile("app/src/main/java/dev/molasses/ui/setup/SetupFlow.kt").readText()

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
}
