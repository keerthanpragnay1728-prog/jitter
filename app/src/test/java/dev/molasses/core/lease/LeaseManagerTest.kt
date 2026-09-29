package dev.molasses.core.lease

import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseManagerTest {

    private val minute = 60_000L
    private val boot = 7

    private fun at(elapsedMs: Long, wallMs: Long = 1_700_000_000_000L + elapsedMs, bootId: Int = boot) =
        StampedInstant(wallMs = wallMs, elapsedMs = elapsedMs, bootId = bootId)

    private val t0 = at(1_000_000L)

    @Test
    fun `an ungranted package holds no lease`() {
        val m = LeaseManager()
        assertEquals(0L, m.remainingMs("a", t0))
        assertFalse(m.isActive("a", t0))
        assertNull(m.active("a", t0))
    }

    @Test
    fun `a granted lease runs for its duration and then stops`() {
        val m = LeaseManager().grant("a", t0, 5 * minute, accumulatedMs = 0)
        assertEquals(5 * minute, m.remainingMs("a", t0))
        assertEquals(2 * minute, m.remainingMs("a", at(t0.elapsedMs + 3 * minute)))
        assertEquals(0L, m.remainingMs("a", at(t0.elapsedMs + 5 * minute)))
        assertFalse(m.isActive("a", at(t0.elapsedMs + 5 * minute)))
    }

    @Test
    fun `a lease is per package`() {
        val m = LeaseManager().grant("a", t0, 5 * minute, 0)
        assertTrue(m.isActive("a", t0))
        assertFalse(m.isActive("b", t0))
    }

    @Test
    fun `winding the wall clock back does not hold a lease open`() {
        // The attack a relief deadline has to survive. There is nothing to
        // clamp because the wall clock is not consulted at all.
        val m = LeaseManager().grant("a", t0, 5 * minute, 0)
        val later = at(t0.elapsedMs + 6 * minute, wallMs = t0.wallMs - 60 * minute)
        assertEquals(0L, m.remainingMs("a", later))
    }

    @Test
    fun `winding the wall clock forward does not end a lease early`() {
        // The mirror. It is not a bypass, but a user who changes timezone
        // should not lose the minutes they paid attention for.
        val m = LeaseManager().grant("a", t0, 5 * minute, 0)
        val later = at(t0.elapsedMs + minute, wallMs = t0.wallMs + 30 * minute)
        assertEquals(4 * minute, m.remainingMs("a", later))
    }

    @Test
    fun `a reboot ends every lease`() {
        val m = LeaseManager().grant("a", t0, 15 * minute, 0)
        val afterBoot = at(1_000L, bootId = boot + 1)
        assertEquals(0L, m.remainingMs("a", afterBoot))
    }

    @Test
    fun `a stamp from the future reads as expired and not as a long lease`() {
        val m = LeaseManager().grant("a", at(5_000_000L), 5 * minute, 0)
        assertEquals(0L, m.remainingMs("a", t0))
    }

    @Test
    fun `an unset stamp is not a lease`() {
        val m = LeaseManager.of(listOf(Lease("a", StampedInstant.UNSET, 5 * minute, 0)))
        assertEquals(0L, m.remainingMs("a", t0))
    }

    @Test
    fun `a live lease is never lengthened`() {
        // The invariant that makes a lease safe to offer. Mirror image of
        // LockRegistry.arm, which can only ever extend.
        val m = LeaseManager()
            .grant("a", t0, 5 * minute, 0)
            .grant("a", at(t0.elapsedMs + minute), 15 * minute, 0)
        assertEquals(4 * minute, m.remainingMs("a", at(t0.elapsedMs + minute)))
    }

    @Test
    fun `a live lease can be shortened`() {
        // More friction, so it is allowed. Nothing in the UI does this; it is
        // allowed because refusing it would be the unsafe direction.
        val m = LeaseManager()
            .grant("a", t0, 15 * minute, 0)
            .grant("a", t0, 5 * minute, 0)
        assertEquals(5 * minute, m.remainingMs("a", t0))
    }

    @Test
    fun `an expired lease can be granted again in full`() {
        val expiry = at(t0.elapsedMs + 5 * minute)
        val m = LeaseManager()
            .grant("a", t0, 5 * minute, 0)
            .grant("a", expiry, 15 * minute, 0)
        assertEquals(15 * minute, m.remainingMs("a", expiry))
    }

    @Test
    fun `a non positive duration grants nothing`() {
        val m = LeaseManager().grant("a", t0, 0L, 0).grant("a", t0, -minute, 0)
        assertFalse(m.isActive("a", t0))
    }

    @Test
    fun `an empty package grants nothing`() {
        assertTrue(LeaseManager().grant("", t0, 5 * minute, 0).snapshot().isEmpty())
    }

    @Test
    fun `the accumulated total at grant is carried on the lease`() {
        // The one number that crosses between the two systems, and it crosses
        // in this direction only: the engine reads it, the lease never reads
        // the engine.
        val m = LeaseManager().grant("a", t0, 5 * minute, accumulatedMs = 38 * minute)
        assertEquals(38 * minute, m.active("a", t0)?.takenAtAccumulatedMs)
    }

    @Test
    fun `prune drops expired leases and keeps live ones`() {
        val later = at(t0.elapsedMs + 6 * minute)
        val m = LeaseManager()
            .grant("gone", t0, 5 * minute, 0)
            .grant("here", t0, 15 * minute, 0)
            .prune(later)
        assertEquals(listOf("here"), m.snapshot().map { it.pkg })
    }

    @Test
    fun `pruning changes no reading`() {
        val later = at(t0.elapsedMs + 6 * minute)
        val m = LeaseManager()
            .grant("gone", t0, 5 * minute, 0)
            .grant("here", t0, 15 * minute, 0)
        assertEquals(m.remainingMs("here", later), m.prune(later).remainingMs("here", later))
        assertEquals(m.remainingMs("gone", later), m.prune(later).remainingMs("gone", later))
    }

    @Test
    fun `a lease round trips through persistence`() {
        val m = LeaseManager().grant("a", t0, 10 * minute, 12_345L)
        val back = LeaseManager.of(m.snapshot())
        assertEquals(m.remainingMs("a", t0), back.remainingMs("a", t0))
        assertEquals(m.active("a", t0), back.active("a", t0))
    }

    @Test
    fun `restoring an expired lease does not resurrect it`() {
        // B5: killing the launcher must not bring a lease back. Expiry is
        // recomputed from the clock on every read, so there is no stored
        // verdict to come back wrong.
        val m = LeaseManager().grant("a", t0, 5 * minute, 0)
        val later = at(t0.elapsedMs + 6 * minute)
        assertFalse(LeaseManager.of(m.snapshot()).isActive("a", later))
    }

    @Test
    fun `a lease survives process death within a boot`() {
        val m = LeaseManager().grant("a", t0, 15 * minute, 0)
        val later = at(t0.elapsedMs + 2 * minute)
        assertEquals(13 * minute, LeaseManager.of(m.snapshot()).remainingMs("a", later))
    }

    @Test
    fun `snapshot is ordered by package so two writes of one state match`() {
        val m = LeaseManager()
            .grant("z", t0, 5 * minute, 0)
            .grant("a", t0, 5 * minute, 0)
        assertEquals(listOf("a", "z"), m.snapshot().map { it.pkg })
    }
}
