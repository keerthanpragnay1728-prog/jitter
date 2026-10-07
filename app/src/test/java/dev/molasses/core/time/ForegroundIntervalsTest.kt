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
    private fun off(pkg: String, at: Long) = Event(Kind.PAUSED, at, pkg)
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

    // ------------------------------------------------- per activity

    private fun on(pkg: String, cls: String, at: Long) = Event(Kind.RESUMED, at, pkg, cls)
    private fun pause(pkg: String, cls: String, at: Long) = Event(Kind.PAUSED, at, pkg, cls)
    private fun stop(pkg: String, cls: String, at: Long) = Event(Kind.STOPPED, at, pkg, cls)

    /**
     * The gap between X's pause and Y's resume in [navigate]. The set is
     * empty for that long, so each screen change costs it: the rule closes
     * the interval when the last activity pauses and opens a new one at the
     * next resume. Twenty milliseconds a change, against minutes a change
     * under the first rule.
     */
    private val handoff = 20L

    /** One screen change inside [pkg] at [at]: X paused, Y resumed, then X stopped. */
    private fun navigate(pkg: String, from: String, to: String, at: Long) = listOf(
        pause(pkg, from, at),
        on(pkg, to, at + handoff),
        stop(pkg, from, at + 400),
    )

    @Test
    fun `X paused, Y resumed, X stopped keeps the app open through Y`() {
        val wa = "com.whatsapp"
        val r = bound(
            listOf(on(wa, "Home", t0 + minute)) +
                navigate(wa, "Home", "Conversation", t0 + 2 * minute) +
                listOf(pause(wa, "Conversation", t0 + 12 * minute), on("org.jitteros.app", "Launcher", t0 + 12 * minute + 20)),
        )
        // The first rule closed it at X's stop, 400 ms into the chat, and
        // counted the rest of the chat as nothing.
        assertEquals(11 * minute - handoff, msOf(r, wa))
    }

    @Test
    fun `repeated in-app navigation over 30 minutes gives 30 minutes`() {
        val chess = "com.chess"
        val screens = listOf("Home", "Game", "Analysis", "Game")
        val events = mutableListOf(on(chess, "Home", t0))
        for (i in 1 until 30) events += navigate(chess, screens[(i - 1) % 4], screens[i % 4], t0 + i * minute)
        events += pause(chess, screens[29 % 4], t0 + 30 * minute)
        events += on("org.jitteros.app", "Launcher", t0 + 30 * minute + 20)
        // Thirty minutes, less the twenty-nine handoffs.
        assertEquals(30 * minute - 29 * handoff, msOf(bound(events), chess))
    }

    @Test
    fun `two screens of one class stay open across the change`() {
        // One chat, then another: the same class, two instances, and the
        // first one's stop arrives after the second has resumed.
        val wa = "com.whatsapp"
        val r = bound(
            listOf(on(wa, "Conversation", t0 + minute)) +
                navigate(wa, "Conversation", "Conversation", t0 + 2 * minute) +
                listOf(screenOff(t0 + 9 * minute)),
        )
        assertEquals(8 * minute - handoff, msOf(r, wa))
    }

    @Test
    fun `Y's own pause, then another package's resume, closes the app`() {
        val app = "com.example"
        val r = bound(
            listOf(on(app, "X", t0 + minute)) +
                navigate(app, "X", "Y", t0 + 2 * minute) +
                listOf(pause(app, "Y", t0 + 5 * minute), on("com.other", "Main", t0 + 5 * minute + 20), stop(app, "Y", t0 + 5 * minute + 400)),
        )
        assertEquals(4 * minute - handoff, msOf(r, app))
        assertTrue(r.none { it.pkg == app && it.runsToEnd })
    }

    @Test
    fun `another package's resume closes the app with activities still in its set`() {
        // Y never paused: a translucent window of another app, or a lost
        // event. The other app's resume ends it all the same.
        val app = "com.example"
        val r = bound(listOf(on(app, "X", t0 + minute), on(app, "Y", t0 + 2 * minute), on("com.other", "Main", t0 + 3 * minute)))
        assertEquals(2 * minute, msOf(r, app))
    }

    @Test
    fun `screen-off closes the app with activities still in its set`() {
        val app = "com.example"
        val r = bound(listOf(on(app, "X", t0 + minute), on(app, "Y", t0 + 2 * minute), screenOff(t0 + 6 * minute)))
        assertEquals(5 * minute, msOf(r, app))
        // And the stops that follow the screen going off close nothing more.
        val after = bound(
            listOf(on(app, "X", t0 + minute), screenOff(t0 + 6 * minute), pause(app, "X", t0 + 6 * minute + 50), stop(app, "X", t0 + 6 * minute + 500)),
        )
        assertEquals(5 * minute, msOf(after, app))
    }

    @Test
    fun `a stop for an activity not in the set is ignored`() {
        val app = "com.example"
        val r = bound(listOf(on(app, "Y", t0 + minute), stop(app, "Stale", t0 + 2 * minute), screenOff(t0 + 4 * minute)))
        assertEquals(3 * minute, msOf(r, app))
    }

    @Test
    fun `a stop with no pause before it still closes its activity, the YONO shape with classes`() {
        val yono = "com.sbi.lotusintouch"
        val at = t0 + hour
        val r = bound(
            listOf(
                on(yono, "Splash", at), stop(yono, "Splash", at + 3 * second), on("com.android.vending", "Main", at + 3 * second),
                on(yono, "Splash", at + 19 * second), stop(yono, "Splash", at + 21 * second),
                on(yono, "Splash", at + 33 * second), stop(yono, "Splash", at + 41 * second),
                screenOff(at + 4 * hour),
            ),
        )
        assertEquals(13 * second, msOf(r, yono))
    }

    @Test
    fun `one resume, no close, a later screen-off still gives minutes`() {
        val yono = "com.sbi.lotusintouch"
        val r = bound(listOf(on(yono, "Main", t0 + hour), screenOff(t0 + hour + 3 * minute), screenOff(t0 + 5 * hour)))
        assertEquals(3 * minute, msOf(r, yono))
    }

    @Test
    fun `an event with no class closes the whole app, as the package-only rule did`() {
        val app = "com.example"
        val r = bound(listOf(on(app, "X", t0 + minute), on(app, "Y", t0 + 2 * minute), off(app, t0 + 4 * minute), screenOff(t0 + 9 * minute)))
        assertEquals(3 * minute, msOf(r, app))
    }

    @Test
    fun `a seeded session of unknown class closes at the first close of any of its activities`() {
        val r = bound(listOf(pause("ig", "Feed", t0 + 30 * second), on("ig", "Reel", t0 + 31 * second), stop("ig", "Feed", t0 + 32 * second)), orphan = Orphan.DROP, openAtStart = "ig")
        // Open at the start, the unknown activity paused at +30 s, a new one
        // resumed at +31 s and runs to the end with the screen on.
        assertEquals(30 * second + (end - t0 - 31 * second), msOf(r, "ig"))
    }

    @Test
    fun `with classes, intervals still never overlap and never exceed the window`() {
        val rnd = kotlin.random.Random(23)
        val pkgs = listOf("a", "b", "c")
        val classes = listOf("", "X", "Y", "Z")
        repeat(400) {
            val events = List(rnd.nextInt(0, 50)) {
                val at = t0 + rnd.nextLong(-hour, 7 * hour)
                val pkg = pkgs.random(rnd)
                val cls = classes.random(rnd)
                when (rnd.nextInt(8)) {
                    0, 1, 2 -> Event(Kind.RESUMED, at, pkg, cls)
                    3 -> Event(Kind.PAUSED, at, pkg, cls)
                    4, 5 -> Event(Kind.STOPPED, at, pkg, cls)
                    6 -> screenOff(at)
                    else -> keyguard(at)
                }
            }
            val r = bound(events, interactive = rnd.nextBoolean(), openAtStart = pkgs.random(rnd).takeIf { rnd.nextBoolean() })
            assertTrue(r.all { it.ms >= 0 && it.startMs >= t0 && it.endMs <= end })
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
