package dev.molasses.core.setup

import dev.molasses.core.setup.Onboarding.Facts
import dev.molasses.core.setup.Onboarding.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    private val fresh = Facts(serviceReady = false, accessibilityEnabled = false, usageAccess = false, targetsSeen = false, limitsSeen = false)
    private val done = Facts(serviceReady = true, accessibilityEnabled = true, usageAccess = true, targetsSeen = true, limitsSeen = true)

    @Test
    fun `a fresh install starts at accessibility and walks the steps in order`() {
        assertEquals(Step.ACCESSIBILITY, Onboarding.current(fresh))
        assertEquals(Step.USAGE_ACCESS, Onboarding.current(fresh.copy(serviceReady = true, accessibilityEnabled = true)))
        assertEquals(Step.TARGETS, Onboarding.current(fresh.copy(serviceReady = true, accessibilityEnabled = true, usageAccess = true)))
        assertEquals(Step.LIMITS, Onboarding.current(done.copy(limitsSeen = false)))
        assertEquals(null, Onboarding.current(done))
        assertTrue(Onboarding.complete(done))
    }

    @Test
    fun `accessibility is satisfied only when the service is bound and ready, not merely enabled`() {
        val enabledNotBound = fresh.copy(accessibilityEnabled = true, serviceReady = false)
        assertFalse(Onboarding.satisfied(Step.ACCESSIBILITY, enabledNotBound))
        assertTrue(Onboarding.satisfied(Step.ACCESSIBILITY, enabledNotBound.copy(serviceReady = true)))
        assertTrue(Onboarding.waitingForBind(enabledNotBound))
        assertFalse(Onboarding.waitingForBind(fresh))
    }

    @Test
    fun `a service switched off reads unsatisfied while its diagnostics still say ready`() {
        // ServiceDiagnostics outlives the unbind; the heartbeat takes up to
        // 45 s to go stale. The Settings string is what closes the gap.
        val switchedOff = done.copy(accessibilityEnabled = false)
        assertFalse(Onboarding.satisfied(Step.ACCESSIBILITY, switchedOff))
        assertEquals(Step.ACCESSIBILITY, Onboarding.current(switchedOff))
    }

    @Test
    fun `an earlier step that lapses comes back first`() {
        // Usage access revoked after everything else was done.
        assertEquals(Step.USAGE_ACCESS, Onboarding.current(done.copy(usageAccess = false)))
    }

    @Test
    fun `the unlock shows only after a return from Settings, still unsatisfied, on 13 and later`() {
        assertTrue(Onboarding.showUnlock(fresh, returnedFromSettings = true, sdkInt = 33))
        assertTrue(Onboarding.showUnlock(fresh.copy(accessibilityEnabled = true), returnedFromSettings = true, sdkInt = 35))
        assertFalse("not before trying", Onboarding.showUnlock(fresh, returnedFromSettings = false, sdkInt = 35))
        assertFalse("no restricted settings before 13", Onboarding.showUnlock(fresh, returnedFromSettings = true, sdkInt = 32))
        assertFalse("not once bound", Onboarding.showUnlock(done, returnedFromSettings = true, sdkInt = 35))
    }

    @Test
    fun `shown on a fresh install, hidden once complete, and back when CFG asks`() {
        assertTrue(Onboarding.shouldShow(completedOnce = false, requested = false))
        assertFalse(Onboarding.shouldShow(completedOnce = true, requested = false))
        assertTrue(Onboarding.shouldShow(completedOnce = true, requested = true))
    }
}
