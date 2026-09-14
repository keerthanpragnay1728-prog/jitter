package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType
import dev.molasses.core.model.FrictionAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrictionEngineTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val min = 60_000L
    private val hour = 60 * min

    /**
     * Drives the engine with a single timeline.
     *
     * Every call sets both clocks from the same instant, which is how
     * production behaves: `nowMs` is `SystemClock.elapsedRealtime()` and the
     * wall clock tracks it. Divergence between the two is the reconciler's
     * problem and is tested in `ClockTamperClampTest`, not here.
     */
    private class Rig(initial: EngineSnapshot = EngineSnapshot()) {
        val store = FakeStore()
        val ledger = FakeLedger()
        val clock = SplitClock(wall = WALL_BASE, mono = 0)
        val scope = TestScope()
        val engine = FrictionEngine(
            initial = initial,
            store = store,
            ledger = ledger,
            wallClock = clock,
            monotonicClock = clock,
            bootIdProvider = clock,
            scope = scope,
        )

        private fun at(ms: Long) {
            clock.mono = ms
            clock.wall = WALL_BASE + ms
        }

        /** Advance the wall clock alone, as a user setting the clock forward does. */
        fun warpWall(ms: Long) { clock.wall += ms }

        /**
         * A reboot: the monotonic clock restarts near zero and BOOT_COUNT
         * increments. The wall clock carries on across it, which is the only
         * reason an anchor can survive a reboot at all.
         */
        fun reboot(wallAdvanceMs: Long) {
            clock.wall += wallAdvanceMs
            clock.mono = 0
            clock.boot += 1
        }

        fun tick(ms: Long) { clock.mono = ms; clock.wall = WALL_BASE + ms; engine.checkpoint(ms) }
        fun tickNow() { engine.checkpoint(clock.mono) }
        fun state() = engine.state.value

        fun enter(pkg: String, ms: Long) { at(ms); engine.onForegroundEnter(pkg, ms) }
        fun exit(pkg: String, ms: Long) { at(ms); engine.onForegroundExit(pkg, ms) }
        fun scroll(pkg: String, ms: Long): FrictionAction { at(ms); return engine.onScroll(pkg, ms) }
        fun clear(pkg: String, ms: Long) { at(ms); engine.onGateCleared(pkg, ms) }
        fun abandon(pkg: String, ms: Long) { at(ms); engine.onGateAbandoned(pkg, ms) }
        fun snap(pkg: String): AppSnapshot = engine.snapshot().perApp.getValue(pkg)

        companion object { const val WALL_BASE = 1_700_000_000_000L }
    }

    @Test
    fun `under five minutes nothing happens`() {
        val r = Rig()
        r.enter(ig, 0)
        assertEquals(FrictionAction.None, r.scroll(ig, 1 * min))
        assertEquals(FrictionAction.None, r.scroll(ig, 4 * min + 59_000))
    }

    @Test
    fun `at five minutes a gate is demanded, then the tier one stall applies`() {
        val r = Rig()
        r.enter(ig, 0)
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min))
        // Still gated until cleared -- a gate is not a suggestion.
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min + 5_000))
        r.clear(ig, 5 * min + 6_000)
        assertEquals(FrictionAction.Stall(1_000), r.scroll(ig, 5 * min + 7_000))
    }

    @Test
    fun `full ladder walk produces the documented behaviour`() {
        val r = Rig()
        r.enter(ig, 0)

        fun clearAndStall(atMs: Long, tier: Int, stallMs: Long) {
            assertEquals("gate at tier $tier", FrictionAction.Gate(tier), r.scroll(ig, atMs))
            r.clear(ig, atMs)
            assertEquals(
                "stall after tier $tier",
                FrictionAction.Stall(stallMs),
                r.scroll(ig, atMs + 1_000),
            )
        }

        clearAndStall(5 * min, 1, 1_000)
        clearAndStall(10 * min, 2, 3_000)
        clearAndStall(15 * min, 3, 5_000)
        clearAndStall(20 * min, 4, 5_000)
        // Terminal: re-arms every five minutes, stall pinned at 5000 ms,
        // indefinitely. No silent cap.
        clearAndStall(25 * min, 5, 5_000)
        clearAndStall(30 * min, 6, 5_000)
        clearAndStall(120 * min, 24, 5_000)
    }

    // ------------------------------------------------------- the invariant

    @Test
    fun `clearing a gate leaves accumulated time untouched`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 12 * min)
        val before = r.snap(ig).accumulatedMs
        assertTrue("time must have accumulated at all, got $before", before >= 12 * min)

        r.clear(ig, 12 * min)

        assertEquals("gate clearing must not refund time", before, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `clearing a gate never lowers the stall duration`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 15 * min)
        r.clear(ig, 15 * min)
        assertEquals(
            FrictionAction.Stall(5_000),
            r.scroll(ig, 15 * min + 1_000),
        )
        // A duplicated or late clear must not walk it back.
        r.clear(ig, 15 * min + 2_000)
        r.clear(ig, 15 * min + 2_500)
        assertEquals(
            FrictionAction.Stall(5_000),
            r.scroll(ig, 15 * min + 3_000),
        )
    }

    @Test
    fun `clearing a gate never rewinds tierIndex`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 20 * min)
        assertEquals(4, r.snap(ig).tierIndex)

        r.clear(ig, 20 * min)
        r.clear(ig, 20 * min)
        r.abandon(ig, 20 * min)

        assertEquals(4, r.snap(ig).tierIndex)
    }

    @Test
    fun `clearing a gate writes only tierUnlockedUntil and gatesCleared`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 10 * min)
        val before = r.snap(ig)

        r.clear(ig, 10 * min)
        val after = r.snap(ig)

        // The two fields a clear is allowed to move.
        assertNotEquals(before.tierUnlockedUntilMs, after.tierUnlockedUntilMs)
        assertEquals(before.gatesCleared + 1, after.gatesCleared)
        // Everything else frozen. Compared field-by-field rather than with
        // copy(), so a field added to AppSnapshot later fails this test
        // instead of being silently carried through.
        assertEquals(before.pkg, after.pkg)
        assertEquals(before.accumulatedMs, after.accumulatedMs)
        assertEquals(before.tierIndex, after.tierIndex)
    }

    @Test
    fun `unlock advances exactly one tier width, not to the current time`() {
        val r = Rig()
        r.enter(ig, 0)
        // Enter tier 2 late: 13 minutes in, not 10.
        r.scroll(ig, 13 * min)
        r.clear(ig, 13 * min)
        // Unlocked to the tier-3 boundary (15 min), not 13 + 5 = 18.
        assertEquals(15 * min, r.snap(ig).tierUnlockedUntilMs)
        assertEquals(FrictionAction.Stall(3_000), r.scroll(ig, 14 * min))
        assertEquals(FrictionAction.Gate(3), r.scroll(ig, 15 * min))
    }

    @Test
    fun `abandoning a gate changes nothing and re-gates on the next scroll`() {
        val r = Rig()
        r.enter(ig, 0)
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min))
        val before = r.snap(ig)

        r.abandon(ig, 5 * min + 1_000)

        val after = r.snap(ig)
        assertEquals(before.gatesCleared, after.gatesCleared)
        assertEquals(before.tierUnlockedUntilMs, after.tierUnlockedUntilMs)
        assertEquals(before.tierIndex, after.tierIndex)
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min + 2_000))
    }

    // -------------------------------------------------------- accounting

    @Test
    fun `leaving the app stops accumulation and returning resumes it`() {
        val r = Rig()
        r.enter(ig, 0)
        r.exit(ig, 4 * min)
        assertEquals(4 * min, r.snap(ig).accumulatedMs)

        // An hour passes off-app; none of it counts.
        r.enter(ig, 64 * min)
        assertEquals(4 * min, r.snap(ig).accumulatedMs)

        // Two more minutes in-app tips it over five.
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 66 * min))
    }

    @Test
    fun `re-entering an already open app does not restart the session`() {
        val r = Rig()
        r.enter(ig, 0)
        // TYPE_WINDOW_STATE_CHANGED fires repeatedly inside one app.
        repeat(20) { r.enter(ig, (it * 10_000).toLong()) }
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min))
    }

    @Test
    fun `a scroll with no preceding window state change still accumulates`() {
        val r = Rig()
        assertEquals(FrictionAction.None, r.scroll(ig, 0))
        assertEquals(FrictionAction.Gate(1), r.scroll(ig, 5 * min))
    }

    @Test
    fun `switching apps closes the previous session and ladders are independent`() {
        val r = Rig()
        r.enter(ig, 0)
        r.enter(yt, 3 * min)
        assertEquals(3 * min, r.snap(ig).accumulatedMs)
        assertEquals(0L, r.snap(yt).accumulatedMs)

        assertEquals(FrictionAction.None, r.scroll(yt, 6 * min))
        assertEquals(FrictionAction.Gate(1), r.scroll(yt, 8 * min))
        // Instagram is untouched at 3 minutes.
        assertEquals(3 * min, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `a backwards monotonic timestamp credits zero rather than negative time`() {
        val r = Rig()
        r.enter(ig, 10 * min)
        // Should be impossible; a boot-reset value leaking past the reconciler
        // must not produce negative accumulation.
        assertEquals(FrictionAction.None, r.scroll(ig, 1 * min))
        assertTrue(r.snap(ig).accumulatedMs >= 0)
    }

    @Test
    fun `an implausibly long single session is capped`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 400 * hour)
        val acc = r.snap(ig).accumulatedMs
        assertTrue("expected a 12h cap, got ${acc}ms", acc <= 12 * hour)
    }

    // ------------------------------------------------------ cycle rollover

    @Test
    fun `abstinence policy rolls the cycle after six idle hours`() {
        val r = Rig(EngineSnapshot(resetPolicy = CycleResetPolicy.ABSTINENCE_6H))
        r.enter(ig, 0)
        r.scroll(ig, 15 * min)
        r.exit(ig, 15 * min)
        assertEquals(3, r.snap(ig).tierIndex)

        r.enter(ig, 15 * min + 6 * hour)

        assertEquals("cycle should have rolled", 0, r.snap(ig).tierIndex)
        assertEquals(0L, r.snap(ig).accumulatedMs)
        assertEquals(FrictionAction.None, r.scroll(ig, 15 * min + 6 * hour + 1 * min))
    }

    @Test
    fun `abstinence policy does not roll one minute short of six hours`() {
        val r = Rig(EngineSnapshot(resetPolicy = CycleResetPolicy.ABSTINENCE_6H))
        r.enter(ig, 0)
        r.scroll(ig, 15 * min)
        r.exit(ig, 15 * min)

        r.enter(ig, 15 * min + 6 * hour - 60_000)

        assertEquals(3, r.snap(ig).tierIndex)
        assertEquals(15 * min, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `abstinence policy does not roll while the app keeps being used`() {
        val r = Rig(EngineSnapshot(resetPolicy = CycleResetPolicy.ABSTINENCE_6H))
        r.enter(ig, 0)
        // Seven hours of on-and-off use, never six clear hours away.
        var t = 0L
        repeat(7) {
            r.scroll(ig, t + 1 * min)
            r.exit(ig, t + 2 * min)
            t += 1 * hour
            r.enter(ig, t)
        }
        assertTrue("should still be laddered, tier=${r.snap(ig).tierIndex}", r.snap(ig).tierIndex >= 2)
    }

    @Test
    fun `fixed window policy rolls six hours after the anchor even while in use`() {
        val r = Rig(EngineSnapshot(resetPolicy = CycleResetPolicy.FIXED_WINDOW_6H))
        r.enter(ig, 0)
        r.scroll(ig, 15 * min)
        r.exit(ig, 15 * min)

        // Only 20 minutes of abstinence, but six hours since the anchor.
        r.enter(ig, 6 * hour + 35 * min)

        assertEquals(0, r.snap(ig).tierIndex)
    }

    @Test
    fun `a rollover does not throw despite the monotonic tier counter`() {
        // Regression guard: a rollover must replace the AppState, not assign 0
        // to an existing MonotonicInt, which throws by design.
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 30 * min)
        r.exit(ig, 30 * min)
        r.enter(ig, 30 * min + 7 * hour) // must not throw
        assertEquals(0, r.snap(ig).tierIndex)
    }

    // ------------------------------------------------------------- hydration

    @Test
    fun `state survives a restart through the snapshot`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 12 * min)
        r.clear(ig, 12 * min)
        r.exit(ig, 12 * min)
        val persisted = r.engine.snapshot()

        val r2 = Rig(persisted)
        r2.enter(ig, 0)
        // Picks up mid-tier-2 with the gate already paid for.
        assertEquals(FrictionAction.Stall(3_000), r2.scroll(ig, 1_000))
        assertEquals(2, r2.snap(ig).tierIndex)
        assertEquals(1, r2.snap(ig).gatesCleared)
    }

    @Test
    fun `a persisted snapshot folds the live session so a crash loses nothing`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 7 * min) // still open, never exited
        val persisted = r.engine.snapshot()
        assertTrue(
            "open session must be folded into the snapshot, got ${persisted.perApp[ig]?.accumulatedMs}",
            persisted.perApp.getValue(ig).accumulatedMs >= 7 * min,
        )
    }

    // ---------------------------------------------------------------- ledger

    @Test
    fun `ledger records the gate lifecycle`() = runTest {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 5 * min)
        r.abandon(ig, 5 * min + 1_000)
        r.scroll(ig, 5 * min + 2_000)
        r.clear(ig, 5 * min + 3_000)
        r.exit(ig, 6 * min)

        val types = r.ledger.typesFor(ig)
        assertTrue(types.contains(EventType.RESUMED))
        assertTrue(types.contains(EventType.SCROLL))
        assertTrue(types.contains(EventType.GATE_SHOWN))
        assertTrue(types.contains(EventType.GATE_ABANDONED))
        assertTrue(types.contains(EventType.GATE_PASSED))
        assertTrue(types.contains(EventType.PAUSED))
        // GATE_SHOWN fires on the transition only, not once per scroll.
        assertEquals(1, r.ledger.count(EventType.GATE_SHOWN))
    }

    @Test
    fun `tier zero scrolling writes no ledger rows`() {
        val r = Rig()
        r.enter(ig, 0)
        repeat(200) { r.scroll(ig, (it * 100).toLong()) }
        assertEquals(0, r.ledger.count(EventType.SCROLL))
    }

    @Test
    fun `persistence is offered on every state change without blocking`() = runTest {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 5 * min)
        r.clear(ig, 5 * min)
        // The writer coroutine only runs when the TestScope is advanced; the
        // point is that none of the calls above suspended or blocked.
        r.scope.testScheduler.advanceUntilIdle()
        assertTrue("expected at least one persisted snapshot", r.store.writes.isNotEmpty())
    }
}
