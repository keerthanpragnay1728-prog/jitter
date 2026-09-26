package dev.molasses.core.config

import dev.molasses.core.repoFile
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned stall and forced probability are gone, not merely hidden.
 *
 * Both were segment D capture aids with no control in any build. The pinned
 * stall was still read by the service in release, so a stored value would
 * have overridden the curve on a user's phone with nothing on screen to say
 * so. They were deleted rather than gated, because a gate on a value nothing
 * can write is a guard with no caller.
 */
class SegmentDAidsRemovedTest {

    @Test
    fun `nothing in main reads or writes either field`() {
        val main = File(repoFile("app/src/main/AndroidManifest.xml").parentFile, "java")
        val offenders = main.walkTopDown().filter { it.extension == "kt" }.filter { f ->
            val t = f.readText()
            listOf("DebugPinnedStallMs", "debugPinnedStallMs", "DebugForcedProbabilityPct", "debugForcedProbabilityPct", "pinnedStallMs")
                .any { t.contains(it) }
        }.map { it.name }.toList()
        assertTrue("still referenced in $offenders", offenders.isEmpty())
    }

    @Test
    fun `the field numbers are reserved so they cannot come back with a new meaning`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        assertTrue(proto.contains("reserved 15, 26;"))
        assertTrue(proto.contains("reserved \"debug_pinned_stall_ms\", \"debug_forced_probability_pct\";"))
    }
}
