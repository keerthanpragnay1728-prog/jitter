package dev.molasses.core.lease

import dev.molasses.core.lease.GatePolicy.GateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePolicyTest {

    @Test
    fun `the countdown is the mode at every tier`() {
        assertEquals(GateMode.COUNTDOWN, GatePolicy.modeFor(GateMode.COUNTDOWN, terminal = false))
        assertEquals(GateMode.COUNTDOWN, GatePolicy.modeFor(GateMode.COUNTDOWN, terminal = true))
    }

    @Test
    fun `walking applies at the terminal tier only`() {
        assertEquals(GateMode.COUNTDOWN, GatePolicy.modeFor(GateMode.WALK, terminal = false))
        assertEquals(GateMode.WALK, GatePolicy.modeFor(GateMode.WALK, terminal = true))
    }

    @Test
    fun `the typing escape hatch survives the feature becoming optional`() {
        // It is an accessibility requirement, not a preference. Collapsing
        // the setting to a switch would have deleted it.
        assertEquals(GateMode.TYPING_ONLY, GatePolicy.modeFor(GateMode.TYPING_ONLY, terminal = true))
        assertEquals(GateMode.COUNTDOWN, GatePolicy.modeFor(GateMode.TYPING_ONLY, terminal = false))
    }

    @Test
    fun `every mode is reachable from the setting`() {
        for (mode in GateMode.entries) {
            assertEquals(mode, GatePolicy.modeFor(mode, terminal = true))
        }
    }
}

class LeaseLadderTest {

    @Test
    fun `the offered durations are five ten and fifteen minutes`() {
        assertEquals(listOf(300_000L, 600_000L, 900_000L), LeaseLadder.OFFERED_MS)
    }

    @Test
    fun `the ladder ascends`() {
        assertEquals(LeaseLadder.OFFERED_MS.sorted(), LeaseLadder.OFFERED_MS)
        assertEquals(LeaseLadder.OFFERED_MS.distinct(), LeaseLadder.OFFERED_MS)
    }

    @Test
    fun `there is no zero and no unlimited`() {
        assertTrue(LeaseLadder.OFFERED_MS.all { it > 0L })
        assertEquals(LeaseLadder.MIN_MS, LeaseLadder.OFFERED_MS.first())
        assertEquals(LeaseLadder.MAX_MS, LeaseLadder.OFFERED_MS.last())
    }

    @Test
    fun `only the offered durations are offered`() {
        assertTrue(LeaseLadder.isOffered(600_000L))
        assertTrue(!LeaseLadder.isOffered(0L))
        assertTrue(!LeaseLadder.isOffered(60L * 60 * 1000))
    }

    @Test
    fun `the longest lease is shorter than a reboot is quick`() {
        // The whole reason a lease may drop the wall clock. See LeaseManager.
        assertTrue(LeaseLadder.MAX_MS <= 15L * 60 * 1000)
    }
}

class GateCountdownTest {

    @Test
    fun `the first gate of a cycle is eight seconds`() {
        assertEquals(8_000L, GateCountdown.durationMs(0))
    }

    @Test
    fun `each lease taken lengthens the next gate by four seconds`() {
        assertEquals(12_000L, GateCountdown.durationMs(1))
        assertEquals(16_000L, GateCountdown.durationMs(2))
        assertEquals(20_000L, GateCountdown.durationMs(3))
        assertEquals(24_000L, GateCountdown.durationMs(4))
    }

    @Test
    fun `the countdown caps at thirty seconds`() {
        assertEquals(30_000L, GateCountdown.durationMs(6))
        assertEquals(30_000L, GateCountdown.durationMs(100))
        assertEquals(30_000L, GateCountdown.durationMs(Int.MAX_VALUE))
    }

    @Test
    fun `it never shortens`() {
        var previous = 0L
        for (n in 0..50) {
            val d = GateCountdown.durationMs(n)
            assertTrue("shortened at $n", d >= previous)
            previous = d
        }
    }

    @Test
    fun `a corrupt negative count does not buy a shorter gate`() {
        assertEquals(GateCountdown.durationMs(0), GateCountdown.durationMs(-1))
        assertEquals(GateCountdown.durationMs(0), GateCountdown.durationMs(Int.MIN_VALUE))
    }
}

class LaunchGateTest {

    private fun decide(
        isTarget: Boolean = true,
        leaseRemainingMs: Long = 0L,
        sensitiveForeground: Boolean = false,
        locked: Boolean = false,
        paused: Boolean = false,
        leasesTakenThisCycle: Int = 0,
        configuredMode: GateMode = GateMode.COUNTDOWN,
        terminal: Boolean = false,
    ) = LaunchGate.decide(
        isTarget, leaseRemainingMs, sensitiveForeground, locked, paused,
        leasesTakenThisCycle, configuredMode, terminal,
    )

