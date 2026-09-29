package dev.molasses.engine

import dev.molasses.core.model.EngineSnapshot
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every public engine method calls its confinement hook first.
 *
 * The hook is how the owner checks the engine is only ever called from one
 * thread; a method that skips it is a method a second thread can reach with
 * nothing saying so. This drives each one and counts.
 */
class EngineConfinementTest {

    private val pkg = "com.instagram.android"

    private fun engine(confinement: () -> Unit): FrictionEngine {
        val clock = MutableClock(1_000)
        return FrictionEngine(
            initial = EngineSnapshot(),
            store = FakeStore(),
            roll = { 0f },
            ledger = FakeLedger(),
            wallClock = clock,
            monotonicClock = clock,
            bootIdProvider = { 1 },
            scope = TestScope(),
            confinement = confinement,
        )
    }

    @Test
    fun `every public method passes through the confinement hook`() {
        var calls = 0
        val e = engine { calls++ }
        val methods: List<Pair<String, () -> Unit>> = listOf(
            "onForegroundEnter" to { e.onForegroundEnter(pkg, 2_000) },
            "onScroll" to { e.onScroll(pkg, 3_000) },
            "isTerminal" to { e.isTerminal(pkg, 3_000) },
            "leasesTakenThisCycle" to { e.leasesTakenThisCycle(pkg) },
            "setHorizon" to { e.setHorizon(pkg, 10 * 60_000L) },
            "onLeaseGranted" to { e.onLeaseGranted(pkg, 5 * 60_000L, 4_000) },
            "checkpoint" to { e.checkpoint(5_000) },
            "snapshot" to { e.snapshot() },
            "onForegroundExit" to { e.onForegroundExit(pkg, 6_000) },
        )
        for ((name, call) in methods) {
            val before = calls
            call()
            assertTrue("$name did not call the confinement hook", calls > before)
        }
    }

    @Test
    fun `a hook that refuses stops the call before it touches state`() {
        var allow = true
        val e = engine { check(allow) { "off thread" } }
        e.onForegroundEnter(pkg, 2_000)
        allow = false
        try {
            e.onScroll(pkg, 3_000)
            fail("onScroll ran past a refusing hook")
        } catch (expected: IllegalStateException) {
            assertEquals("off thread", expected.message)
        }
    }
}
