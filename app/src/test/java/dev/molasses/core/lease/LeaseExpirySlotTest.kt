package dev.molasses.core.lease

import dev.molasses.core.lease.LeaseExpirySlot.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's lease-expiry check, step by step, with a fake Handler: a map
 * of the one posted check to its due time. Each step applies the slot's
 * action exactly as `applyLeaseExpiry` does.
 */
class LeaseExpirySlotTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val tenMin = 10 * 60_000L

    private val slot = LeaseExpirySlot()
    private var now = 0L
    /** The posted check: package and due time, or null. */
    private var posted: Pair<String, Long>? = null
    private val log = mutableListOf<String>()

    private fun apply(action: Action) {
        log += "${action::class.simpleName}: ${action.why}"
        when (action) {
            is Action.Schedule -> posted = action.pkg to now + action.delayMs
            is Action.Cancel -> posted = null
            is Action.Keep -> Unit
        }
    }

    /** Run the clock to [t]; if the posted check is due, fire it as the service does. */
    private fun runTo(t: Long, openPkg: String?, remainingAt: (Long) -> Long): LeaseExpiryCheck.Fire? {
        now = t
        val (pkg, due) = posted ?: return null
        if (due > t) return null
        posted = null
        val fire = slot.fire(pkg, openPkg, remainingAt(t))
        if (fire is LeaseExpiryCheck.Fire.Reschedule) posted = pkg to t + fire.delayMs
        return fire
    }

    /** A lease of [ms] granted at [grantedAt]. */
    private fun lease(grantedAt: Long, ms: Long): (Long) -> Long = { t -> (grantedAt + ms - t).coerceAtLeast(0L) }

    @Test
    fun `granted from a home-first entry gate and relaunched, the expiry gate is raised at the deadline`() {
        // The entry gate sent the app home at its first draw, so its session
        // closed before the lease was chosen. The lease is granted over the
        // launcher, with nothing open.
        apply(slot.leave(ig, "launcher"))
        val remaining = lease(grantedAt = 10_000, ms = tenMin)
        now = 10_000
        apply(slot.grant(ig, openPkg = null, remainingMs = remaining(now)))
        assertEquals(ig to 10_000 + tenMin, posted)
        // The leave of the gated session, arriving late, must not drop it.
        apply(slot.leave(ig, "watchdog saw org.jitteros.app"))
        assertEquals(ig to 10_000 + tenMin, posted)
        // The relaunch enters the app: the grant's check stays, not re-armed.
        now = 10_400
        apply(slot.enter(ig, remaining(now)))
        assertEquals(ig to 10_000 + tenMin, posted)
        // Watching, no scroll, no window change: the deadline comes.
        assertNull(runTo(10_000 + tenMin - 1, ig, remaining))
        assertEquals(LeaseExpiryCheck.Fire.RaiseGate, runTo(10_000 + tenMin, ig, remaining))
    }

    @Test
    fun `a relaunch that finds the app's own gate still drawn cannot lose the check`() {
        // maybeLaunchGate's AlreadyOwn returns before enter is applied, so
        // the slot sees no enter at all. The grant's check is still there.
        val remaining = lease(grantedAt = 0, ms = tenMin)
        apply(slot.grant(ig, openPkg = null, remainingMs = remaining(0)))
        assertEquals(LeaseExpiryCheck.Fire.RaiseGate, runTo(tenMin, ig, remaining))
    }

    @Test
    fun `the same through the expired gate's lease`() {
        val first = lease(grantedAt = 0, ms = tenMin)
        apply(slot.grant(ig, openPkg = null, remainingMs = first(0)))
        apply(slot.enter(ig, first(500)))
        assertEquals(LeaseExpiryCheck.Fire.RaiseGate, runTo(tenMin, ig, first))
        // The LEASE EXPIRED gate went up and sent the app home: a real leave,
        // with nothing pending any more.
        apply(slot.leave(ig, "launcher"))
        // Another lease, chosen on the expired gate, over the launcher.
        val second = lease(grantedAt = tenMin + 20_000, ms = 5 * 60_000L)
        now = tenMin + 20_000
        apply(slot.grant(ig, openPkg = null, remainingMs = second(now)))
        apply(slot.leave(ig, "launcher"))
        now = tenMin + 20_500
        apply(slot.enter(ig, second(now)))
        assertEquals(LeaseExpiryCheck.Fire.RaiseGate, runTo(tenMin + 20_000 + 5 * 60_000L, ig, second))
    }

    @Test
    fun `leaving for real still cancels`() {
        val remaining = lease(grantedAt = 0, ms = tenMin)
        apply(slot.grant(ig, openPkg = null, remainingMs = remaining(0)))
        apply(slot.enter(ig, remaining(400)))
        apply(slot.leave(ig, "launcher"))
        assertNull(posted)
        assertNull(slot.pending)
        assertTrue(log.last().startsWith("Cancel: left $ig"))
    }

    @Test
    fun `a grant while the app is open counts as opened, so a leave cancels`() {
        apply(slot.grant(ig, openPkg = ig, remainingMs = tenMin))
        apply(slot.leave(ig, "launcher"))
        assertNull(posted)
    }

    @Test
    fun `leaving another app keeps the check`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.enter(ig, tenMin))
        apply(slot.leave(yt, "switched to $ig"))
        assertEquals(ig, posted?.first)
        assertTrue(log.last().contains("the check is for $ig"))
    }

    @Test
    fun `entering another app on its own live lease replaces the check, and one with none keeps it`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.enter(yt, remainingMs = 0L))
        assertEquals(ig, posted?.first)
        apply(slot.enter(yt, remainingMs = 60_000L))
        assertEquals(yt to 60_000L, posted)
    }

    @Test
    fun `a lease from an earlier visit is armed again on enter`() {
        apply(slot.enter(ig, remainingMs = 90_000L))
        assertEquals(ig to 90_000L, posted)
        assertEquals(LeaseExpirySlot.Pending(ig, opened = true), slot.pending)
    }

    @Test
    fun `a new grant, rollover and teardown drop it`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.grant(yt, openPkg = null, remainingMs = 60_000L))
        assertEquals(yt to 60_000L, posted)
        apply(slot.clear("rollover"))
        assertNull(posted)
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.clear("teardown"))
        assertNull(posted)
        assertNull(slot.pending)
    }

    @Test
    fun `a check that fires with the app closed does nothing and empties the slot`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        assertEquals(LeaseExpiryCheck.Fire.Skip("no longer open"), runTo(tenMin, null) { 0L })
        assertNull(slot.pending)
    }

    @Test
    fun `time still on the lease at fire reschedules and stays pending`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.enter(ig, tenMin))
        val fire = runTo(tenMin, ig) { 30_000L }
        assertEquals(LeaseExpiryCheck.Fire.Reschedule(30_000L), fire)
        assertEquals(ig to tenMin + 30_000L, posted)
        assertEquals(LeaseExpirySlot.Pending(ig, opened = true), slot.pending)
    }

    @Test
    fun `every decision carries its reason`() {
        apply(slot.grant(ig, openPkg = null, remainingMs = tenMin))
        apply(slot.leave(ig, "late"))
        apply(slot.enter(ig, tenMin))
        apply(slot.leave(ig, "launcher"))
        assertEquals(
            listOf(
                "Schedule: new grant, open=none",
                "Keep: left $ig (late) before it opened since its grant",
                "Keep: enter $ig: its check stays, set for its deadline",
                "Cancel: left $ig (launcher)",
            ),
            log,
        )
    }
}
