package dev.molasses.engine

import dev.molasses.core.model.EngineSnapshot
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The penalty ratchet charges each overdue interval exactly once across a
 * restart.
 *
 * Its anchor, the point it has charged up to, used to live only in memory. A
 * restarted engine anchored at zero, so its first evaluation measured from the
 * lease mark and billed the whole overdue stretch a second time.
 */
class PenaltyRestartTest {

    private val pkg = "com.instagram.android"
    private val min = 60_000L

    private fun engine(initial: EngineSnapshot, clock: MutableClock) = FrictionEngine(
        initial = initial,
        store = FakeStore(),
        roll = { 0f },
        ledger = FakeLedger(),
        wallClock = clock,
        monotonicClock = clock,
        bootIdProvider = { 1 },
        scope = TestScope(),
    )

    private fun penalty(e: FrictionEngine) = e.snapshot().perApp.getValue(pkg).penaltyMs

    /** Enter, take a five minute lease a second in, sit to eleven minutes. */
    private fun overdueSession(clock: MutableClock): Pair<FrictionEngine, Long> {
        val e = engine(EngineSnapshot(), clock)
        e.onForegroundEnter(pkg, clock.now)
        clock.advance(1_000)
        e.onLeaseGranted(pkg, 5 * min, clock.now)
        clock.now = 11 * min
        e.checkpoint(clock.now)
        val charged = penalty(e)
        assertTrue("the setup must be overdue, charged $charged", charged > 5 * min)
        return e to charged
    }

    @Test
    fun `a restart charges the overdue interval exactly once`() {
        val clock = MutableClock(0)
        val (before, charged) = overdueSession(clock)
        val stored = before.snapshot()
        assertEquals(11 * min, stored.perApp.getValue(pkg).penaltyAnchorMs)

        val after = engine(stored, clock)
        after.onForegroundEnter(pkg, clock.now)
        after.checkpoint(clock.now)
        assertEquals("the restart re-billed time already charged", charged, penalty(after))

        clock.advance(min)
        after.checkpoint(clock.now)
        assertEquals("one more overdue minute costs one minute", charged + min, penalty(after))
    }

    @Test
    fun `an anchor at zero, the old behaviour, charges the same interval twice`() {
        // The counterexample, kept so the test above is known to be the one
        // that separates the two. A stored anchor of zero is what every
        // restart used to amount to.
        val clock = MutableClock(0)
        val (before, charged) = overdueSession(clock)
        val stored = before.snapshot()
        val zeroed = stored.copy(
            perApp = stored.perApp.mapValues { (_, a) -> a.copy(penaltyAnchorMs = 0L) },
        )
        val after = engine(zeroed, clock)
        after.onForegroundEnter(pkg, clock.now)
        after.checkpoint(clock.now)
        assertTrue("an anchor at zero should re-bill, got ${penalty(after)}", penalty(after) > charged)
    }

    @Test
    fun `a file with no stored anchor charges nothing retroactively`() {
        // Every install written before the anchor was persisted.
        val clock = MutableClock(0)
        val (before, charged) = overdueSession(clock)
        val stored = before.snapshot()
        val legacy = stored.copy(
            perApp = stored.perApp.mapValues { (_, a) -> a.copy(penaltyAnchorMs = null) },
        )
        val after = engine(legacy, clock)
        after.onForegroundEnter(pkg, clock.now)
        after.checkpoint(clock.now)
        assertEquals(charged, penalty(after))
        clock.advance(min)
        after.checkpoint(clock.now)
        assertEquals(charged + min, penalty(after))
    }
}
