package dev.molasses.core.lease

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service sets, keeps and drops the lease-expiry check only through
 * [LeaseExpirySlot], and fires it where [LeaseExpiryCheck] says. Read as
 * text: the service compiles nowhere here.
 */
class LeaseExpiryWiringTest {

    private val service by lazy {
        repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
    }

    private fun count(needle: String) = Regex(Regex.escape(needle)).findAll(service).count()

    @Test
    fun `a grant sets the check whatever is open, after the lease is in memory`() {
        val grant = functionBody(service, "private fun grantLease(")
        val inMemory = grant.indexOf("leases = leases.grant(")
        val arm = grant.indexOf("applyLeaseExpiry(leaseExpirySlot.grant(pkg, sessions.openPkg, leases.remainingMs(pkg, nowStamped())))")
        assertTrue("the check reads the lease it was set for", inMemory in 0 until arm)
    }

    @Test
    fun `a leave goes to the slot, first thing after the session closes`() {
        val leave = functionBody(service, "private fun leaveTarget(")
        val close = leave.indexOf("sessions.close(id) ?: return")
        val decide = leave.indexOf("applyLeaseExpiry(leaseExpirySlot.leave(pkg, reason))")
        assertTrue(close in 0 until decide)
        assertTrue("before the overlays come down", decide < leave.indexOf("shutter.release("))
    }

    @Test
    fun `enter goes to the slot after the gate decision, and never cancels`() {
        val enter = functionBody(service, "private fun enterTarget(")
        val decide = enter.indexOf("applyLeaseExpiry(leaseExpirySlot.enter(pkg, leases.remainingMs(pkg, nowStamped())))")
        assertTrue(decide > enter.indexOf("if (maybeLaunchGate(pkg)) {"))
        assertFalse(enter.contains("removeLeaseExpiryCheck("))
    }

    @Test
    fun `rollover, teardown and connect go to the slot`() {
        val rolled = service.substring(service.indexOf("onCycleRolled = {")).substringBefore("},")
        assertTrue(rolled.contains("applyLeaseExpiry(leaseExpirySlot.clear(\"rollover\"))"))
        assertTrue(functionBody(service, "private fun teardown()").contains("applyLeaseExpiry(leaseExpirySlot.clear(\"teardown\"))"))
        assertTrue(service.contains("applyLeaseExpiry(leaseExpirySlot.connect(open, remaining))"))
    }

    @Test
    fun `only the slot's decisions touch the Handler, and every one is logged with its reason`() {
        // The Handler is posted to in one place and cleared in one place.
        assertEquals(1, count("leaseExpiryHandler.postDelayed("))
        assertEquals(1, count("leaseExpiryHandler.removeCallbacks("))
        val apply = functionBody(service, "private fun applyLeaseExpiry(")
        assertTrue(apply.contains("is LeaseExpirySlot.Action.Schedule -> postLeaseExpiry(action.pkg, action.delayMs, action.why)"))
        assertTrue(apply.contains("Log.i(LEASE_EXPIRY_TAG, \"cancelled pkg=\${action.pkg} (\${action.why})\")"))
        assertTrue(apply.contains("Log.i(LEASE_EXPIRY_TAG, \"kept (\${action.why})\")"))
        assertTrue(functionBody(service, "private fun postLeaseExpiry(").contains("Log.i(LEASE_EXPIRY_TAG, \"scheduled pkg=\$pkg delay=\${delayMs}ms (\$why)\")"))
        // The old entry points are gone, so nothing can bypass the slot.
        for (gone in listOf("armLeaseExpiry(", "cancelLeaseExpiry(", "scheduleLeaseExpiry(", "applyLeaseExpiryPlan(")) {
            assertFalse(gone, service.contains(gone))
        }
        // Every caller of postLeaseExpiry is the slot's Schedule or the fire's Reschedule.
        assertEquals(3, count("postLeaseExpiry("))
    }

    @Test
    fun `the check re-reads the session and the lease, and gates through the ordinary path`() {
        val fire = functionBody(service, "private fun onLeaseExpiryCheck(")
        assertTrue(fire.contains("leaseExpirySlot.fire(pkg, open, leases.remainingMs(pkg, nowStamped()))"))
        assertTrue(fire.contains("val open = sessions.openPkg"))
        assertTrue(fire.contains("!overlaysSuppressed() && maybeLaunchGate(pkg)"))
        assertTrue("a skip says what was in front", fire.contains("(open=\$open foreground=\$foregroundPkg)"))
        assertFalse("not AlarmManager", service.substring(service.indexOf("private val leaseExpiryHandler")).substringBefore("\n\n").contains("AlarmManager"))
    }

    @Test
    fun `the gate decision still reads the lease in one place, maybeLaunchGate`() {
        assertTrue(Regex("""leases\.remainingMs\(pkg, now\)""").findAll(service).count() == 1)
        assertTrue(functionBody(service, "private fun maybeLaunchGate(").contains("leaseRemainingMs = leases.remainingMs(pkg, now)"))
    }
}
