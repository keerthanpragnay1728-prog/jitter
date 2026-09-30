package dev.molasses.core.settings

import dev.molasses.core.settings.UntrackSunset.Sunset
import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UntrackSunsetTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val start = StampedInstant(wallMs = 1_700_000_000_000L, elapsedMs = 50_000_000L, bootId = 4)
    private val ig = "com.instagram.android"

    /** [start] moved on by [ms] on both clocks, same boot. */
    private fun after(ms: Long, wallShiftMs: Long = 0L, bootId: Int = start.bootId) =
        StampedInstant(start.wallMs + ms + wallShiftMs, start.elapsedMs + ms, bootId)

    @Test
    fun `the deadline is seven days out on both clocks, in the same boot`() {
        val s = UntrackSunset.grant(ig, start)
        assertEquals(7 * day, UntrackSunset.GRACE_MS)
        assertEquals(StampedInstant(start.wallMs + 7 * day, start.elapsedMs + 7 * day, start.bootId), s.deadline)
        assertEquals(7 * day, UntrackSunset.remainingMs(s, start))
        assertEquals(start.wallMs + 7 * day, UntrackSunset.resumesAtWallMs(s, start))
    }

    @Test
    fun `it is due at seven days and not a millisecond before`() {
        val s = UntrackSunset.grant(ig, start)
        assertFalse(UntrackSunset.due(s, after(7 * day - 1)))
        assertEquals(1L, UntrackSunset.remainingMs(s, after(7 * day - 1)))
        assertTrue(UntrackSunset.due(s, after(7 * day)))
        assertTrue(UntrackSunset.due(s, after(30 * day)))
    }

    @Test
    fun `winding the clock back does not extend it`() {
        val s = UntrackSunset.grant(ig, start)
        // Six days in, then the wall clock is wound back five days.
        val wound = after(6 * day, wallShiftMs = -5 * day)
        assertEquals(day, UntrackSunset.remainingMs(s, wound))
        assertTrue(UntrackSunset.due(s, after(7 * day, wallShiftMs = -5 * day)))
        // And the date shown is the date the check will act on.
        assertEquals(wound.wallMs + day, UntrackSunset.resumesAtWallMs(s, wound))
    }

    @Test
    fun `moving the clock forward ends it sooner, which is the friction direction`() {
        val s = UntrackSunset.grant(ig, start)
        assertTrue(UntrackSunset.due(s, after(hour, wallShiftMs = 7 * day)))
    }

    @Test
    fun `a reboot ends it, because relief does not trust the wall clock across a boot`() {
        val s = UntrackSunset.grant(ig, start)
        val rebooted = StampedInstant(start.wallMs + hour, elapsedMs = 30_000L, bootId = start.bootId + 1)
        assertEquals(0L, UntrackSunset.remainingMs(s, rebooted))
        assertTrue(UntrackSunset.due(s, rebooted))
    }

    @Test
    fun `scope is the social category or the named list, and never YouTube`() {
        for (pkg in listOf(
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.snapchat.android",
        )) {
            assertTrue("$pkg by name, with no category", UntrackSunset.inScope(pkg, categorySocial = false))
        }
        assertTrue("any social app by category", UntrackSunset.inScope("org.example.forum", categorySocial = true))
        assertFalse("anything else untracks permanently", UntrackSunset.inScope("org.example.calculator", categorySocial = false))
        assertFalse(UntrackSunset.inScope(UntrackSunset.YOUTUBE, categorySocial = false))
        assertFalse("YouTube even if it declared itself social", UntrackSunset.inScope(UntrackSunset.YOUTUBE, categorySocial = true))
        assertFalse(UntrackSunset.inScope("", categorySocial = true))
    }

    @Test
    fun `an untrack in scope stores a sunset, and one out of scope stores nothing`() {
        val granted = UntrackSunset.afterToggle(emptyList(), ig, trackedAfter = false, grantsSunset = true, now = start)
        assertEquals(listOf(UntrackSunset.grant(ig, start)), granted)
        val none = UntrackSunset.afterToggle(emptyList(), "org.example.calculator", trackedAfter = false, grantsSunset = false, now = start)
        assertEquals(emptyList<Sunset>(), none)
    }

    @Test
    fun `untracking again replaces the old sunset, and tracking by hand clears it`() {
        val old = UntrackSunset.grant(ig, start)
        val other = UntrackSunset.grant("com.snapchat.android", start)
        val later = after(2 * day)
        val replaced = UntrackSunset.afterToggle(listOf(old, other), ig, trackedAfter = false, grantsSunset = true, now = later)
        assertEquals(listOf(other, UntrackSunset.grant(ig, later)), replaced)
        val cleared = UntrackSunset.afterToggle(listOf(old, other), ig, trackedAfter = true, grantsSunset = false, now = later)
        assertEquals(listOf(other), cleared)
    }

    @Test
    fun `a re-arm adds due apps back and keeps the rest running`() {
        val due = UntrackSunset.grant(ig, start)
        val running = UntrackSunset.grant("com.snapchat.android", after(3 * day))
        val r = UntrackSunset.rearm(listOf("com.twitter.android"), listOf(due, running), after(7 * day))!!
        assertEquals(listOf("com.twitter.android", ig), r.targets)
        assertEquals(listOf(running), r.remaining)
        assertEquals(listOf(ig), r.rearmed)
    }

    @Test
    fun `a re-arm leaves the chosen-empty state, and nothing due writes nothing`() {
        val s = UntrackSunset.grant(ig, start)
        assertNull(UntrackSunset.rearm(emptyList(), listOf(s), after(day)))
        assertEquals(listOf(ig), UntrackSunset.rearm(emptyList(), listOf(s), after(7 * day))!!.targets)
    }

    @Test
    fun `a re-arm does not duplicate an app that is already tracked`() {
        val s = UntrackSunset.grant(ig, start)
        val r = UntrackSunset.rearm(listOf(ig), listOf(s), after(7 * day))!!
        assertEquals(listOf(ig), r.targets)
        assertEquals(emptyList<Sunset>(), r.remaining)
    }
}
