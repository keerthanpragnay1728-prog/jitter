package dev.molasses.core.settings

import dev.molasses.core.settings.UntrackCoolingOff.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UntrackCoolingOffTest {

    private val pkg = "com.instagram.android"
    private fun started(at: Long = 1_000L) =
        UntrackCoolingOff.start(pkg, "Instagram", tracked = true, lockRemainingMs = 0, nowElapsedMs = at)!!

    @Test
    fun `it is 150 seconds`() {
        assertEquals(150_000L, UntrackCoolingOff.DURATION_MS)
    }

    @Test
    fun `it counts down on elapsed time and is ready only at zero`() {
        val s = started(1_000)
        assertEquals(Phase.Counting(150_000), UntrackCoolingOff.phase(s, 1_000))
        assertEquals(Phase.Counting(1), UntrackCoolingOff.phase(s, 1_000 + 149_999))
        assertEquals(Phase.Ready, UntrackCoolingOff.phase(s, 1_000 + 150_000))
        assertEquals(Phase.Ready, UntrackCoolingOff.phase(s, 1_000 + 900_000))
    }

    @Test
    fun `the answers are unavailable until zero`() {
        val s = started(0)
        assertFalse(UntrackCoolingOff.mayConfirm(s, 149_999))
        assertTrue(UntrackCoolingOff.mayConfirm(s, 150_000))
    }

    @Test
    fun `a clock earlier than the start serves nothing`() {
        assertEquals(Phase.Counting(150_000), UntrackCoolingOff.phase(started(10_000), 5_000))
    }

    @Test
    fun `seconds round up, reading 150 at the start and 0 only at zero`() {
        assertEquals(150L, UntrackCoolingOff.seconds(150_000))
        assertEquals(1L, UntrackCoolingOff.seconds(1))
        assertEquals(0L, UntrackCoolingOff.seconds(0))
    }

    @Test
    fun `a locked or untracked app never starts one`() {
        assertNull(UntrackCoolingOff.start(pkg, "Instagram", tracked = true, lockRemainingMs = 60_000, nowElapsedMs = 0))
        assertNull(UntrackCoolingOff.start(pkg, "Instagram", tracked = false, lockRemainingMs = 0, nowElapsedMs = 0))
    }

    @Test
    fun `every way of leaving abandons it`() {
        for (how in UntrackCoolingOff.Leave.entries) {
            assertNull("$how", UntrackCoolingOff.onLeave(started(), how))
        }
    }

    @Test
    fun `last target only when it is the one tracked package`() {
        val all: (String) -> Boolean = { true }
        assertTrue(UntrackCoolingOff.isLastTarget(pkg, listOf(pkg), all))
        assertFalse(UntrackCoolingOff.isLastTarget(pkg, listOf(pkg, "com.twitter.android"), all))
        assertFalse("not tracked at all", UntrackCoolingOff.isLastTarget(pkg, emptyList(), all))
        assertFalse("some other app is the last", UntrackCoolingOff.isLastTarget(pkg, listOf("com.twitter.android"), all))
    }

    @Test
    fun `a tracked package that is not installed does not stop it being the last`() {
        // The device case: a default that was never installed stays in the
        // resolved set, CFG draws no row for it, and the line never showed.
        val installed = setOf(pkg, "com.google.android.youtube")
        val tracked = listOf(pkg, "com.twitter.android")
        assertTrue(UntrackCoolingOff.isLastTarget(pkg, tracked, installed::contains))
        assertFalse(
            "an installed second target still counts",
            UntrackCoolingOff.isLastTarget(pkg, tracked + "com.google.android.youtube", installed::contains),
        )
    }

    @Test
    fun `the app itself counts even while the installed list is still loading`() {
        assertTrue(UntrackCoolingOff.isLastTarget(pkg, listOf(pkg)) { false })
    }
}
