package dev.molasses.core.lease

import dev.molasses.core.time.StampedInstant
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's lease registry agrees with the engine at the instant it is read.
 *
 * ## The bug this exists for
 * `grantLease` applied a lease to two places at two speeds: to the engine
 * synchronously, and to the lease registry through a DataStore write and a
 * flow emission. `maybeLaunchGate` reads both in one breath, so for as long as
 * the write was in flight it saw a package with no lease and one more lease
 * taken than before, and put a second gate up one escalation step higher.
 *
 * That was not a race that sometimes lost. The gate window is focusable, so
 * taking it down hands focus back to the target app, which emits
 * `WINDOW_STATE_CHANGED`, which the router turns into `EnterTarget`, which
 * asks for a gate again. It arrives a frame or two after the dismiss, and a
 * disk write cannot land in that time. Every lease cost two rungs instead of
 * one, so the countdown hit its 30 s cap on the third expiry of a cycle rather
 * than the sixth, and the ledger showed it as ordinary escalation because the
 * second gate really did end in a second lease.
 *
 * ## Why the invariant is asserted twice
 * The first two tests drive the mechanism: grant into a [LeaseManager] and ask
 * [LaunchGate] the question the service asks, with no store anywhere. They say
 * the pieces can answer correctly.
 *
 * The last two read `MolassesAccessibilityService.kt` as text, because the
 * defect was never in the pieces. It was one assignment the service did not
 * make, in a file no tool in this environment compiles. `FontScaleWiringTest`
 * exists for the same reason and reads the same way.
 *
 * ## Both directions
 * A grant and a rollover are the same defect pointing opposite ways. A grant
 * the registry has not seen yet costs an extra gate and an extra rung; a
 * rollover it has not seen yet leaves a lease the new cycle never issued
 * standing, and `LaunchGate` passes on it, so the first gate of the cycle is
 * skipped. The second is the worse half, because it is relief rather than
 * friction. Both are asserted here.
 *
 * Pure. Unit-tested behaviour lives in `LeaseManagerTest`; this is about the
 * seam between that and the gate.
 */
class LeaseGrantVisibilityTest {

    private val pkg = "com.instagram.android"

    private fun at(elapsedMs: Long) = StampedInstant(
        wallMs = 1_700_000_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        bootId = 7,
    )

    /** The question `maybeLaunchGate` asks, with the two fields it reads. */
    private fun decide(leases: LeaseManager, now: StampedInstant, leasesTaken: Int) =
        LaunchGate.decide(
            isTarget = true,
            leaseRemainingMs = leases.remainingMs(pkg, now),
            sensitiveForeground = false,
            locked = false,
            paused = false,
            leasesTakenThisCycle = leasesTaken,
            configuredMode = GatePolicy.GateMode.COUNTDOWN,
            terminal = false,
        )

    @Test
    fun `the gate fires before a lease and passes immediately after one`() {
        val now = at(1_000)
        var leases = LeaseManager()

        assertTrue(
            "a package with no lease is owed a gate",
            decide(leases, now, leasesTaken = 0) is LaunchGate.Decision.Intercept,
        )

        leases = leases.grant(pkg, now, 15 * 60_000L, accumulatedMs = 0L)

        // The count has gone up, as the engine's has, and that must not be
        // enough on its own to earn a second gate.
        val after = decide(leases, now, leasesTaken = 1)
        assertTrue(
            "a lease granted in memory must pass the very next decision, got $after",
            after is LaunchGate.Decision.Pass,
        )
    }

    @Test
    fun `without the in-memory grant the second gate is one rung higher`() {
        // The shape of the bug, stated so the fix has something to be a fix
        // of. This is what the service did when it left `leases` to the
        // observer: the registry is untouched and the count has moved.
        val now = at(1_000)
        val stale = LeaseManager()

        val first = decide(stale, now, leasesTaken = 0) as LaunchGate.Decision.Intercept
        val second = decide(stale, now, leasesTaken = 1) as LaunchGate.Decision.Intercept

        assertEquals(GateCountdown.FIRST_MS, first.countdownMs)
        assertEquals(GateCountdown.FIRST_MS + GateCountdown.STEP_MS, second.countdownMs)
        assertTrue("and it claims the lease expired, which it did not", second.expired)
    }

    @Test
    fun `applying the same grant twice is a no-op, so the observer cannot disagree`() {
        // What makes writing in both places safe. The service grants in
        // memory and the store grants again from its own copy; the observer
        // then assigns the store's answer over the top. All three have to
        // land on the same lease or the fix trades one divergence for
        // another.
        val now = at(1_000)
        val once = LeaseManager().grant(pkg, now, 15 * 60_000L, accumulatedMs = 0L)
        val twice = once.grant(pkg, now, 15 * 60_000L, accumulatedMs = 0L)
        assertEquals(once.snapshot(), twice.snapshot())

        // And a moment later, which is when the observer's assignment
        // actually arrives.
        val later = at(1_400)
        assertEquals(
            once.snapshot(),
            once.grant(pkg, later, 15 * 60_000L, accumulatedMs = 0L).snapshot(),
        )
    }

