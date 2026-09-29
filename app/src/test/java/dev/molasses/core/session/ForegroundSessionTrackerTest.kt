package dev.molasses.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundSessionTrackerTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    @Test
    fun `both exit paths firing for one session close it once`() {
        // The accessibility launcher event and the usage-stats watchdog both
        // race to close the same session. Exactly one PAUSED row must result.
        val t = ForegroundSessionTracker()
        val id = t.open(ig)

        assertEquals("first path closes it", ig, t.close(id))
        assertNull("second path is a no-op", t.close(id))
    }

    @Test
    fun `a stale close cannot end the session that replaced it`() {
        // The failure that timestamp proximity would not catch: leave
        // Instagram, come straight back, and a late watchdog tick still
        // holding the old id must not close the new session.
        val t = ForegroundSessionTracker()
        val first = t.open(ig)
        t.close(first)
        val second = t.open(ig)

        assertNull("the late close names a session that is gone", t.close(first))
        assertEquals("the live session is untouched", ig, t.openPkg)
        assertEquals(ig, t.close(second))
    }

    @Test
    fun `re-entering the same app keeps one session`() {
        // Window state changes fire repeatedly inside one app. A new id per
        // dialog would fragment the accounting.
        val t = ForegroundSessionTracker()
        val first = t.open(ig)
        repeat(10) { assertEquals(first, t.open(ig)) }
        assertEquals(ig, t.close(first))
    }

    @Test
    fun `switching apps ends the previous session and starts a new one`() {
        val t = ForegroundSessionTracker()
        val a = t.open(ig)
        val b = t.open(yt)
        assertEquals(yt, t.openPkg)
        assertNull("the old id is stale once the app changed", t.close(a))
        assertEquals(yt, t.close(b))
    }

    @Test
    fun `ids are never reused`() {
        val t = ForegroundSessionTracker()
        val seen = mutableSetOf<ForegroundSessionTracker.SessionId>()
        repeat(100) {
            val id = t.open(if (it % 2 == 0) ig else yt)
            assertEquals("id $id was reused", true, seen.add(id))
            t.close(id)
        }
    }

    @Test
    fun `closing when nothing is open is a no-op`() {
        val t = ForegroundSessionTracker()
        assertNull(t.closeCurrent())
        assertNull(t.close(ForegroundSessionTracker.SessionId(42)))
    }

    @Test
    fun `closeCurrent ends whatever is open`() {
        val t = ForegroundSessionTracker()
        t.open(ig)
        assertEquals(ig, t.closeCurrent())
        assertNull(t.openPkg)
    }
}
