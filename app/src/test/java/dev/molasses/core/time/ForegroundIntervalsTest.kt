package dev.molasses.core.time

import dev.molasses.core.time.ForegroundIntervals.Event
import dev.molasses.core.time.ForegroundIntervals.Interval
import dev.molasses.core.time.ForegroundIntervals.Kind
import dev.molasses.core.time.ForegroundIntervals.Orphan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundIntervalsTest {

    private val t0 = 1_700_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val end = t0 + 6 * hour

    private fun on(pkg: String, at: Long) = Event(Kind.RESUMED, at, pkg)
    private fun off(pkg: String, at: Long) = Event(Kind.CLOSED, at, pkg)
    private fun screenOff(at: Long) = Event(Kind.SCREEN_OFF, at)
    private fun keyguard(at: Long) = Event(Kind.KEYGUARD_SHOWN, at)
    private fun shutdown(at: Long) = Event(Kind.SHUTDOWN, at)

    private fun bound(
        events: List<Event>,
        interactive: Boolean = true,
        orphan: Orphan = Orphan.CREDIT_FROM_WINDOW_START,
        openAtStart: String? = null,
    ) = ForegroundIntervals.bound(events, t0, end, interactive, orphan, openAtStart)

    private fun msOf(intervals: List<Interval>, pkg: String) = intervals.filter { it.pkg == pkg }.sumOf { it.ms }

    @Test
    fun `its own close ends an interval`() {
        assertEquals(3 * second, msOf(bound(listOf(on("a", t0 + minute), off("a", t0 + minute + 3 * second))), "a"))
    }

    @Test
    fun `a missing close is bounded by another app's resume`() {
        val r = bound(listOf(on("a", t0 + minute), on("b", t0 + 2 * minute), off("b", t0 + 3 * minute)))
        assertEquals(minute, msOf(r, "a"))
        assertEquals(minute, msOf(r, "b"))
    }

    @Test
    fun `a missing close is bounded by screen-off`() {
        val r = bound(listOf(on("a", t0 + minute), screenOff(t0 + 4 * minute)))
        assertEquals(3 * minute, msOf(r, "a"))
    }

    @Test
    fun `a missing close is bounded by keyguard`() {
        val r = bound(listOf(on("a", t0 + minute), keyguard(t0 + 2 * minute)))
        assertEquals(minute, msOf(r, "a"))
    }

    @Test
    fun `a missing close is bounded by shutdown`() {
        val r = bound(listOf(on("a", t0 + minute), shutdown(t0 + 5 * minute)))
        assertEquals(4 * minute, msOf(r, "a"))
    }

    @Test
    fun `a stop closes as a pause does, and the later one is not a second close`() {
        // The device's shape: resumed, stopped, no pause.
        val stopped = bound(listOf(on("a", t0 + minute), off("a", t0 + minute + 2 * second), on("b", t0 + 2 * minute)))
        assertEquals(2 * second, msOf(stopped, "a"))
        // The usual shape: paused, then stopped after the next app resumed.
        val both = bound(listOf(on("a", t0 + minute), off("a", t0 + 2 * minute), on("b", t0 + 2 * minute), off("a", t0 + 2 * minute + second)))
        assertEquals(minute, msOf(both, "a"))
        assertEquals(1, both.count { it.pkg == "a" })
    }

    @Test
    fun `a genuinely current app is still counted up to now`() {
        val r = bound(listOf(on("a", t0 + minute), off("a", t0 + 2 * minute), on("b", end - 7 * minute)))
        assertEquals(7 * minute, msOf(r, "b"))
        assertTrue(r.single { it.pkg == "b" }.runsToEnd)
    }

    @Test
    fun `an open app with the screen not interactive closes at its last evidence, not at now`() {
        val r = bound(listOf(on("a", t0 + minute), on("a", t0 + 3 * minute)), interactive = false)
        assertEquals("from its first resume to its last", 2 * minute, msOf(r, "a"))
        assertTrue(r.none { it.runsToEnd })
    }

    @Test
    fun `the YONO shape gives seconds, not hours`() {
        // Three resumes, each followed by a stop and another package's resume,
        // and no pause at all. Read hours later.
        val yono = "com.sbi.lotusintouch"
        val at = t0 + hour
        val r = bound(
            listOf(
                on(yono, at), off(yono, at + 3 * second), on("com.android.vending", at + 3 * second),
                on(yono, at + 19 * second), off(yono, at + 21 * second), on("org.jitteros.app", at + 21 * second),
                on(yono, at + 33 * second), off(yono, at + 41 * second), on("org.jitteros.app", at + 41 * second),
            ),
        )
        assertEquals(13 * second, msOf(r, yono))
        // And with every stop missing too, the other resumes still bound it.
        val noCloses = bound(
            listOf(
                on(yono, at), on("com.android.vending", at + 3 * second),
                on(yono, at + 19 * second), on("org.jitteros.app", at + 21 * second),
                on(yono, at + 33 * second), on("org.jitteros.app", at + 41 * second),
            ),
        )
        assertEquals(13 * second, msOf(noCloses, yono))
    }

    @Test
    fun `a close for an app in front at the window start is bounded by the first bound`() {
        // In front across the window start; the screen went off at +5m and
        // its pause was only logged at +30m.
        val r = bound(listOf(screenOff(t0 + 5 * minute), off("a", t0 + 30 * minute)))
        assertEquals(5 * minute, msOf(r, "a"))
        assertTrue(r.single { it.pkg == "a" }.fromBeforeWindow)
        assertEquals(30 * minute, msOf(bound(listOf(off("a", t0 + 30 * minute))), "a"))
    }

    @Test
    fun `the reconciler drops a close with nothing before it`() {
        assertEquals(0L, msOf(bound(listOf(off("a", t0 + 30 * minute)), orphan = Orphan.DROP), "a"))
    }

    @Test
    fun `an app open at the start is bounded like any other`() {
        val r = bound(listOf(on("chrome", t0 + 30 * second)), orphan = Orphan.DROP, openAtStart = "ig")
        assertEquals(30 * second, msOf(r, "ig"))
        val alone = bound(emptyList(), orphan = Orphan.DROP, openAtStart = "ig")
        assertEquals("in front, screen on: to the end", end - t0, msOf(alone, "ig"))
        val dark = bound(emptyList(), interactive = false, orphan = Orphan.DROP, openAtStart = "ig")
        assertEquals("screen off and no evidence: nothing", 0L, msOf(dark, "ig"))
    }

    @Test
    fun `a duplicate resume keeps the earlier start`() {
        val r = bound(listOf(on("a", t0 + minute), on("a", t0 + 3 * minute), off("a", t0 + 4 * minute)))
        assertEquals(3 * minute, msOf(r, "a"))
    }

    @Test
    fun `a resume and its own close at one instant are zero, not a reach back to the start`() {
        val r = bound(listOf(on("a", t0 + hour), off("a", t0 + hour)))
        assertEquals(0L, msOf(r, "a"))
        assertTrue(r.none { it.fromBeforeWindow })
    }

    @Test
    fun `intervals never overlap, so their sum never exceeds the window`() {
        val rnd = kotlin.random.Random(7)
        val pkgs = listOf("a", "b", "c", "d")
        repeat(300) {
            val events = List(rnd.nextInt(0, 40)) {
                val at = t0 + rnd.nextLong(-hour, 7 * hour)
                when (rnd.nextInt(6)) {
                    0, 1 -> on(pkgs.random(rnd), at)
                    2 -> off(pkgs.random(rnd), at)
                    3 -> screenOff(at)
                    4 -> keyguard(at)
                    else -> shutdown(at)
                }
            }
            val r = bound(events, interactive = rnd.nextBoolean(), openAtStart = pkgs.random(rnd).takeIf { rnd.nextBoolean() })
            assertTrue(r.all { it.ms >= 0 && it.startMs >= t0 && it.endMs <= end })
            // Zero-length intervals hold no time and may share an instant.
            val sorted = r.filter { it.ms > 0 }.sortedBy { it.startMs }
            for (i in 1 until sorted.size) {
                assertTrue("$events gave overlapping $sorted", sorted[i].startMs >= sorted[i - 1].endMs)
            }
            assertTrue(r.sumOf { it.ms } <= end - t0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window that ends before it starts is rejected`() {
        ForegroundIntervals.bound(emptyList(), end, t0, true, Orphan.DROP)
    }
}