    @Test
    fun `the service applies the grant to its own registry, not only to the store`() {
        val body = grantLeaseBody()
        assertTrue(
            "grantLease must assign `leases` itself. Without it the next " +
                "gate decision reads a package with no lease and one more " +
                "lease taken, and charges a second rung for one decision.",
            Regex("""\bleases\s*=\s*leases\.grant\(""").containsMatchIn(body),
        )
    }

    @Test
    fun `the assignment is not inside the coroutine that writes the store`() {
        // The whole point is that it happens before the callback returns. An
        // assignment moved inside `scope.launch` would compile, would read
        // like the same fix, and would restore the original bug with a
        // shorter window.
        val body = grantLeaseBody()
        val assignment = body.indexOf("leases = leases.grant(")
        val launch = body.indexOf("scope.launch")
        assertTrue("no assignment found in grantLease", assignment >= 0)
        assertTrue("no store write found in grantLease", launch >= 0)
        assertTrue(
            "the in-memory grant must come before the store write, and " +
                "outside the coroutine that performs it",
            assignment < launch,
        )
    }

    // ------------------------------------------------------------- rollover

    @Test
    fun `after a rollover the first gate of the new cycle fires`() {
        // The engine replaces every AppState on a rollover, so leasesTaken is
        // zero in the same call stack. The registry has to be cleared in that
        // call stack too, or the lease the old cycle issued outlives the cycle
        // it was escalating against.
        val now = at(1_000)
        val before = LeaseManager().grant(pkg, now, 15 * 60_000L, accumulatedMs = 0L)
        assertTrue(
            "a live lease passes, which is correct before the rollover",
            decide(before, now, leasesTaken = 1) is LaunchGate.Decision.Pass,
        )

        val after = LeaseManager()
        val decision = decide(after, at(1_100), leasesTaken = 0)
        assertTrue(
            "the first gate of a new cycle must fire, got $decision",
            decision is LaunchGate.Decision.Intercept,
        )
        assertEquals(
            "and it is the first gate, not a continuation of the old ladder",
            GateCountdown.FIRST_MS,
            (decision as LaunchGate.Decision.Intercept).countdownMs,
        )
        assertTrue("nothing expired, because the cycle started over", !decision.expired)
    }

    @Test
    fun `without the in-memory clear the new cycle opens free`() {
        // The shape of the bug. The count has reset and the registry has not,
        // so the gate passes on a lease that belongs to a cycle that is over.
        // This is the dangerous direction: the last bug charged a rung too
        // many, this one hands out an ungated launch.
        val now = at(1_000)
        val stale = LeaseManager().grant(pkg, now, 15 * 60_000L, accumulatedMs = 0L)
        assertTrue(
            decide(stale, at(1_100), leasesTaken = 0) is LaunchGate.Decision.Pass,
        )
    }

    @Test
    fun `the service clears its own registry on rollover, not only the store`() {
        // Same text assertion as the grant, for the same reason: the clear
        // has to happen in the call stack the engine rolls the cycle on.
        // Moved inside the coroutine it would compile, would read like this
        // fix, and would restore the bug with a shorter window.
        val body = onCycleRolledBody()
        val clear = body.indexOf("leases = LeaseManager()")
        val launch = body.indexOf("scope.launch")
        assertTrue(
            "onCycleRolled must clear `leases` itself. Without it the first " +
                "gate of the new cycle passes on a lease the new cycle never " +
                "issued.",
            clear >= 0,
        )
        assertTrue("no store clear found in onCycleRolled", launch >= 0)
        assertTrue(
            "the in-memory clear must come before the store write, and " +
                "outside the coroutine that performs it",
            clear < launch,
        )
    }

    /** The `onCycleRolled` lambda, from its name to the argument that follows. */
    private fun onCycleRolledBody(): String {
        val text = serviceSource()
        val start = text.indexOf("onCycleRolled = {")
        assertTrue("onCycleRolled has been renamed or removed", start >= 0)
        val end = text.indexOf("\n            },", start)
        assertTrue("could not find the end of the onCycleRolled lambda", end > start)
        return text.substring(start, end)
    }

    /**
     * The text of `grantLease`, from its signature to the next declaration.
     *
     * Sliced rather than searched whole, so a match somewhere else in a
     * thousand line file cannot satisfy either assertion above.
     */
    private fun grantLeaseBody(): String {
        val text = serviceSource()
        val start = text.indexOf("private fun grantLease(")
        assertTrue("grantLease has been renamed or removed", start >= 0)
        val end = text.indexOf("\n    /**", start)
        return text.substring(start, if (end > start) end else text.length)
    }

    private fun serviceSource(): String = repoFile(
        "app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt",
    ).readText()

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative from ${System.getProperty("user.dir")}")
    }
}
