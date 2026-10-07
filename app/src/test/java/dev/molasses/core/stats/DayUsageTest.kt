package dev.molasses.core.stats

import dev.molasses.core.time.ForegroundIntervals.Event
import dev.molasses.core.time.ForegroundIntervals.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayUsageTest {

    private val midnight = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = midnight + 12 * hour

    private fun on(pkg: String, at: Long) = Event(Kind.RESUMED, at, pkg)
    private fun off(pkg: String, at: Long) = Event(Kind.PAUSED, at, pkg)

    /** The ledger's question, read with the screen on unless a test says otherwise. */
    private fun replay(
        events: List<Event>,
        start: Long,
        end: Long,
        exclude: Set<String> = emptySet(),
        interactive: Boolean = true,
    ) = DayUsage.replay(events, start, end, interactive, exclude)

    @Test
    fun `a paired interval is credited once`() {
        val r = replay(
            listOf(on("a", midnight + hour), off("a", midnight + hour + 20 * minute)),
            midnight, now,
        )
        assertEquals(20 * minute, r.totalMs)
        assertEquals(listOf(DayUsage.Entry("a", 20 * minute, opens = 1)), r.apps)
    }

    @Test
    fun `an interval running past the window end stops at the window end`() {
        val r = replay(
            listOf(on("a", now - 5 * minute), off("a", now + hour)),
            midnight, now,
        )
        assertEquals(5 * minute, r.totalMs)
    }

    @Test
    fun `an interval starting before the window start begins at the window start`() {
        val r = replay(
            listOf(on("a", midnight - hour), off("a", midnight + 10 * minute)),
            midnight, now,
        )
        assertEquals(10 * minute, r.totalMs)
    }

    @Test
    fun `a pause with no resume is credited from the window start`() {
        // The straddling case that queryAndAggregateUsageStats got wrong: an
        // app in front across midnight. Its RESUMED is yesterday's, so today
        // only ever sees the PAUSED.
        val r = replay(listOf(off("a", midnight + 30 * minute)), midnight, now)
        assertEquals(30 * minute, r.totalMs)
    }

    @Test
    fun `a later unmatched pause is not credited from the window start again`() {
        // Once the package has appeared in the stream, an unmatched PAUSED is
        // a duplicate or a stray, not evidence of an interval reaching back
        // to midnight. Crediting it again would add half the day per stray.
        val r = replay(
            listOf(
                on("a", midnight + hour),
                off("a", midnight + hour + minute),
                off("a", midnight + 2 * hour),
            ),
            midnight, now,
        )
        assertEquals(minute, r.totalMs)
    }

    @Test
    fun `a package still open at the end is credited to the end`() {
        val r = replay(listOf(on("a", now - 7 * minute)), midnight, now)
        assertEquals(7 * minute, r.totalMs)
    }

    @Test
    fun `a duplicate resume keeps the earlier open timestamp`() {
        val r = replay(
            listOf(
                on("a", midnight + hour),
                on("a", midnight + hour + 5 * minute),
                off("a", midnight + hour + 8 * minute),
            ),
            midnight, now,
        )
        assertEquals(8 * minute, r.totalMs)
    }

    @Test
    fun `another package's resume closes the one before it`() {
        // This test used to credit a for ten minutes, overlapping b's three:
        // each package kept its own interval until its own pause, however
        // late. Two apps are not in front at once, so b's resume ends a's
        // interval whether or not a's pause ever arrives. See
        // ForegroundIntervals.
        val r = replay(
            listOf(
                on("a", midnight + hour),
                on("b", midnight + hour + minute),
                off("a", midnight + hour + 10 * minute),
                off("b", midnight + hour + 4 * minute),
            ),
            midnight, now,
        )
        assertEquals(minute, r.apps.first { it.pkg == "a" }.foregroundMs)
        assertEquals(3 * minute, r.apps.first { it.pkg == "b" }.foregroundMs)
        assertEquals(4 * minute, r.totalMs)
    }

    @Test
    fun `an excluded package still bounds everyone else`() {
        // The console in front means nothing else is, even though the
        // console's own time is left out of the day.
        val r = replay(
            listOf(on("a", midnight + hour), on("org.jitteros.app", midnight + hour + 2 * minute)),
            midnight, now, exclude = setOf("org.jitteros.app"),
        )
        assertEquals(2 * minute, r.totalMs)
        assertNull(r.entry("org.jitteros.app"))
    }

    @Test
    fun `the YONO shape reads as seconds, not the hours since`() {
        // One resume, no close of any kind, the screen going off later, and
        // the ledger read hours afterwards. Unbounded, this was every hour
        // from the resume to the reading.
        val yono = "com.sbi.lotusintouch"
        val r = replay(
            listOf(
                on(yono, midnight + hour),
                Event(Kind.SCREEN_OFF, midnight + hour + 3 * minute),
                Event(Kind.SCREEN_OFF, midnight + 5 * hour),
            ),
            midnight, now,
        )
        assertEquals(3 * minute, r.entry(yono)!!.foregroundMs)
    }

    @Test
    fun `an excluded package contributes nothing`() {
        val r = replay(
            listOf(
                on("org.jitteros.app", midnight + hour),
                off("org.jitteros.app", midnight + 3 * hour),
                on("a", midnight + 3 * hour),
                off("a", midnight + 3 * hour + minute),
            ),
            midnight, now, exclude = setOf("org.jitteros.app"),
        )
        assertEquals(minute, r.totalMs)
        assertTrue(r.apps.none { it.pkg == "org.jitteros.app" })
    }

    @Test
    fun `the total sums every app and not only the leaders`() {
        // The defect this replaces: the headline summed the top five, so a
        // day spent in fifteen short visits read as a quiet day.
        val events = (1..15).flatMap { i ->
            val start = midnight + i * 10 * minute
            listOf(on("p$i", start), off("p$i", start + 2 * minute))
        }
        val r = replay(events, midnight, now)
        assertEquals(15, r.apps.size)
        assertEquals(30 * minute, r.totalMs)
        val d = r.distribution(5, minMs = minute)
        assertEquals(5, d.rows.size)
        assertEquals(10 * minute, d.rows.sumOf { it.foregroundMs })
        assertEquals("the ten that did not fit are counted", 10, d.pastCap)
        assertEquals(0, d.underFloor)
    }

    @Test
    fun `the distribution floor drops short apps without touching the total`() {
        val r = replay(
            listOf(
                on("long", midnight), off("long", midnight + 30 * minute),
                on("brief", midnight + hour), off("brief", midnight + hour + 20_000L),
            ),
            midnight, now,
        )
        assertEquals(30 * minute + 20_000L, r.totalMs)
        val d = r.distribution(5, minMs = minute)
        assertEquals(listOf("long"), d.rows.map { it.pkg })
        assertEquals("the brief app is hidden, and counted", 1, d.underFloor)
        assertEquals(0, d.pastCap)
    }

    @Test
    fun `a two-minute app is listed, and a sub-minute one is counted, not dropped`() {
        // The device report: Instagram for about two minutes and a brief
        // Chess, after Settings. The rule lists the first and counts the
        // second; neither is ever silently missing.
        val r = replay(
            listOf(
                on("settings", midnight + hour), off("settings", midnight + hour + 3 * minute),
                on("instagram", midnight + 2 * hour), off("instagram", midnight + 2 * hour + 2 * minute),
                on("chess", midnight + 3 * hour), off("chess", midnight + 3 * hour + 25_000L),
            ),
            midnight, now,
        )
        val d = r.distribution(5, minMs = minute)
        assertEquals(listOf("settings", "instagram"), d.rows.map { it.pkg })
        assertEquals(1, d.underFloor)
        assertEquals(0, d.pastCap)
    }

    @Test
    fun `exactly the floor is listed, and a day of only short apps still says there were some`() {
        val atFloor = replay(listOf(on("a", midnight), off("a", midnight + minute)), midnight, now)
        assertEquals(listOf("a"), atFloor.distribution(5, minMs = minute).rows.map { it.pkg })
        val onlyShort = replay(listOf(on("a", midnight), off("a", midnight + 10_000L)), midnight, now)
        val d = onlyShort.distribution(5, minMs = minute)
        assertEquals(emptyList<DayUsage.Entry>(), d.rows)
        assertEquals(1, d.underFloor)
    }

    @Test
    fun `an app still in front at the moment of reading is counted up to then`() {
        // U4 pinned this unconditionally: an open interval ran to the window
        // end, which ruled out cause (c), the current app going missing. It
        // now holds only for the app that is actually current: the most
        // recent foreground event, with the screen interactive. Run to the
        // end for any open interval, it credited an app whose pause never
        // arrived with every hour up to the reading (YONO SBI on a device:
        // seconds of use, hours on the ledger).
        val r = replay(listOf(on("instagram", now - 2 * minute)), midnight, now)
        assertEquals(listOf("instagram"), r.distribution(5, minMs = minute).rows.map { it.pkg })
        assertEquals(2 * minute, r.entry("instagram")!!.foregroundMs)
        // Not current: a later resume by another app bounds it.
        val replaced = replay(listOf(on("instagram", now - 2 * hour), on("chess", now - hour)), midnight, now)
        assertEquals(hour, replaced.entry("instagram")!!.foregroundMs)
        // Current, but the screen is off: it closes at its last evidence.
        val dark = replay(listOf(on("instagram", now - 2 * minute)), midnight, now, interactive = false)
        assertNull(dark.entry("instagram"))
    }

    @Test
    fun `apps are ordered longest first with the package name breaking ties`() {
        val r = replay(
            listOf(
                on("b", midnight), off("b", midnight + 5 * minute),
                on("a", midnight + hour), off("a", midnight + hour + 5 * minute),
                on("c", midnight + 2 * hour), off("c", midnight + 2 * hour + 9 * minute),
            ),
            midnight, now,
        )
        assertEquals(listOf("c", "a", "b"), r.apps.map { it.pkg })
    }

    @Test
    fun `a zero length interval is not an app`() {
        val r = replay(
            listOf(on("a", midnight + hour), off("a", midnight + hour)),
            midnight, now,
        )
        assertEquals(emptyList<DayUsage.Entry>(), r.apps)
        assertEquals(0L, r.totalMs)
    }

    @Test
    fun `an empty stream is an empty day and not an error`() {
        val r = replay(emptyList(), midnight, now)
        assertEquals(0L, r.totalMs)
        assertEquals(emptyList<DayUsage.Entry>(), r.apps)
    }

    @Test
    fun `an unsorted stream gives the same answer as a sorted one`() {
        val events = listOf(
            off("a", midnight + hour + 10 * minute),
            on("b", midnight + hour + minute),
            on("a", midnight + hour),
            off("b", midnight + hour + 4 * minute),
        )
        assertEquals(
            replay(events.sortedBy { it.wallMs }, midnight, now),
            replay(events, midnight, now),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window that ends before it starts is rejected`() {
        replay(emptyList(), now, midnight)
    }
}

class DayUsageOpensTest {

    private val midnight = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = midnight + 12 * hour

    private fun on(pkg: String, at: Long) = Event(Kind.RESUMED, at, pkg)
    private fun off(pkg: String, at: Long) = Event(Kind.PAUSED, at, pkg)

    /** The ledger's question, read with the screen on unless a test says otherwise. */
    private fun replay(
        events: List<Event>,
        start: Long,
        end: Long,
        exclude: Set<String> = emptySet(),
        interactive: Boolean = true,
    ) = DayUsage.replay(events, start, end, interactive, exclude)

    private fun opens(events: List<Event>, pkg: String = "a"): Int =
        replay(events, midnight, now).entry(pkg)?.opens ?: 0

    @Test
    fun `one visit is one open`() {
        assertEquals(1, opens(listOf(on("a", midnight + hour), off("a", midnight + 2 * hour))))
    }

    @Test
    fun `a visit still open at the end counts`() {
        assertEquals(1, opens(listOf(on("a", now - minute))))
    }

    @Test
    fun `two separated visits are two opens`() {
        assertEquals(
            2,
            opens(
                listOf(
                    on("a", midnight + hour), off("a", midnight + hour + minute),
                    on("a", midnight + 3 * hour), off("a", midnight + 3 * hour + minute),
                ),
            ),
        )
    }

    @Test
    fun `an activity transition inside one visit is not a second open`() {
        // The reason the visit gap exists. A multi-activity app pauses and
        // resumes crossing between its own screens, and counting those would
        // report six opens for two.
        assertEquals(
            1,
            opens(
                listOf(
                    on("a", midnight + hour),
                    off("a", midnight + hour + 30_000L),
                    on("a", midnight + hour + 30_500L),
                    off("a", midnight + hour + 90_000L),
                ),
            ),
        )
    }

    @Test
    fun `a gap of exactly the visit threshold is a new open`() {
        assertEquals(
            2,
            opens(
                listOf(
                    on("a", midnight + hour),
                    off("a", midnight + hour + minute),
                    on("a", midnight + hour + minute + DayUsage.VISIT_GAP_MS),
                    off("a", midnight + hour + 2 * minute + DayUsage.VISIT_GAP_MS),
                ),
            ),
        )
    }

    @Test
    fun `a duplicate resume inside an open interval is not an open`() {
        assertEquals(
            1,
            opens(
                listOf(
                    on("a", midnight + hour),
                    on("a", midnight + hour + 5 * minute),
                    off("a", midnight + hour + 6 * minute),
                ),
            ),
        )
    }

    @Test
    fun `a visit that began before the window counts once inside it`() {
        assertEquals(1, opens(listOf(off("a", midnight + 20 * minute))))
    }

    @Test
    fun `opens are counted per package`() {
        val events = listOf(
            on("a", midnight + hour), off("a", midnight + hour + minute),
            on("b", midnight + 2 * hour), off("b", midnight + 2 * hour + minute),
            on("b", midnight + 4 * hour), off("b", midnight + 4 * hour + minute),
        )
        assertEquals(1, opens(events, "a"))
        assertEquals(2, opens(events, "b"))
    }

    @Test
    fun `an excluded package has no row to read opens from`() {
        val r = replay(
            listOf(on("x", midnight), off("x", midnight + hour)),
            midnight, now, exclude = setOf("x"),
        )
        assertNull(r.entry("x"))
    }
}
