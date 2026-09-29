package dev.molasses.core.lease

import dev.molasses.core.time.StampedInstant
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a lease survives being written down, and that expiry survives it too.
 *
 * The same two halves as `LockPersistenceTest`, pointed the other way. There
 * the risk is a restart refunding served time; here it is a restart handing
 * back time that has already run out, because a lease that comes back from
 * disk alive is a gate the user can clear by killing the launcher.
 *
 * Pure; no Android imports.
 */
class LeasePersistenceTest {

    private val ig = "com.instagram.android"
    private val min = 60_000L
    private val hour = 60 * min
    private val wallBase = 1_700_000_000_000L

    private fun at(offsetMs: Long, boot: Int = 1) =
        StampedInstant(wallMs = wallBase + offsetMs, elapsedMs = offsetMs, bootId = boot)

    /** What the store does: write the snapshot out, read it back in. */
    private fun restart(m: LeaseManager) = LeaseManager.of(m.snapshot())

    // ------------------------------------------------------- never longer

    @Test
    fun `a live lease is still not lengthened after a restart`() {
        // The invariant the subsystem is for. In memory it is
        // LeaseManager.grant; on disk it is the same call made inside
        // updateData against what was just read back.
        val granted = LeaseManager().grant(ig, at(0), 5 * min, 0)
        val attacked = restart(granted).grant(ig, at(min), 15 * min, 0)
        assertEquals(
            "fifteen minutes must not be bought on top of a live five",
            4 * min,
            attacked.remainingMs(ig, at(min)),
        )
    }

    @Test
    fun `time served is not refunded by a restart`() {
        // Kill the launcher twice and a five minute lease never ends would be
        // the mirror of the lock's bypass, and just as cheap to perform.
        val granted = LeaseManager().grant(ig, at(0), 15 * min, 0)
        assertEquals(5 * min, granted.remainingMs(ig, at(10 * min)))
        assertEquals(5 * min, restart(granted).remainingMs(ig, at(10 * min)))
    }

    @Test
    fun `an expired lease does not come back alive`() {
        val granted = LeaseManager().grant(ig, at(0), 5 * min, 0)
        assertFalse(restart(granted).isActive(ig, at(6 * min)))
        // And again, because a second restart is what an ordinary crash loop
        // looks like.
        assertFalse(restart(restart(granted)).isActive(ig, at(6 * min)))
    }

    // -------------------------------------------------------------- clocks

    @Test
    fun `a backward clock wind does not hold a restored lease open`() {
        // The RELIEF direction, asserted on the far side of persistence.
        val granted = restart(LeaseManager().grant(ig, at(0), 5 * min, 0))
        val wound = StampedInstant(
            wallMs = wallBase - hour,
            elapsedMs = 6 * min,
            bootId = 1,
        )
        assertEquals(0L, granted.remainingMs(ig, wound))
    }

    @Test
    fun `a reboot ends a restored lease`() {
        // The opposite answer from a lock, and deliberately. A lock has to
        // span a reboot to mean anything; a lease is fifteen minutes and a
        // reboot outlasts it, so expiring is both safe and free.
        val granted = restart(LeaseManager().grant(ig, at(0), 15 * min, 0))
        assertEquals(0L, granted.remainingMs(ig, at(min, boot = 2)))
    }

    // ------------------------------------------------------- lossless

    @Test
    fun `every field survives the round trip`() {
        val leases = listOf(
            Lease(ig, at(0, boot = 3), 5 * min, 12 * min),
            Lease("com.google.android.youtube", at(min, boot = 3), 10 * min, 0),
            Lease("com.reddit.frontpage", at(2 * min, boot = 3), 15 * min, 38 * min),
        )
        assertEquals(leases.sortedBy { it.pkg }, LeaseManager.of(leases).snapshot())
    }

    // ------------------------------------------------- the proto keeps up

    @Test
    fun `the proto declares a field for every property of a lease`() {
        val entry = message("LeaseEntry")
        // Lease.startedAt is a StampedInstant, stored flat as three fields.
        val expected = (propertiesOf(Lease::class.java) - "startedAt") +
            propertiesOf(StampedInstant::class.java).map { "started_${it.snake()}" }
        assertTrue("reflection found nothing; the model shape has changed", expected.size >= 6)

        for (name in expected) {
            assertTrue(
                "LeaseEntry has no field for Lease.$name. Adding a property to " +
                    "the model without adding it here loses it on every write.",
                Regex("""\b${Regex.escape(name.snake())}\s*=\s*\d+;""").containsMatchIn(entry),
            )
        }
    }

    @Test
    fun `CycleState stores the lease list and the gate mode`() {
        assertTrue(
            "no repeated LeaseEntry field on CycleState",
            Regex("""repeated\s+LeaseEntry\s+leases\s*=\s*\d+;""").containsMatchIn(proto),
        )
        assertTrue(
            "no gate_mode_ordinal field on CycleState",
            Regex("""int32\s+gate_mode_ordinal\s*=\s*\d+;""").containsMatchIn(proto),
        )
    }

    @Test
    fun `the default gate mode is the proto3 zero value`() {
        // The ordinal is stored raw, so COUNTDOWN being first is what makes
        // an install that has never touched the setting read correctly with
        // no migration.
        assertEquals(0, GatePolicy.GateMode.COUNTDOWN.ordinal)
    }

    @Test
    fun `the AppState fields the lease model needs are declared`() {
        val entry = message("message AppState")
        for (field in listOf("leases_taken", "lease_until_accumulated_ms", "penalty_ms")) {
            assertTrue(
                "AppState has no $field",
                Regex("""\b${Regex.escape(field)}\s*=\s*\d+;""").containsMatchIn(entry),
            )
        }
        // The two renamed fields kept their numbers. Renumbering them would
        // have reinterpreted every stored file on upgrade.
        assertTrue(
            "leases_taken must keep field number 3",
            Regex("""leases_taken\s*=\s*3;""").containsMatchIn(entry),
        )
        assertTrue(
            "lease_until_accumulated_ms must keep field number 4",
            Regex("""lease_until_accumulated_ms\s*=\s*4;""").containsMatchIn(entry),
        )
    }

    // -------------------------------------------------------------- helpers

    private val proto: String by lazy {
        repoFile("app/src/main/proto/cycle_state.proto").readText()
    }

    private fun message(header: String): String {
        val start = proto.indexOf("$header {")
        check(start >= 0) { "no '$header' in cycle_state.proto" }
        val end = proto.indexOf("\n}", start)
        return proto.substring(start, end)
    }

    /**
     * The model's own properties. Compiler-generated fields are not: the
     * synthetic ones, and anything named with a leading `$`, which is how the
     * Compose compiler names the `$stable` field it adds to every class in the
     * app module. pure-verify does not apply that compiler, so this passed
     * there and failed in `testDebugUnitTest`.
     */
    private fun propertiesOf(type: Class<*>): List<String> =
        type.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .filterNot { it == "Companion" || it == "UNSET" }

    private fun String.snake(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

}
