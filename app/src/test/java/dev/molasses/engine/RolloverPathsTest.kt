package dev.molasses.engine

import dev.molasses.core.friction.CycleRollover
import dev.molasses.core.friction.HorizonPolicy
import dev.molasses.core.functionBody
import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.repoFile
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's rollover and the reconciler's rollover on connect end in the
 * same per-app state, because both call [CycleRollover.carryOver].
 *
 * The engine half is driven for real. The reconciler is compiled by nothing
 * here, so its half is read as text.
 */
class RolloverPathsTest {

    private val pkg = "com.instagram.android"
    private val min = 60_000L
    private val hour = 60 * min

    private val spent = AppSnapshot(
        pkg = pkg,
        accumulatedMs = 40 * min,
        tierIndex = 8,
        leasesTaken = 3,
        leaseUntilAccumulatedMs = 30 * min,
        penaltyMs = 9 * min,
        horizonMs = 25 * min,
        pendingHorizonMs = 40 * min,
        penaltyAnchorMs = 40 * min,
    )

    @Test
    fun `the engine rollover lands the same state carryOver gives`() {
        val clock = MutableClock(1_000 + 7 * hour)
        val e = FrictionEngine(
            initial = EngineSnapshot(
                perApp = mapOf(pkg to spent),
                cycleAnchorWallMs = 1_000,
                cycleAnchorElapsedMs = 1_000,
                cycleAnchorBootId = 1,
            ),
            store = FakeStore(),
            roll = { 0f },
            ledger = FakeLedger(),
            wallClock = clock,
            monotonicClock = clock,
            bootIdProvider = { 1 },
            scope = TestScope(),
        )
        e.onForegroundEnter(pkg, clock.now)
        val rolled = e.snapshot().perApp.getValue(pkg)
        val expected = CycleRollover.carryOver(spent)

        assertEquals(40 * min, rolled.horizonMs)
        assertEquals(HorizonPolicy.NONE, rolled.pendingHorizonMs)
        assertEquals(expected.copy(penaltyAnchorMs = null), rolled.copy(penaltyAnchorMs = null))
    }

    @Test
    fun `the reconciler's rollover on connect carries apps over instead of dropping them`() {
        val text = repoFile("app/src/main/java/dev/molasses/monitor/ForegroundReconciler.kt").readText()
        val body = functionBody(text, "private fun maybeRollCycleOnConnect(")
        assertTrue("the connect rollover must call CycleRollover.carryOver", body.contains("perApp = CycleRollover.carryOver(snapshot.perApp)"))
        assertFalse("emptying the map drops a pending widen", body.contains("perApp = emptyMap()"))
    }

    @Test
    fun `the engine rollover calls the same function`() {
        val text = repoFile("app/src/main/java/dev/molasses/engine/FrictionEngine.kt").readText()
        val body = functionBody(text, "private fun maybeRollCycle(")
        assertTrue(body.contains("CycleRollover.carryOver("))
    }
}
