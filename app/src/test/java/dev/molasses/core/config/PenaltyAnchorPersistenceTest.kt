package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The penalty anchor reaches the proto and comes back with its presence.
 *
 * The mapping lives in the DataStore layer, which nothing here compiles, so
 * it is read as text. Presence is the half that matters: a proto3 scalar
 * reads an absent field as zero, and an anchor of zero is exactly the double
 * charge this field was added to stop.
 */
class PenaltyAnchorPersistenceTest {

    @Test
    fun `the field is optional, so an absent anchor is distinguishable from zero`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        assertTrue(proto.contains("optional int64 penalty_anchor_ms = 8;"))
    }

    @Test
    fun `the store writes the anchor and reads it back by presence`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        assertTrue("toProto must write the anchor", store.contains("setPenaltyAnchorMs("))
        assertTrue(
            "the read must check presence rather than take the default zero",
            store.contains("if (a.hasPenaltyAnchorMs()) a.penaltyAnchorMs else null"),
        )
    }
}