    @Test
    fun `entering a target with no lease is intercepted`() {
        val d = decide()
        assertTrue(d is LaunchGate.Decision.Intercept)
        d as LaunchGate.Decision.Intercept
        assertEquals(8_000L, d.countdownMs)
        assertEquals(GateMode.COUNTDOWN, d.mode)
        assertEquals(false, d.expired)
    }

    @Test
    fun `a package that is not a target passes`() {
        assertTrue(decide(isTarget = false) is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a live lease passes`() {
        assertTrue(decide(leaseRemainingMs = 1L) is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a sensitive package is never gated`() {
        // An overlay sets FLAG_WINDOW_IS_OBSCURED and a payment app is
        // entitled to refuse the transaction.
        assertTrue(decide(sensitiveForeground = true) is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a sensitive package outranks everything else`() {
        val d = decide(sensitiveForeground = true, locked = true, paused = false, terminal = true)
        assertTrue(d is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a locked package is left to the lock flash`() {
        assertTrue(decide(locked = true) is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a pause suspends the gate`() {
        // The opposite answer from LockEnforcement, where a pause does not
        // outrank a lock. A gate is a nudge; a lock is a commitment.
        assertTrue(decide(paused = true) is LaunchGate.Decision.Pass)
    }

    @Test
    fun `a returning gate is longer and says the lease expired`() {
        val d = decide(leasesTakenThisCycle = 2) as LaunchGate.Decision.Intercept
        assertEquals(16_000L, d.countdownMs)
        assertTrue(d.expired)
    }

    @Test
    fun `a first gate does not claim a lease expired`() {
        val d = decide(leasesTakenThisCycle = 0) as LaunchGate.Decision.Intercept
        assertEquals(false, d.expired)
    }

    @Test
    fun `the walking gate reaches the user only at the terminal tier`() {
        val below = decide(configuredMode = GateMode.WALK, terminal = false)
        assertEquals(GateMode.COUNTDOWN, (below as LaunchGate.Decision.Intercept).mode)
        val at = decide(configuredMode = GateMode.WALK, terminal = true)
        assertEquals(GateMode.WALK, (at as LaunchGate.Decision.Intercept).mode)
    }

    @Test
    fun `every pass carries a reason`() {
        val passes = listOf(
            decide(isTarget = false),
            decide(sensitiveForeground = true),
            decide(locked = true),
            decide(paused = true),
            decide(leaseRemainingMs = 1L),
        )
        for (p in passes) {
            assertTrue((p as LaunchGate.Decision.Pass).why.isNotEmpty())
        }
    }
}

/**
 * A gate that did not draw must not count as shown.
 *
 * The invariant this pins is the one that cost every stall on hardware: the
 * caller committed as soon as it dispatched, so a single failed addView
 * disabled the gate and all the friction behind it, permanently, with a
 * healthy service and a correct ledger.
 */
class GateOutcomeTest {

    private val intercept = LaunchGate.Decision.Intercept(
        countdownMs = 8_000L,
        mode = GateMode.COUNTDOWN,
        expired = false,
    )
    private val pass = LaunchGate.Decision.Pass("lease active")

    @Test
    fun `a gate that attached suppresses friction`() {
        val o = LaunchGate.outcome(intercept, attached = true)
        assertEquals(LaunchGate.Outcome.Shown, o)
        assertTrue(o.suppressesFriction)
    }

    @Test
    fun `a gate that did not attach does not suppress friction`() {
        // The whole point. The app is on screen and scrollable, so the stall
        // is the only friction left and it has to run.
        val o = LaunchGate.outcome(intercept, attached = false)
        assertTrue(o is LaunchGate.Outcome.NotDrawn)
        assertFalse(o.suppressesFriction)
    }

    @Test
    fun `a failure carries a reason`() {
        val o = LaunchGate.outcome(intercept, attached = false) as LaunchGate.Outcome.NotDrawn
        assertTrue(o.why.isNotEmpty())
    }

    @Test
    fun `no gate owed suppresses nothing, attached or not`() {
        // A pass is not a gate. Reading attached here would make a stale
        // isShowing from some other package decide this one's friction.
        assertEquals(LaunchGate.Outcome.NotOwed, LaunchGate.outcome(pass, attached = false))
        assertEquals(LaunchGate.Outcome.NotOwed, LaunchGate.outcome(pass, attached = true))
        assertFalse(LaunchGate.outcome(pass, attached = true).suppressesFriction)
    }

    @Test
    fun `only a drawn gate ever suppresses friction`() {
        // Stated over the whole space rather than case by case, so a fourth
        // outcome added later cannot quietly default to suppressing.
        val all = listOf(
            LaunchGate.outcome(intercept, attached = true),
            LaunchGate.outcome(intercept, attached = false),
            LaunchGate.outcome(pass, attached = true),
            LaunchGate.outcome(pass, attached = false),
        )
        assertEquals(1, all.count { it.suppressesFriction })
        assertEquals(LaunchGate.Outcome.Shown, all.single { it.suppressesFriction })
    }
}
