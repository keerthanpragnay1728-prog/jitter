package dev.molasses.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOffLineTest {

    private val t0 = 50_000L
    private val second = 1_000L

    /** Read once a second from [t0] for [seconds], the service not working throughout. */
    private fun notWorkingFor(seconds: Int): Long? {
        var since: Long? = null
        for (s in 0..seconds) since = ServiceOffLine.since(since, working = false, visible = true, nowMs = t0 + s * second)
        return since
    }

    @Test
    fun `the grace is ten seconds`() {
        assertEquals(10_000L, ServiceOffLine.GRACE_MS)
    }

    @Test
    fun `not shown at 9 s`() {
        val since = notWorkingFor(9)
        assertEquals(t0, since)
        assertFalse(ServiceOffLine.shown(since, working = false, nowMs = t0 + 9 * second))
        assertFalse(ServiceOffLine.shown(since, working = false, nowMs = t0 + 10 * second - 1))
    }

    @Test
    fun `shown at 10 s`() {
        val since = notWorkingFor(10)
        assertTrue(ServiceOffLine.shown(since, working = false, nowMs = t0 + 10 * second))
    }

    @Test
    fun `hidden again as soon as the service works`() {
        val since = notWorkingFor(30)
        assertTrue(ServiceOffLine.shown(since, working = false, nowMs = t0 + 30 * second))
        // Before the next read has cleared the clock, the working flag alone hides it.
        assertFalse(ServiceOffLine.shown(since, working = true, nowMs = t0 + 31 * second))
        assertNull(ServiceOffLine.since(since, working = true, visible = true, nowMs = t0 + 31 * second))
    }

    @Test
    fun `a normal boot shows nothing`() {
        // Not working for the first four seconds, then connected.
        var since: Long? = null
        for (s in 0..4) {
            since = ServiceOffLine.since(since, working = false, visible = true, nowMs = t0 + s * second)
            assertFalse(ServiceOffLine.shown(since, working = false, nowMs = t0 + s * second))
        }
        since = ServiceOffLine.since(since, working = true, visible = true, nowMs = t0 + 5 * second)
        assertFalse(ServiceOffLine.shown(since, working = true, nowMs = t0 + 5 * second))
    }

    @Test
    fun `the grace is continuous, so a working read in between starts it again`() {
        var since = notWorkingFor(8)
        since = ServiceOffLine.since(since, working = true, visible = true, nowMs = t0 + 9 * second)
        since = ServiceOffLine.since(since, working = false, visible = true, nowMs = t0 + 10 * second)
        assertEquals(t0 + 10 * second, since)
        assertFalse(ServiceOffLine.shown(since, working = false, nowMs = t0 + 19 * second))
        assertTrue(ServiceOffLine.shown(since, working = false, nowMs = t0 + 20 * second))
    }

    @Test
    fun `leaving the console resets the clock, so a return waits the grace again`() {
        var since = notWorkingFor(30)
        since = ServiceOffLine.since(since, working = false, visible = false, nowMs = t0 + 31 * second)
        assertNull(since)
        since = ServiceOffLine.since(since, working = false, visible = true, nowMs = t0 + 60 * second)
        assertFalse(ServiceOffLine.shown(since, working = false, nowMs = t0 + 69 * second))
        assertTrue(ServiceOffLine.shown(since, working = false, nowMs = t0 + 70 * second))
    }
}
