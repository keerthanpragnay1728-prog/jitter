package dev.molasses.core.setup

import dev.molasses.core.setup.Onboarding.Facts
import dev.molasses.core.setup.Onboarding.Session
import dev.molasses.core.setup.Onboarding.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    private val fresh = Facts(serviceReady = false, accessibilityEnabled = false, usageAccess = false, defaultHome = false, targetsSeen = false, limitsSeen = false)
    private val done = Facts(serviceReady = true, accessibilityEnabled = true, usageAccess = true, defaultHome = true, targetsSeen = true, limitsSeen = true)

    @Test
    fun `a fresh install starts at accessibility and walks the steps in order`() {
        assertEquals(Step.ACCESSIBILITY, Onboarding.current(fresh))
        assertEquals(Step.USAGE_ACCESS, Onboarding.current(fresh.copy(serviceReady = true, accessibilityEnabled = true)))
        assertEquals(Step.HOME, Onboarding.current(fresh.copy(serviceReady = true, accessibilityEnabled = true, usageAccess = true)))
        assertEquals(Step.TARGETS, Onboarding.current(fresh.copy(serviceReady = true, accessibilityEnabled = true, usageAccess = true, defaultHome = true)))
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
    fun `the home step is satisfied only by being the default home`() {
        assertEquals(Step.HOME, Onboarding.current(done.copy(defaultHome = false)))
        assertTrue(Onboarding.satisfied(Step.HOME, done))
    }

    @Test
    fun `the home step asks through the role where it can, and offers settings once that came back empty`() {
        assertEquals(Onboarding.HomeRoute.ROLE_REQUEST, Onboarding.homeRoute(roleAvailable = true))
        assertEquals(Onboarding.HomeRoute.HOME_SETTINGS, Onboarding.homeRoute(roleAvailable = false))
        val notHome = done.copy(defaultHome = false)
        val returned = Session(homeRequestReturned = true)
        assertFalse("not before asking", Onboarding.showHomeSettingsFallback(notHome, Session(), roleAvailable = true))
        assertTrue(Onboarding.showHomeSettingsFallback(notHome, returned, roleAvailable = true))
        assertFalse("not once home", Onboarding.showHomeSettingsFallback(done, returned, roleAvailable = true))
        assertFalse("settings is already the main route", Onboarding.showHomeSettingsFallback(notHome, returned, roleAvailable = false))
    }

    @Test
    fun `the grants that change elsewhere are polled, the ones that are seen are not`() {
        assertTrue(Onboarding.needsPoll(Step.ACCESSIBILITY))
        assertTrue(Onboarding.needsPoll(Step.USAGE_ACCESS))
        assertTrue(Onboarding.needsPoll(Step.HOME))
        assertFalse(Onboarding.needsPoll(Step.TARGETS))
        assertFalse(Onboarding.needsPoll(Step.LIMITS))
        assertFalse(Onboarding.needsPoll(null))
    }

    @Test
    fun `shown on a fresh install, hidden once complete, and back when CFG asks`() {
        assertTrue(Onboarding.shouldShow(completedOnce = false, session = Session()))
        assertFalse(Onboarding.shouldShow(completedOnce = true, session = Session()))
        assertTrue(Onboarding.shouldShow(completedOnce = true, session = Onboarding.request(Session())))
    }

    @Test
    fun `LATER holds for the session and a fresh session brings it back`() {
        val later = Onboarding.later(Session())
        assertFalse(Onboarding.shouldShow(completedOnce = false, session = later))
        // A cold start is a new process, so a new Session.
        assertTrue(Onboarding.shouldShow(completedOnce = false, session = Session()))
    }

    @Test
    fun `DONE closes it before the stored flag lands`() {
        val closed = Onboarding.done(Session(targetsSeen = true))
        assertFalse(Onboarding.shouldShow(completedOnce = false, session = closed))
        assertTrue(closed.limitsSeen)
    }

    @Test
    fun `a request reopens a closed flow and walks targets and limits again`() {
        val again = Onboarding.request(Onboarding.done(Session(targetsSeen = true)))
        assertTrue(Onboarding.shouldShow(completedOnce = true, session = again))
        assertFalse(again.targetsSeen)
        assertFalse(again.limitsSeen)
        // LATER on a requested flow puts it away again.
        assertFalse(Onboarding.shouldShow(completedOnce = true, session = Onboarding.later(again)))
    }
}
