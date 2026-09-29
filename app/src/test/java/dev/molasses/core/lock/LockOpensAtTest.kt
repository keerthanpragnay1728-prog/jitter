package dev.molasses.core.lock

import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockOpensAtTest {

    private val pkg = "com.instagram.android"
    private val hour = 60 * 60_000L
    private val wall0 = 1_700_000_000_000L

    private fun at(wall: Long, elapsed: Long, boot: Int = 1) = StampedInstant(wallMs = wall, elapsedMs = elapsed, bootId = boot)

    private val armed = LockRegistry.of(emptyList()).arm(pkg, at(wall0, 0), 4 * hour, LockReason.BLOCK)

    @Test
    fun `with clocks in agreement it opens at start plus duration`() {
        val now = at(wall0 + hour, hour)
        assertEquals(wall0 + 4 * hour, LockOpensAt.wallMs(armed, pkg, now))
    }

    @Test
    fun `a forward clock jump moves the opening later, never earlier`() {
        // One real hour served, the wall clock pushed thirty days ahead.
        val jumped = at(wall0 + hour + 30 * 24 * hour, hour)
        val opens = LockOpensAt.wallMs(armed, pkg, jumped)!!
        assertTrue("a lock still standing must not read as already open", opens > jumped.wallMs)
        assertEquals("three hours still to serve, from now", jumped.wallMs + 3 * hour, opens)
    }

    @Test
    fun `no lock, or one that has run out, has no opening time`() {
        assertNull(LockOpensAt.wallMs(armed, "com.other", at(wall0, 0)))
        assertNull(LockOpensAt.wallMs(armed, pkg, at(wall0 + 5 * hour, 5 * hour)))
    }

    @Test
    fun `it tracks the stored lock as it runs down`() {
        // The same instant from every read while the clocks agree: it is not
        // re-derived from a duration at the moment of asking.
        val a = LockOpensAt.wallMs(armed, pkg, at(wall0 + 10_000, 10_000))
        val b = LockOpensAt.wallMs(armed, pkg, at(wall0 + 2 * hour, 2 * hour))
        assertEquals(a, b)
    }
}
