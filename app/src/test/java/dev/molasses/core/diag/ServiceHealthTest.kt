package dev.molasses.core.diag

import dev.molasses.core.diag.ServiceHealthPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthTest {

    private val s = 1_000L

    @Test
    fun `never connected`() {
        val h = ServiceHealthPolicy.evaluate(Snapshot(0, 0, 0, 10 * s))
        assertEquals(ServiceHealth.NEVER_CONNECTED, h)
        assertFalse(h.acceptingEvents)
    }

    @Test
    fun `connected but not ready is connecting`() {
        // The silent-drop window: onAccessibilityEvent returns early on !ready.
        val h = ServiceHealthPolicy.evaluate(Snapshot(1 * s, 0, 0, 2 * s))
        assertEquals(ServiceHealth.CONNECTING, h)
        assertFalse("must not read as accepting events", h.acceptingEvents)
    }

    @Test
    fun `ready and beating is healthy`() {
        val h = ServiceHealthPolicy.evaluate(Snapshot(1 * s, 2 * s, 30 * s, 35 * s))
        assertEquals(ServiceHealth.HEALTHY, h)
        assertTrue(h.acceptingEvents)
    }

    @Test
    fun `ready with no heartbeat yet is healthy, measured from ready`() {
        // The first checkpoint is up to 15 s out. A service that just became
        // ready has not missed anything.
        val h = ServiceHealthPolicy.evaluate(Snapshot(1 * s, 2 * s, 0, 10 * s))
        assertEquals(ServiceHealth.HEALTHY, h)
    }

    @Test
    fun `three missed checkpoints is stale`() {
        val h = ServiceHealthPolicy.evaluate(Snapshot(1 * s, 2 * s, 10 * s, 10 * s + 46 * s))
        assertEquals(ServiceHealth.STALE, h)
    }

    @Test
    fun `one missed checkpoint is not stale`() {
        // A doze window or a slow write. Flagging that would cry wolf.
        val h = ServiceHealthPolicy.evaluate(Snapshot(1 * s, 2 * s, 10 * s, 10 * s + 20 * s))
        assertEquals(ServiceHealth.HEALTHY, h)
    }

    @Test
    fun `a stale service is still treated as accepting events`() {
        // It probably is not, but the honest reading is "we do not know".
        // Saying it stopped accepting would be a guess.
        assertTrue(ServiceHealth.STALE.acceptingEvents)
    }

    // ------------------------------------------------------ stuck starting

    @Test
    fun `stuck starting only after the grace period`() {
        assertFalse(ServiceHealthPolicy.isStuckStarting(Snapshot(1 * s, 0, 0, 3 * s)))
        assertFalse(ServiceHealthPolicy.isStuckStarting(Snapshot(1 * s, 0, 0, 6 * s - 1)))
        assertTrue(ServiceHealthPolicy.isStuckStarting(Snapshot(1 * s, 0, 0, 7 * s)))
    }

    @Test
    fun `a ready service is never stuck starting`() {
        assertFalse(ServiceHealthPolicy.isStuckStarting(Snapshot(1 * s, 2 * s, 0, 999 * s)))
    }

    @Test
    fun `a service that never connected is not stuck starting`() {
        // That is a different fault with a different fix, so it must not
        // report as this one.
        assertFalse(ServiceHealthPolicy.isStuckStarting(Snapshot(0, 0, 0, 999 * s)))
    }

    @Test
    fun `the grace period is five seconds as specified`() {
        assertEquals(5_000L, ServiceHealthPolicy.STARTUP_GRACE_MS)
    }
}
