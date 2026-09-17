package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.model.FrictionDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private class Rig(
        initial: EngineSnapshot = EngineSnapshot(),
        roll: () -> Float = { 0f },
    ) {
        val store = FakeStore()
        val ledger = FakeLedger()
        val clock = SplitClock(wall = WALL_BASE, mono = 0)
        val scope = TestScope()
        val engine = FrictionEngine(
            initial = initial,
            store = store,
            // Defaults to always stalling. The Bernoulli dimension is tested
            // in FrictionCurveTest; a random miss here would make every
            // precedence and accounting test flaky for an unrelated reason.
            roll = roll,
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
        fun scroll(pkg: String, ms: Long): FrictionDecision { at(ms); return engine.onScroll(pkg, ms) }
        fun lease(pkg: String, ms: Long, durationMs: Long = 5 * 60_000L) {
            at(ms); engine.onLeaseGranted(pkg, durationMs, ms)
        }
        fun snap(pkg: String): AppSnapshot = engine.snapshot().perApp.getValue(pkg)

        companion object { const val WALL_BASE = 1_700_000_000_000L }
    }

    @Test
    fun `under five minutes nothing happens`() {
        val r = Rig()
        r.enter(ig, 0)
        // Tier 0 and under the curve's onset. Nothing here is about a gate
        // any more: the gate fired at the launch, before the first of these
        // scrolls, and the engine was never told about it.
        assertEquals(FrictionDecision.NONE, r.scroll(ig, 1 * min))
        assertEquals(FrictionDecision.NONE, r.scroll(ig, 4 * min + 59_000))
    }

    @Test
    fun `a scroll earns a stall and never a gate`() {
        // The engine stopped deciding when to interrupt. It decides what a
        // scroll costs; whether the user may be here at all is the lease
        // system's question and is asked at the launch.
        val r = Rig()
        r.enter(ig, 0)
        r.lease(ig, 0, 5 * min)

        assertEquals("before the curve's onset", 0L, r.scroll(ig, 5 * min).stallMs)
        assertTrue("past the onset", r.scroll(ig, 12 * min).stalls)
    }

    @Test
    fun `overstaying a lease adds friction rather than removing it`() {
        // The invariant the ratchet exists to create, stated directly. It
        // used to be about ignoring a checkpoint; the mark it measures
        // against is now the lease that was taken, and nothing else changed.
        val overstayed = Rig()
        overstayed.enter(ig, 0)
        overstayed.lease(ig, 0, 5 * min)
        overstayed.scroll(ig, 15 * min)

        // Staying inside means taking another lease each time one runs out,
        // not just the first. A version of this that leased only once and
        // then sat for ten minutes accrues exactly the same penalty as the
        // overstaying rig and makes the test assert nothing.
        val paid = Rig()
        paid.enter(ig, 0)
        paid.lease(ig, 0, 5 * min)
        paid.scroll(ig, 5 * min)
        paid.lease(ig, 5 * min, 5 * min)
        paid.scroll(ig, 10 * min)
        paid.lease(ig, 10 * min, 5 * min)
        paid.scroll(ig, 15 * min)

        assertTrue(
            "overstaying must cost more than leasing: overstayed=" +
                "${overstayed.snap(ig).penaltyMs} paid=${paid.snap(ig).penaltyMs}",
            overstayed.snap(ig).penaltyMs > paid.snap(ig).penaltyMs,
        )
        assertEquals("leasing every span accrues nothing", 0L, paid.snap(ig).penaltyMs)
    }

    @Test
    fun `no lease taken means nothing is overdue`() {
        // The guard that keeps the ratchet off users it was never meant to
        // charge. Anchoring to a mark of zero with no lease behind it would
        // make every app on a device with the gate suppressed overdue from
        // its first millisecond.
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 20 * min)
        assertEquals(0L, r.snap(ig).penaltyMs)
    }

    @Test
    fun `the penalty is a ratchet and a new lease never refunds it`() {
        val r = Rig()
        r.enter(ig, 0)
        r.lease(ig, 0, 5 * min)
        r.scroll(ig, 12 * min)
        val earned = r.snap(ig).penaltyMs
        assertTrue("penalty should have accrued, got $earned", earned > 0)

        r.lease(ig, 12 * min, 15 * min)
        r.scroll(ig, 13 * min)
        assertEquals("a new lease must not refund the penalty", earned, r.snap(ig).penaltyMs)
    }

    @Test
    fun `the penalty is never folded into accumulated time`() {
        // The ledger reports what the user actually spent. The penalty is a
        // separate number and is shown as one.
        val r = Rig()
        r.enter(ig, 0)
        r.lease(ig, 0, 5 * min)
        r.scroll(ig, 15 * min)
        val snap = r.snap(ig)
        assertTrue("penalty should have accrued", snap.penaltyMs > 0)
        assertTrue(
            "accumulated ${snap.accumulatedMs} should be true time, about 15m",
            snap.accumulatedMs in (15 * min - 2_000)..(15 * min + 2_000),
        )
    }

    @Test
    fun `tiers climb with true time and stalls never fall`() {
        // What the walk pins is the structure, which is the part that must
        // not drift: a tier per five minute boundary on true time, and a
        // stall that never decreases, including across a lease.
        val r = Rig()
        r.enter(ig, 0)

        var previousStall = 0L
        fun step(atMs: Long, tier: Int) {
            val d = r.scroll(ig, atMs)
            assertEquals("tier at ${atMs / min}m", tier, r.snap(ig).tierIndex)
            assertTrue(
                "stall fell at tier $tier: $previousStall to ${d.stallMs}",
                d.stallMs >= previousStall,
            )
            previousStall = d.stallMs
            r.lease(ig, atMs, 5 * min)
            val after = r.scroll(ig, atMs + 1_000)
            assertTrue("stall must survive the lease", after.stallMs >= previousStall)
        }

        step(5 * min, 1)
        step(10 * min, 2)
        step(15 * min, 3)
        step(20 * min, 4)
        // Terminal: the index keeps climbing, indefinitely. No silent cap.
        step(25 * min, 5)
        step(30 * min, 6)
        step(120 * min, 24)

        // The default horizon sits an eighth into the taper, so its ceiling
        // is 4750 rather than 5000. Read from the curve rather than written
        // as a literal, so retuning the taper does not make this a test of a
        // constant instead of the invariant it is about.
        assertEquals(
            "terminal stall",
            FrictionCurve.terminalStallMs(FrictionCurve.DEFAULT_HORIZON_MS).toLong(),
            previousStall,
        )
    }

    @Test
    fun `the curve onset is the default horizon's forty percent`() {
        // Ten minutes on the default twenty five minute horizon.
        // Isolated so a change to the horizon shows up here rather than as a
        // surprise somewhere downstream.
        val r = Rig()
        r.enter(ig, 0)
        // A lease covering the whole span, so no penalty accrues and this
        // measures the curve rather than the ratchet.
        r.lease(ig, 0, 15 * min)

        assertEquals(10 * min, FrictionCurve.ONSET_MS)
        assertEquals(0L, r.scroll(ig, 9 * min + 30_000).stallMs)
        assertTrue(r.scroll(ig, 10 * min + 30_000).stalls)
    }

    @Test
    fun `overstaying a lease pulls the onset forward`() {
        // The other side of the same coin, asserted rather than left
        // implicit. Five minutes leased and three overstayed is eleven
        // minutes of effective time at eight of real time, which is past an
        // onset that real time has not reached.
        val overstayed = Rig()
        overstayed.enter(ig, 0)
        overstayed.lease(ig, 0, 5 * min)
        assertTrue(
            "three minutes overdue should reach the onset early",
            overstayed.scroll(ig, 8 * min).stalls,
        )

        // Without the overstay, the same real time earns nothing.
        val covered = Rig()
        covered.enter(ig, 0)
        covered.lease(ig, 0, 15 * min)
        assertEquals(0L, covered.scroll(ig, 8 * min).stallMs)
    }

    @Test
    fun `no stall is ever commanded below the floor`() {
        val r = Rig()
        r.enter(ig, 0)
        for (m in listOf(6, 7, 8, 9, 10, 12, 15, 20, 25, 40)) {
            val stall = r.scroll(ig, m.toLong() * min).stallMs
            if (stall > 0) {
                assertTrue(
                    "at ${m}m the stall was ${stall}ms, below the floor",
                    stall >= FrictionCurve.DEFAULT_FLOOR_MS,
                )
            }
        }
    }

    @Test
    fun `a probability miss withholds the stall and nothing else`() {
        // A Bernoulli miss is not a free minute. The curve has been read and
        // the ratchet has run either way; only the sink is withheld.
        val r = Rig(roll = { 1f })
        r.enter(ig, 0)
        r.lease(ig, 0, 5 * min)
        val d = r.scroll(ig, 10 * min)
        assertEquals("the roll missed, so no stall", 0L, d.stallMs)
        assertEquals("tier is not subject to chance", 2, r.snap(ig).tierIndex)
        assertTrue("the ratchet is not subject to chance", r.snap(ig).penaltyMs > 0)
    }

    // ------------------------------------------------------- the invariant

    @Test
    fun `taking a lease leaves accumulated time untouched`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 12 * min)
        val before = r.snap(ig).accumulatedMs
        assertTrue("time must have accumulated at all, got $before", before >= 12 * min)

        r.lease(ig, 12 * min, 15 * min)

        assertEquals("a lease must not refund time", before, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `taking a lease never lowers the stall duration`() {
        // The sentence that makes a lease safe to sell: it buys time, never
        // friction. A user who takes fifteen minutes scrolls through exactly
        // the stall they would have had.
        val r = Rig()
        r.enter(ig, 0)
        r.lease(ig, 0, 15 * min)
        r.scroll(ig, 15 * min)
        val before = r.scroll(ig, 15 * min + 1_000).stallMs
        assertTrue("a stall should be commanded at 15 min, got $before", before > 0)

        // A duplicated or late grant must not walk it back. Relational rather
        // than against a hardcoded 5000, so retuning cannot quietly turn this
        // into a test of the constant instead of the invariant.
        r.lease(ig, 15 * min + 2_000, 15 * min)
        r.lease(ig, 15 * min + 2_500, 15 * min)
        assertTrue(
            "a lease lowered the stall",
            r.scroll(ig, 15 * min + 3_000).stallMs >= before,
        )
    }

    @Test
    fun `taking a lease never rewinds tierIndex`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 20 * min)
        assertEquals(4, r.snap(ig).tierIndex)

        r.lease(ig, 20 * min, 15 * min)
        r.lease(ig, 20 * min, 5 * min)

        assertEquals(4, r.snap(ig).tierIndex)
    }

    @Test
    fun `taking a lease writes only the lease mark and the count`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 10 * min)
        val before = r.snap(ig)

        r.lease(ig, 10 * min, 5 * min)
        val after = r.snap(ig)

        // The two fields a grant is allowed to move.
        assertNotEquals(before.leaseUntilAccumulatedMs, after.leaseUntilAccumulatedMs)
        assertEquals(before.leasesTaken + 1, after.leasesTaken)
        // Everything else frozen. Compared field-by-field rather than with
        // copy(), so a field added to AppSnapshot later fails this test
        // instead of being silently carried through.
        assertEquals(before.pkg, after.pkg)
        assertEquals(before.accumulatedMs, after.accumulatedMs)
        assertEquals(before.tierIndex, after.tierIndex)
        assertEquals(before.penaltyMs, after.penaltyMs)
    }

    @Test
    fun `the lease mark is now plus the duration, in accumulated time`() {
        val r = Rig()
        r.enter(ig, 0)
        // Thirteen minutes in, with no lease so far.
        r.scroll(ig, 13 * min)
        r.lease(ig, 13 * min, 5 * min)
        // Eighteen minutes of accumulated time, not the next tier boundary.
        // The old field advanced to a fixed grid; a lease is a span the user
        // picked and it starts where they are.
        assertEquals(18 * min, r.snap(ig).leaseUntilAccumulatedMs)
        assertEquals("nothing overdue inside it", 0L, r.snap(ig).penaltyMs)
        r.scroll(ig, 17 * min)
        assertEquals("still nothing overdue", 0L, r.snap(ig).penaltyMs)
        r.scroll(ig, 19 * min)
        assertTrue("a minute past it is overdue", r.snap(ig).penaltyMs > 0)
    }

    @Test
    fun `a shorter lease cannot pull the mark backwards`() {
        val r = Rig()
        r.enter(ig, 0)
        r.lease(ig, 0, 15 * min)
        r.lease(ig, 0, 5 * min)
        assertEquals(15 * min, r.snap(ig).leaseUntilAccumulatedMs)
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
        r.scroll(ig, 66 * min)
        assertTrue(r.snap(ig).accumulatedMs >= 6 * min)
    }

    @Test
    fun `re-entering an already open app does not restart the session`() {
        val r = Rig()
        r.enter(ig, 0)
        // TYPE_WINDOW_STATE_CHANGED fires repeatedly inside one app.
        repeat(20) { r.enter(ig, (it * 10_000).toLong()) }
        r.scroll(ig, 5 * min)
        assertEquals(5 * min, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `a scroll with no preceding window state change still accumulates`() {
        val r = Rig()
        assertEquals(FrictionDecision.NONE, r.scroll(ig, 0))
        r.scroll(ig, 5 * min)
        assertEquals(5 * min, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `switching apps closes the previous session and ladders are independent`() {
        val r = Rig()
        r.enter(ig, 0)
        r.enter(yt, 3 * min)
        assertEquals(3 * min, r.snap(ig).accumulatedMs)
        assertEquals(0L, r.snap(yt).accumulatedMs)

        assertEquals(FrictionDecision.NONE, r.scroll(yt, 6 * min))
        assertTrue(r.scroll(yt, 16 * min).stalls)
        // Instagram is untouched at 3 minutes.
        assertEquals(3 * min, r.snap(ig).accumulatedMs)
    }

    @Test
    fun `a backwards monotonic timestamp credits zero rather than negative time`() {
        val r = Rig()
        r.enter(ig, 10 * min)
        // Should be impossible; a boot-reset value leaking past the reconciler
        // must not produce negative accumulation.
        assertEquals(FrictionDecision.NONE, r.scroll(ig, 1 * min))
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
        assertEquals(FrictionDecision.NONE, r.scroll(ig, 15 * min + 6 * hour + 1 * min))
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
        // A five minute lease taken at the start and then overstayed by
        // seven, so there is a penalty to survive the restart at all.
        r.lease(ig, 0, 5 * min)
        r.scroll(ig, 12 * min)
        r.exit(ig, 12 * min)
        val persisted = r.engine.snapshot()

        val r2 = Rig(persisted)
        r2.enter(ig, 0)
        // Picks up mid-tier-2 with a lease already taken. The stall is above
        // the true-time tier 2 value because the penalty carried through the
        // snapshot.
        assertTrue(
            "penalty must survive the restart",
            r2.snap(ig).penaltyMs > 0,
        )
        assertTrue(r2.scroll(ig, 1_000).stallMs >= 3_000L)
        assertEquals(2, r2.snap(ig).tierIndex)
        assertEquals(1, r2.snap(ig).leasesTaken)
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
    fun `ledger records the session and the lease`() = runTest {
        val r = Rig()
        r.enter(ig, 0)
        // Past the curve's onset, or the scroll earns nothing and writes no
        // row. A SCROLL row is written when a stall is actually commanded,
        // which is the only moment it describes anything.
        r.scroll(ig, 12 * min)
        r.lease(ig, 12 * min + 3_000, 5 * min)
        r.exit(ig, 13 * min)

        val types = r.ledger.typesFor(ig)
        assertTrue(types.contains(EventType.RESUMED))
        assertTrue(types.contains(EventType.SCROLL))
        assertTrue(types.contains(EventType.LEASE_TAKEN))
        assertTrue(types.contains(EventType.PAUSED))
        // One row per lease, not one per scroll after it.
        assertEquals(1, r.ledger.count(EventType.LEASE_TAKEN))
    }

    @Test
    fun `the gate rows are the overlay's and never the engine's`() = runTest {
        // LEASE_GATE_SHOWN and LEASE_DECLINED are written by the overlay,
        // which is the only thing that knows a window went up. The engine is
        // never told a gate was shown, so it must not claim one was.
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 20 * min)
        r.lease(ig, 20 * min, 5 * min)
        val types = r.ledger.typesFor(ig)
        assertTrue(EventType.LEASE_GATE_SHOWN !in types)
        assertTrue(EventType.LEASE_DECLINED !in types)
        assertTrue(EventType.GATE_SHOWN !in types)
    }

    @Test
    fun `scrolling below the first tier writes no ledger rows`() {
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
        r.lease(ig, 5 * min, 5 * min)
        // The writer coroutine only runs when the TestScope is advanced; the
        // point is that none of the calls above suspended or blocked.
        r.scope.testScheduler.advanceUntilIdle()
        assertTrue("expected at least one persisted snapshot", r.store.writes.isNotEmpty())
    }
}
