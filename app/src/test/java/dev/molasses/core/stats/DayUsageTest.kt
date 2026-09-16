package dev.molasses.core.stats

import dev.molasses.core.stats.DayUsage.Kind
import dev.molasses.core.stats.DayUsage.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayUsageTest {

    private val midnight = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = midnight + 12 * hour

    private fun on(pkg: String, at: Long) = Transition(pkg, Kind.RESUMED, at)
    private fun off(pkg: String, at: Long) = Transition(pkg, Kind.PAUSED, at)

    @Test
    fun `a paired interval is credited once`() {
        val r = DayUsage.replay(
            listOf(on("a", midnight + hour), off("a", midnight + hour + 20 * minute)),
            midnight, now,
        )
        assertEquals(20 * minute, r.totalMs)
        assertEquals(listOf(DayUsage.Entry("a", 20 * minute, opens = 1)), r.apps)
    }

    @Test
    fun `an interval running past the window end stops at the window end`() {
        val r = DayUsage.replay(
            listOf(on("a", now - 5 * minute), off("a", now + hour)),
            midnight, now,
        )
        assertEquals(5 * minute, r.totalMs)
    }

    @Test
    fun `an interval starting before the window start begins at the window start`() {
        val r = DayUsage.replay(
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
        val r = DayUsage.replay(listOf(off("a", midnight + 30 * minute)), midnight, now)
        assertEquals(30 * minute, r.totalMs)
    }

    @Test
    fun `a later unmatched pause is not credited from the window start again`() {
        // Once the package has appeared in the stream, an unmatched PAUSED is
        // a duplicate or a stray, not evidence of an interval reaching back
        // to midnight. Crediting it again would add half the day per stray.
        val r = DayUsage.replay(
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
        val r = DayUsage.replay(listOf(on("a", now - 7 * minute)), midnight, now)
        assertEquals(7 * minute, r.totalMs)
    }

    @Test
    fun `a duplicate resume keeps the earlier open timestamp`() {
        val r = DayUsage.replay(
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
    fun `interleaved packages each keep their own interval`() {
        val r = DayUsage.replay(
            listOf(
                on("a", midnight + hour),
                on("b", midnight + hour + minute),
                off("a", midnight + hour + 10 * minute),
                off("b", midnight + hour + 4 * minute),
            ),
            midnight, now,
        )
        assertEquals(10 * minute, r.apps.first { it.pkg == "a" }.foregroundMs)
        assertEquals(3 * minute, r.apps.first { it.pkg == "b" }.foregroundMs)
        assertEquals(13 * minute, r.totalMs)
    }

    @Test
    fun `an excluded package contributes nothing`() {
        val r = DayUsage.replay(
            listOf(
                on("dev.molasses", midnight + hour),
                off("dev.molasses", midnight + 3 * hour),
                on("a", midnight + 3 * hour),
                off("a", midnight + 3 * hour + minute),
            ),
            midnight, now, exclude = setOf("dev.molasses"),
        )
        assertEquals(minute, r.totalMs)
        assertTrue(r.apps.none { it.pkg == "dev.molasses" })
    }

    @Test
    fun `the total sums every app and not only the leaders`() {
        // The defect this replaces: the headline summed the top five, so a
        // day spent in fifteen short visits read as a quiet day.
        val events = (1..15).flatMap { i ->
            val start = midnight + i * 10 * minute
            listOf(on("p$i", start), off("p$i", start + 2 * minute))
        }
        val r = DayUsage.replay(events, midnight, now)
        assertEquals(15, r.apps.size)
        assertEquals(30 * minute, r.totalMs)
        assertEquals(5, r.top(5, minMs = minute).size)
        assertEquals(10 * minute, r.top(5, minMs = minute).sumOf { it.foregroundMs })
    }

    @Test
    fun `the distribution floor drops short apps without touching the total`() {
        val r = DayUsage.replay(
            listOf(
                on("long", midnight), off("long", midnight + 30 * minute),
                on("brief", midnight + hour), off("brief", midnight + hour + 20_000L),
            ),
            midnight, now,
        )
        assertEquals(30 * minute + 20_000L, r.totalMs)
        assertEquals(listOf("long"), r.top(5, minMs = minute).map { it.pkg })
    }

    @Test
    fun `apps are ordered longest first with the package name breaking ties`() {
        val r = DayUsage.replay(
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
        val r = DayUsage.replay(
            listOf(on("a", midnight + hour), off("a", midnight + hour)),
            midnight, now,
        )
        assertEquals(emptyList<DayUsage.Entry>(), r.apps)
        assertEquals(0L, r.totalMs)
    }

    @Test
    fun `an empty stream is an empty day and not an error`() {
        val r = DayUsage.replay(emptyList(), midnight, now)
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
            DayUsage.replay(events.sortedBy { it.wallMs }, midnight, now),
            DayUsage.replay(events, midnight, now),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window that ends before it starts is rejected`() {
        DayUsage.replay(emptyList(), now, midnight)
    }
}

class DayUsageOpensTest {

    private val midnight = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = midnight + 12 * hour

    private fun on(pkg: String, at: Long) = Transition(pkg, Kind.RESUMED, at)
    private fun off(pkg: String, at: Long) = Transition(pkg, Kind.PAUSED, at)

    private fun opens(events: List<Transition>, pkg: String = "a"): Int =
        DayUsage.replay(events, midnight, now).entry(pkg)?.opens ?: 0

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
        val r = DayUsage.replay(
            listOf(on("x", midnight), off("x", midnight + hour)),
            midnight, now, exclude = setOf("x"),
        )
        assertNull(r.entry("x"))
    }
}
