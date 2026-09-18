package dev.molasses.core.lock

import dev.molasses.core.time.StampedInstant
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a lock survives being written down.
 *
 * ## The half that is pure
 * `LockRegistry.snapshot()` and `LockRegistry.of()` are the two ends of
 * persistence. Everything between them is a field copy, so a registry that
 * round-trips through its own snapshot and still answers identically under a
 * clock jump is the property worth asserting, and it is assertable here.
 *
 * ## The half that is not, and what stands in for it
 * The proto mapping lives in the Android layer and cannot run in this module.
 * What can be checked is that the proto declares a field for every property of
 * [Lock] and [StampedInstant]: the failure mode is someone adding a field to
 * the model, the mapping compiling because the new field has a default, and
 * every stored lock silently losing it on the next write. Reflection over the
 * data classes catches that; reviewing the mapping does not.
 *
 * Pure; no Android imports.
 */
class LockPersistenceTest {

    private val ig = "com.instagram.android"
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val wallBase = 1_700_000_000_000L

    private fun honest(offsetMs: Long, boot: Int = 1) =
        StampedInstant(wallMs = wallBase + offsetMs, elapsedMs = offsetMs, bootId = boot)

    /** What the store does: write the snapshot out, read it back in. */
    private fun restart(r: LockRegistry) = LockRegistry.of(r.snapshot())

    // ------------------------------------------------------- extend only

    @Test
    fun `a shorter lock is still a no-op after a restart`() {
        // The invariant the whole subsystem is for. In memory it is
        // LockRegistry.arm; on disk it is the same call made inside
        // updateData against what was just read back.
        val armed = LockRegistry().arm(ig, honest(0), 30 * day, LockReason.BLOCK)
        val afterRestart = restart(armed)
        val attacked = afterRestart.arm(ig, honest(hour), min, LockReason.BLOCK)

        assertEquals(
            "a one minute lock must not cancel thirty days",
            armed.remainingMs(ig, honest(hour)),
            attacked.remainingMs(ig, honest(hour)),
        )
    }

    @Test
    fun `a longer lock still extends after a restart`() {
        val armed = restart(LockRegistry().arm(ig, honest(0), hour, LockReason.BLOCK))
        val extended = armed.arm(ig, honest(0), 7 * day, LockReason.BLOCK)
        assertEquals(7 * day, extended.remainingMs(ig, honest(0)))
    }

    @Test
    fun `served time is not refunded by a restart`() {
        // A restart that reset the clock on a lock would make process death a
        // bypass: kill the app twice a day and a seven day lock never ends.
        val armed = LockRegistry().arm(ig, honest(0), 6 * hour, LockReason.BEDTIME)
        val fiveHoursIn = honest(5 * hour)
        assertEquals(hour, armed.remainingMs(ig, fiveHoursIn))
        assertEquals(hour, restart(armed).remainingMs(ig, fiveHoursIn))
    }

    // -------------------------------------------------------------- clocks

    @Test
    fun `a forward clock jump does not shorten a restored lock`() {
        // The RESTRICTION clamp, asserted on the far side of persistence. The
        // stored stamp carries elapsedRealtime and the boot id precisely so
        // this still holds after the file has been round-tripped.
        val armed = restart(LockRegistry().arm(ig, honest(0), 30 * day, LockReason.BLOCK))
        val jumped = StampedInstant(
            wallMs = wallBase + 30 * day + hour,
            elapsedMs = hour,
            bootId = 1,
        )
        assertTrue("a thirty day jump must not expire the lock", armed.isLocked(ig, jumped))
        assertEquals(30 * day - hour, armed.remainingMs(ig, jumped))
    }

    @Test
    fun `a reboot does not end a restored lock`() {
        // The one case where the wall clock has to be trusted: there is no
        // monotonic reading that spans the boot. A lock that ended at every
        // reboot would be cancellable by holding the power button.
        val armed = restart(LockRegistry().arm(ig, honest(0), 7 * day, LockReason.BLOCK))
        val afterReboot = StampedInstant(
            wallMs = wallBase + day,
            elapsedMs = 30_000,
            bootId = 2,
        )
        assertEquals(6 * day, armed.remainingMs(ig, afterReboot))
    }

    // ------------------------------------------------------- lossless

    @Test
    fun `every field survives the round trip`() {
        val locks = listOf(
            Lock(ig, honest(0, boot = 3), 30 * day, LockReason.BLOCK),
            Lock("com.google.android.youtube", honest(min, boot = 3), hour, LockReason.FOCUS),
            Lock("com.reddit.frontpage", honest(2 * min, boot = 3), day, LockReason.BEDTIME),
            Lock("com.twitter.android", honest(3 * min, boot = 3), 5 * min, LockReason.CHECKPOINT),
        )
        assertEquals(locks.sortedBy { it.pkg }, LockRegistry.of(locks).snapshot())
    }

    // ------------------------------------------------- the proto keeps up

    @Test
    fun `the proto declares a field for every property of a lock`() {
        val entry = message("LockEntry")
        // Lock.startedAt is a StampedInstant, stored flat as three fields.
        val expected = (propertiesOf(Lock::class.java) - "startedAt") +
            propertiesOf(StampedInstant::class.java).map { "started_${it.snake()}" }
        assertTrue("reflection found nothing; the model shape has changed", expected.size >= 6)

        for (name in expected) {
            assertTrue(
                "LockEntry has no field for Lock.$name. Adding a property to " +
                    "the model without adding it here loses it on every write.",
                Regex("""\b${Regex.escape(name.snake())}\s*=\s*\d+;""").containsMatchIn(entry),
            )
        }
    }

    @Test
    fun `the proto declares every lock reason`() {
        val declared = message("enum LockReasonProto")
        for (reason in LockReason.entries) {
            assertTrue(
                "LockReasonProto is missing ${reason.name}",
                Regex("""\b${reason.name}\s*=\s*\d+;""").containsMatchIn(declared),
            )
        }
        // BLOCK must be zero: proto3 pins the zero value as the default, and
        // an unreadable reason field falling back to BLOCK is the honest
        // answer because BLOCK is the reason with no extra behaviour.
        assertTrue(
            "BLOCK must be the proto3 zero value",
            Regex("""BLOCK\s*=\s*0;""").containsMatchIn(declared),
        )
    }

    @Test
    fun `CycleState stores the lock list`() {
        assertTrue(
            "no repeated LockEntry field on CycleState",
            Regex("""repeated\s+LockEntry\s+locks\s*=\s*\d+;""").containsMatchIn(proto),
        )
    }

    // -------------------------------------------------------------- helpers

    private val proto: String by lazy {
        repoFile("app/src/main/proto/cycle_state.proto").readText()
    }

    /** The body of a named message or enum, for a targeted assertion. */
    private fun message(header: String): String {
        val start = proto.indexOf("$header {")
        check(start >= 0) { "no '$header' in cycle_state.proto" }
        val end = proto.indexOf("}", start)
        return proto.substring(start, end)
    }

    private fun propertiesOf(type: Class<*>): List<String> =
        type.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it == "Companion" || it == "UNSET" }

    private fun String.snake(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

}
