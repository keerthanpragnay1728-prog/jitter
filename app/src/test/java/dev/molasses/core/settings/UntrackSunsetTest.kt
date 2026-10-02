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

    /** The grace, read from the constant so no test restates the number. */
    private val grace = UntrackSunset.GRACE_MS

    /** [start] moved on by [ms] on both clocks, same boot. */
    private fun after(ms: Long, wallShiftMs: Long = 0L, bootId: Int = start.bootId) =
        StampedInstant(start.wallMs + ms + wallShiftMs, start.elapsedMs + ms, bootId)

    @Test
    fun `a new untrack gets three days, on both clocks, in the same boot`() {
        assertEquals(3, UntrackSunset.GRACE_DAYS)
        assertEquals(3 * day, grace)
        val s = UntrackSunset.grant(ig, start)
        assertEquals(StampedInstant(start.wallMs + grace, start.elapsedMs + grace, start.bootId), s.deadline)
        assertEquals(grace, UntrackSunset.remainingMs(s, start))
        assertEquals(start.wallMs + grace, UntrackSunset.resumesAtWallMs(s, start))
    }

    @Test
    fun `a sunset stored with a seven-day deadline keeps its date`() {
        // Granted under the first grace, 7 days, before it was shortened. No
        // migration: it ends on the date its user was shown, and CFG shows
        // that date throughout, not now plus the new grace.
        val old = Sunset(ig, StampedInstant(start.wallMs + 7 * day, start.elapsedMs + 7 * day, start.bootId))
        assertEquals(7 * day, UntrackSunset.remainingMs(old, start))
        assertEquals(6 * day, UntrackSunset.remainingMs(old, after(day)))
        for (at in listOf(0L, day, 2 * day, 4 * day, 6 * day)) {
            assertEquals("shown on day ${at / day}", start.wallMs + 7 * day, UntrackSunset.resumesAtWallMs(old, after(at)))
        }
        assertFalse("not cut short to the new grace", UntrackSunset.due(old, after(grace)))
        assertFalse(UntrackSunset.due(old, after(7 * day - 1)))
        assertTrue(UntrackSunset.due(old, after(7 * day)))
        // The stored entry itself is never rewritten by a re-arm that does
        // not take it.
        val r = UntrackSunset.rearm(emptyList(), listOf(old, UntrackSunset.grant("com.snapchat.android", start)), after(grace))!!
        assertEquals(listOf(old), r.remaining)
        assertEquals(listOf("com.snapchat.android"), r.rearmed)
    }

    @Test
    fun `it is due at the grace and not a millisecond before`() {
        val s = UntrackSunset.grant(ig, start)
        assertFalse(UntrackSunset.due(s, after(grace - 1)))
        assertEquals(1L, UntrackSunset.remainingMs(s, after(grace - 1)))
        assertTrue(UntrackSunset.due(s, after(grace)))
        assertTrue(UntrackSunset.due(s, after(30 * day)))
    }

    @Test
    fun `winding the clock back does not extend it`() {
        val s = UntrackSunset.grant(ig, start)
        // A day short of the end, then the wall clock is wound back two days.
        val wound = after(grace - day, wallShiftMs = -2 * day)
        assertEquals(day, UntrackSunset.remainingMs(s, wound))
        assertTrue(UntrackSunset.due(s, after(grace, wallShiftMs = -2 * day)))
        // And the date shown is the date the check will act on.
        assertEquals(wound.wallMs + day, UntrackSunset.resumesAtWallMs(s, wound))
    }

    @Test
    fun `moving the clock forward ends it sooner, which is the friction direction`() {
        val s = UntrackSunset.grant(ig, start)
        assertTrue(UntrackSunset.due(s, after(hour, wallShiftMs = grace)))
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
            "com.facebook.lite",
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
        val running = UntrackSunset.grant("com.snapchat.android", after(day))
        val r = UntrackSunset.rearm(listOf("com.twitter.android"), listOf(due, running), after(grace))!!
        assertEquals(listOf("com.twitter.android", ig), r.targets)
        assertEquals(listOf(running), r.remaining)
        assertEquals(listOf(ig), r.rearmed)
    }

    @Test
    fun `a re-arm leaves the chosen-empty state, and nothing due writes nothing`() {
        val s = UntrackSunset.grant(ig, start)
        assertNull(UntrackSunset.rearm(emptyList(), listOf(s), after(day)))
        assertEquals(listOf(ig), UntrackSunset.rearm(emptyList(), listOf(s), after(grace))!!.targets)
    }

    @Test
    fun `a re-arm does not duplicate an app that is already tracked`() {
        val s = UntrackSunset.grant(ig, start)
        val r = UntrackSunset.rearm(listOf(ig), listOf(s), after(grace))!!
        assertEquals(listOf(ig), r.targets)
        assertEquals(emptyList<Sunset>(), r.remaining)
    }
}
