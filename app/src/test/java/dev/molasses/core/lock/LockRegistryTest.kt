package dev.molasses.core.lock

import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockRegistryTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val wallBase = 1_700_000_000_000L

    /** Both clocks in step: an untampered device. */
    private fun honest(offsetMs: Long, boot: Int = 1) =
        StampedInstant(wallMs = wallBase + offsetMs, elapsedMs = offsetMs, bootId = boot)

    private val t0 = honest(0)

    @Test
    fun `an unlocked package has no remainder`() {
        val r = LockRegistry()
        assertFalse(r.isLocked(ig, t0))
        assertEquals(0L, r.remainingMs(ig, t0))
        assertNull(r.reasonFor(ig, t0))
    }

    @Test
    fun `a lock counts down and expires`() {
        val r = LockRegistry().arm(ig, t0, 2 * hour, LockReason.BLOCK)
        assertEquals(2 * hour, r.remainingMs(ig, t0))
        assertEquals(1 * hour, r.remainingMs(ig, honest(1 * hour)))
        assertTrue(r.isLocked(ig, honest(2 * hour - 1)))
        assertFalse(r.isLocked(ig, honest(2 * hour)))
        assertEquals(0L, r.remainingMs(ig, honest(99 * day)))
    }

    @Test
    fun `a lock is scoped to its package`() {
        val r = LockRegistry().arm(ig, t0, 2 * hour, LockReason.BLOCK)
        assertTrue(r.isLocked(ig, t0))
        assertFalse(r.isLocked(yt, t0))
    }

    // -------------------------------------------------- the forward-jump attack

    @Test
    fun `a forward clock jump does not shorten a lock`() {
        // Thirty days armed. One minute of real time later the user sets the
        // system date thirty one days ahead. Under a bare wall-clock deadline,
        // or under a max() clamp, the lock would be gone.
        val r = LockRegistry().arm(ig, t0, 30 * day, LockReason.BLOCK)
        val jumped = StampedInstant(
            wallMs = wallBase + 1 * min + 31 * day,
            elapsedMs = 1 * min,
            bootId = 1,
        )
        assertTrue("lock must hold through a forward jump", r.isLocked(ig, jumped))
        assertEquals(30 * day - 1 * min, r.remainingMs(ig, jumped))
    }

    @Test
    fun `a backward wind does not extend a lock beyond its duration`() {
        // The clamp floors credit at zero rather than crediting negative time,
        // so winding back stalls the countdown but can never add time.
        val r = LockRegistry().arm(ig, t0, 2 * hour, LockReason.BLOCK)
        val wound = StampedInstant(
            wallMs = wallBase - 5 * hour,
            elapsedMs = 30 * min,
            bootId = 1,
        )
        assertTrue(r.remainingMs(ig, wound) <= 2 * hour)
    }

    @Test
    fun `a lock survives a reboot`() {
        // The whole reason a lock carries a wall-clock stamp rather than
        // following PauseWindow onto elapsedRealtime alone.
        val r = LockRegistry().arm(ig, t0, 7 * day, LockReason.BLOCK)
        val afterReboot = StampedInstant(
            wallMs = wallBase + 2 * day,
            elapsedMs = 90_000,
            bootId = 2,
        )
        assertTrue(r.isLocked(ig, afterReboot))
        assertEquals(5 * day, r.remainingMs(ig, afterReboot))
    }

    @Test
    fun `a lock expires normally across a reboot once its time is served`() {
        val r = LockRegistry().arm(ig, t0, 1 * hour, LockReason.BLOCK)
        val afterReboot = StampedInstant(
            wallMs = wallBase + 3 * hour,
            elapsedMs = 60_000,
            bootId = 2,
        )
        assertFalse(r.isLocked(ig, afterReboot))
    }

    // ------------------------------------------------------- the toll invariant

    @Test
    fun `a shorter lock cannot cancel a longer one`() {
        // Otherwise "block instagram 1m" is a one-line undo for a 30 day lock.
        val r = LockRegistry()
            .arm(ig, t0, 30 * day, LockReason.BLOCK)
            .arm(ig, honest(1 * min), 1 * min, LockReason.BLOCK)
        assertTrue(r.remainingMs(ig, honest(1 * min)) > 29 * day)
    }

    @Test
    fun `a longer lock does extend a shorter one`() {
        val r = LockRegistry()
            .arm(ig, t0, 1 * hour, LockReason.BLOCK)
            .arm(ig, honest(1 * min), 5 * hour, LockReason.FOCUS)
        assertEquals(5 * hour, r.remainingMs(ig, honest(1 * min)))
        assertEquals(LockReason.FOCUS, r.reasonFor(ig, honest(1 * min)))
    }

    @Test
    fun `a zero or negative duration is a no-op, never an unlock`() {
        val armed = LockRegistry().arm(ig, t0, 2 * hour, LockReason.BLOCK)
        assertEquals(2 * hour, armed.arm(ig, t0, 0, LockReason.BLOCK).remainingMs(ig, t0))
        assertEquals(2 * hour, armed.arm(ig, t0, -5 * hour, LockReason.BLOCK).remainingMs(ig, t0))
        assertFalse(LockRegistry().arm(ig, t0, 0, LockReason.BLOCK).isLocked(ig, t0))
    }

    @Test
    fun `an empty package is rejected`() {
        assertFalse(LockRegistry().arm("", t0, 2 * hour, LockReason.BLOCK).isLocked("", t0))
    }

    // --------------------------------------------------------------- bulk and housekeeping

    @Test
    fun `armAll locks every named package`() {
        val r = LockRegistry().armAll(listOf(ig, yt), t0, 3 * hour, LockReason.FOCUS)
        assertTrue(r.isLocked(ig, t0))
        assertTrue(r.isLocked(yt, t0))
        assertEquals(2, r.active(t0).size)
    }

    @Test
    fun `active lists only live locks, longest first`() {
        val r = LockRegistry()
            .arm(ig, t0, 1 * hour, LockReason.BLOCK)
            .arm(yt, t0, 6 * hour, LockReason.BLOCK)
        assertEquals(listOf(yt, ig), r.active(t0).map { it.pkg })
        assertEquals(listOf(yt), r.active(honest(2 * hour)).map { it.pkg })
    }

    @Test
    fun `prune drops expired entries and changes no behaviour`() {
        val r = LockRegistry()
            .arm(ig, t0, 1 * hour, LockReason.BLOCK)
            .arm(yt, t0, 6 * hour, LockReason.BLOCK)
        val later = honest(2 * hour)
        val pruned = r.prune(later)
        assertEquals(1, pruned.snapshot().size)
        assertEquals(r.remainingMs(yt, later), pruned.remainingMs(yt, later))
        assertEquals(r.remainingMs(ig, later), pruned.remainingMs(ig, later))
    }

    @Test
    fun `a registry round trips through its snapshot`() {
        val r = LockRegistry()
            .arm(ig, t0, 6 * hour, LockReason.BEDTIME)
            .arm(yt, t0, 2 * hour, LockReason.FOCUS)
        val rebuilt = LockRegistry.of(r.snapshot())
        assertEquals(r.remainingMs(ig, honest(hour)), rebuilt.remainingMs(ig, honest(hour)))
        assertEquals(LockReason.BEDTIME, rebuilt.reasonFor(ig, t0))
    }
}
