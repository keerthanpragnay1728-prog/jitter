package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-hour fixed window as the engine implements it.
 *
 * `FrictionEngineTest` covers the ladder; this file covers only the cycle
 * boundary, because the three cases the brief names are all about what the
 * clocks say rather than about what the user scrolled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CycleWindowEngineTest {

    private val ig = "com.instagram.android"
    private val min = 60_000L
    private val hour = 60 * min
    private val window = CycleResetPolicy.WINDOW_MS

    private class Rig(
        initial: EngineSnapshot? = null,
    ) {
        val store = FakeStore()
        val ledger = FakeLedger()
        val clock = SplitClock(wall = WALL_BASE, mono = 0, boot = 1)
        val scope = TestScope()
        val engine = FrictionEngine(
            initial = initial ?: EngineSnapshot(),
            store = store,
            ledger = ledger,
            wallClock = clock,
            monotonicClock = clock,
            bootIdProvider = clock,
            scope = scope,
        )

        /**
         * Offset of the wall clock from the monotonic one. Zero on an
         * untampered device that has not rebooted; [warpWallForward] and
         * [reboot] are the only things that change it, and [at] respects it so
         * that a later event does not silently undo them.
         */
        private var wallOffsetMs = 0L

        fun at(ms: Long) {
            clock.mono = ms
            clock.wall = WALL_BASE + ms + wallOffsetMs
        }

        fun enter(pkg: String, ms: Long) { at(ms); engine.onForegroundEnter(pkg, ms) }
        fun exit(pkg: String, ms: Long) { at(ms); engine.onForegroundExit(pkg, ms) }
        fun scroll(pkg: String, ms: Long) { at(ms); engine.onScroll(pkg, ms) }
        fun tick(ms: Long) { at(ms); engine.checkpoint(ms) }
        fun setHorizon(pkg: String, ms: Long) { engine.setHorizon(pkg, ms) }

        /** Wall clock alone moves. This is the bypass under test. */
        fun warpWallForward(ms: Long) {
            wallOffsetMs += ms
            clock.wall += ms
        }

        /**
         * Monotonic clock restarts at zero and BOOT_COUNT increments, while
         * the wall clock carries on and advances by [wallAdvanceMs] for the
         * time the device was off.
         */
        fun reboot(wallAdvanceMs: Long) {
            val wallNow = clock.wall + wallAdvanceMs
            clock.mono = 0
            clock.boot += 1
            wallOffsetMs = wallNow - WALL_BASE
            clock.wall = wallNow
        }

        fun snap(pkg: String): AppSnapshot = engine.snapshot().perApp.getValue(pkg)
        fun state() = engine.state.value

        companion object { const val WALL_BASE = 1_700_000_000_000L }
    }

    // --------------------------------------------------------- the horizon

    @Test
    fun `a widened horizon waits for the rollover and then lands`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 10 * min)
        r.setHorizon(ig, 60 * min)

        assertEquals("not yet", 25 * min, r.snap(ig).horizonMs)
        assertEquals("waiting", 60 * min, r.snap(ig).pendingHorizonMs)

        r.tick(window + min)

        assertEquals("promoted", 60 * min, r.snap(ig).horizonMs)
        assertEquals("nothing left waiting", 0L, r.snap(ig).pendingHorizonMs)
    }

    @Test
    fun `a narrowed horizon does not wait`() {
        val r = Rig()
        r.enter(ig, 0)
        r.setHorizon(ig, 10 * min)
        assertEquals(10 * min, r.snap(ig).horizonMs)
        assertEquals(0L, r.snap(ig).pendingHorizonMs)
    }

    @Test
    fun `a horizon change refunds nothing`() {
        // The rule the whole feature is under. Widening is a statement about
        // what a session is for, not a reset, and the accumulated total and
        // the tier it earned are untouched by it in both directions.
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 16 * min)
        val before = r.snap(ig)

        r.setHorizon(ig, 60 * min)
        assertEquals(before.accumulatedMs, r.snap(ig).accumulatedMs)
        assertEquals(before.tierIndex, r.snap(ig).tierIndex)

        r.setHorizon(ig, 10 * min)
        assertEquals(before.accumulatedMs, r.snap(ig).accumulatedMs)
        assertEquals(before.tierIndex, r.snap(ig).tierIndex)
    }

    @Test
    fun `a promoted horizon arrives at zero accumulated time`() {
        // Why the rollover is the right moment to promote at: the total the
        // new curve is read against starts over with it, so a wider horizon
        // can never arrive part way up a ramp it did not scale.
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 16 * min)
        r.setHorizon(ig, 60 * min)
        r.exit(ig, 16 * min)

        r.tick(window + min)

        assertEquals(60 * min, r.snap(ig).horizonMs)
        assertEquals(0L, r.snap(ig).accumulatedMs)
        assertEquals(0, r.snap(ig).tierIndex)
    }

    @Test
    fun `a pending widen survives a process death still pending`() {
        // It is state, not an in-memory intention. A restart that promoted it
        // would make killing the launcher the way to skip the wait.
        val r = Rig()
        r.enter(ig, 0)
        r.setHorizon(ig, 60 * min)
        r.exit(ig, min)
        val persisted = r.engine.snapshot()

        val r2 = Rig(initial = persisted)
        assertEquals("still not in force", 25 * min, r2.snap(ig).horizonMs)
        assertEquals("still waiting", 60 * min, r2.snap(ig).pendingHorizonMs)

        r2.enter(ig, 0)
        r2.tick(window + min)
        assertEquals(60 * min, r2.snap(ig).horizonMs)
    }

    @Test
    fun `an in force horizon survives a rollover it did not change at`() {
        val r = Rig()
        r.enter(ig, 0)
        r.setHorizon(ig, 10 * min)
        r.exit(ig, min)
        r.tick(window + min)
        assertEquals(10 * min, r.snap(ig).horizonMs)
    }

    // ------------------------------------------------- rollover on the tick

    @Test
    fun `rollover occurs during an open session that spans the deadline`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 30 * min)

        // Still the same session, five and a half hours in.
        r.tick(5 * hour + 30 * min)
        assertTrue(
            "should still be laddered before the deadline",
            r.snap(ig).accumulatedMs >= 5 * hour,
        )

        // The 15 s tick that first lands past the deadline. No foreground
        // entry happens here: the user never left the app.
        r.tick(window + 15_000)

        assertEquals(0, r.snap(ig).tierIndex)
        assertEquals(0, r.snap(ig).leasesTaken)
        // Only the 15 s since the rollover, not the six hours before it.
        assertTrue(
            "accumulated should have reset, was ${r.snap(ig).accumulatedMs}",
            r.snap(ig).accumulatedMs < 1 * min,
        )
    }

    @Test
    fun `a session spanning the deadline keeps accumulating in the new cycle`() {
        val r = Rig()
        r.enter(ig, 0)
        r.tick(window + 15_000)
        // Twenty more minutes in the same unbroken session.
        r.tick(window + 20 * min)

        val accumulated = r.snap(ig).accumulatedMs
        assertTrue(
            "expected about 20 min in the new cycle, got $accumulated",
            accumulated in (19 * min)..(21 * min),
        )
    }

    @Test
    fun `the countdown is clamped at zero rather than going negative`() {
        val r = Rig()
        r.enter(ig, 0)
        assertEquals(window, r.state().cycleRemainingMs)

        r.tick(2 * hour)
        assertEquals(window - 2 * hour, r.state().cycleRemainingMs)

        // A tick well past the deadline. The rollover re-anchors at that
        // instant, so the countdown reads a fresh full window, and at no
        // point is it negative.
        r.tick(window + 45 * min)
        assertTrue(r.state().cycleRemainingMs >= 0)
        assertEquals(window, r.state().cycleRemainingMs)
    }

    @Test
    fun `a rollover with nothing open leaves the cycle unanchored until the next entry`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 20 * min)
        r.exit(ig, 20 * min)

        r.tick(window + 1)
        assertEquals(0L, r.state().cycleAnchorWallMs)

        // The next foreground entry is what starts the new cycle.
        r.enter(ig, window + 3 * hour)
        assertEquals(Rig.WALL_BASE + window + 3 * hour, r.state().cycleAnchorWallMs)
        assertEquals(window, r.state().cycleRemainingMs)
    }

    // ------------------------------------------------------- the clock bypass

    @Test
    fun `system clock moved forward six hours with unchanged bootId does not reset`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 19 * min)
        val tier = r.snap(ig).tierIndex
        assertTrue("precondition: should be laddered, tier=$tier", tier >= 1)

        // Nineteen minutes of real time. The user now sets the system clock
        // forward six hours and comes back.
        r.at(19 * min)
        r.warpWallForward(6 * hour)
        r.tick(19 * min + 1_000)

        assertEquals("cycle must not have rolled", tier, r.snap(ig).tierIndex)
        assertTrue(
            "accumulated must survive the warp, was ${r.snap(ig).accumulatedMs}",
            r.snap(ig).accumulatedMs >= 19 * min,
        )
        assertTrue(
            "countdown must reflect real time, was ${r.state().cycleRemainingMs}",
            r.state().cycleRemainingMs > window - 25 * min,
        )
    }

    @Test
    fun `a clock warp does not reset the cycle on foreground entry either`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 19 * min)
        val tier = r.snap(ig).tierIndex
        r.exit(ig, 19 * min)

        r.at(20 * min)
        r.warpWallForward(7 * hour)
        r.enter(ig, 20 * min)

        assertEquals(tier, r.snap(ig).tierIndex)
    }

    // ------------------------------------------------------------- the reboot

    @Test
    fun `reboot mid-cycle preserves the anchor`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 19 * min)
        val tier = r.snap(ig).tierIndex
        val anchorWall = r.state().cycleAnchorWallMs
        r.exit(ig, 19 * min)

        // Two hours of wall time pass across a reboot. elapsedRealtime
        // restarts at zero and BOOT_COUNT increments; the engine survives in
        // this test rather than being rebuilt, which isolates the clock
        // arithmetic from the reconciler's own path.
        r.reboot(wallAdvanceMs = 2 * hour)
        r.enter(ig, 90_000)

        assertEquals("anchor must not move", anchorWall, r.state().cycleAnchorWallMs)
        assertEquals("cycle must not have rolled", tier, r.snap(ig).tierIndex)
        assertTrue(
            "accumulated must survive the reboot, was ${r.snap(ig).accumulatedMs}",
            r.snap(ig).accumulatedMs >= 19 * min,
        )
    }

    @Test
    fun `a cycle that came due while the device was off rolls on the next entry`() {
        val r = Rig()
        r.enter(ig, 0)
        r.scroll(ig, 19 * min)
        r.exit(ig, 19 * min)

        // Seven hours of wall time across a reboot: genuinely past the
        // deadline, and the wall clock is the only witness there is.
        r.reboot(wallAdvanceMs = 7 * hour)
        r.enter(ig, 60_000)

        assertEquals(0, r.snap(ig).tierIndex)
    }

    // -------------------------------------------------------- rehydration

    @Test
    fun `the anchor stamp survives a snapshot round trip`() {
        val r = Rig()
        r.enter(ig, 0)
        r.tick(31 * min)

        val persisted = r.engine.snapshot()
        assertEquals(Rig.WALL_BASE, persisted.cycleAnchorWallMs)
        assertEquals(0L, persisted.cycleAnchorElapsedMs)
        assertEquals(1, persisted.cycleAnchorBootId)

        // Rebuild from the snapshot, as the service does at connect, and
        // check the deadline is still measured from the original anchor.
        val r2 = Rig(initial = persisted)
        r2.at(31 * min)
        r2.tick(31 * min)
        assertEquals(window - 31 * min, r2.state().cycleRemainingMs)
    }
}
