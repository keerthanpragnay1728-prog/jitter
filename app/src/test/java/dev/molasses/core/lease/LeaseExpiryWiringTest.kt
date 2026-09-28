package dev.molasses.core.lease

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service arms, cancels and fires the lease-expiry check where
 * [LeaseExpiryCheck] says. Read as text: the service compiles nowhere here.
 */
class LeaseExpiryWiringTest {

    private val service by lazy {
        repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
    }

    @Test
    fun `a grant schedules the check, after the lease is in memory`() {
        val grant = functionBody(service, "private fun grantLease(")
        val inMemory = grant.indexOf("leases = leases.grant(")
        val arm = grant.indexOf("armLeaseExpiry(pkg, \"new grant\")")
        assertTrue("the check reads the lease it was armed for", inMemory in 0 until arm)
    }

    @Test
    fun `leaving cancels it, first thing after the session closes`() {
        val leave = functionBody(service, "private fun leaveTarget(")
        val close = leave.indexOf("sessions.close(id) ?: return")
        val cancel = leave.indexOf("cancelLeaseExpiry(")
        assertTrue(close in 0 until cancel)
        assertTrue("before the overlays come down", cancel < leave.indexOf("shutter.release("))
    }

    @Test
    fun `rollover and teardown cancel it, and a new grant replaces it`() {
        val rolled = service.substring(service.indexOf("onCycleRolled = {")).substringBefore("},")
        assertTrue(rolled.contains("cancelLeaseExpiry(\"rollover\")"))
        assertTrue(functionBody(service, "private fun teardown()").contains("cancelLeaseExpiry(\"teardown\")"))
        val arm = functionBody(service, "private fun armLeaseExpiry(")
        assertTrue("one pending check at a time", arm.indexOf("cancelLeaseExpiry(") in 0 until arm.indexOf("applyLeaseExpiryPlan("))
    }

    @Test
    fun `entering an app on a live lease re-arms it, and connect re-arms an open leased app`() {
        val enter = functionBody(service, "private fun enterTarget(")
        assertTrue(enter.indexOf("armLeaseExpiry(pkg, \"enter\")") > enter.indexOf("if (maybeLaunchGate(pkg)) {"))
        assertTrue(service.contains("applyLeaseExpiryPlan(LeaseExpiryCheck.onConnect(open, remaining), \"connect\")"))
    }

    @Test
    fun `the check re-reads the session and the lease, and gates through the ordinary path`() {
        val fire = functionBody(service, "private fun onLeaseExpiryCheck(")
        assertTrue(fire.contains("LeaseExpiryCheck.onFire(pkg, open, leases.remainingMs(pkg, nowStamped()))"))
        assertTrue(fire.contains("val open = sessions.openPkg"))
        assertTrue(fire.contains("!overlaysSuppressed() && maybeLaunchGate(pkg)"))
        assertFalse("not AlarmManager", service.substring(service.indexOf("private val leaseExpiryHandler")).substringBefore("\n\n").contains("AlarmManager"))
    }

    @Test
    fun `the gate decision still reads the lease in one place, maybeLaunchGate`() {
        assertTrue(Regex("""leases\.remainingMs\(pkg, now\)""").findAll(service).count() == 1)
        assertTrue(functionBody(service, "private fun maybeLaunchGate(").contains("leaseRemainingMs = leases.remainingMs(pkg, now)"))
    }
}
