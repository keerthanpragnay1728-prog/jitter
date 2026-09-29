package dev.molasses.core.settings

import dev.molasses.core.settings.TargetGrouping.Entry
import dev.molasses.core.settings.TargetGrouping.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetGroupingTest {

    private val insta = Entry("com.instagram.android", "Instagram")
    private val tube = Entry("com.google.android.youtube", "YouTube")
    private val x = Entry("com.twitter.android", "X")
    private val calc = Entry("com.calc", "Calculator")
    private val maps = Entry("com.maps", "Maps")

    private val all = listOf(insta, tube, x, calc, maps)

    private fun rows(
        apps: List<Entry> = all,
        tracked: Set<String> = emptySet(),
        horizons: Map<String, Long> = emptyMap(),
        default: Long = 25 * 60_000L,
    ) = TargetGrouping.rows(apps, tracked) { horizons[it] ?: default }

    @Test
    fun `a fresh install is a plain list with no headers claiming to divide it`() {
        // Nothing tracked, so "UNTRACKED" would be a label over the only
        // thing on screen. This is the screen a beta tester meets first.
        val out = rows()
        assertTrue(out.all { it is Row.App })
        assertEquals(
            listOf("Calculator", "Instagram", "Maps", "X", "YouTube"),
            out.filterIsInstance<Row.App>().map { it.entry.label },
        )
    }

    @Test
    fun `tracking everything also needs no header`() {
        val out = rows(tracked = all.map { it.pkg }.toSet())
        assertTrue(out.none { it is Row.TrackedHeader || it is Row.UntrackedHeader })
    }

    @Test
    fun `tracked comes first, and both headers appear once both buckets exist`() {
        val out = rows(tracked = setOf(insta.pkg, tube.pkg))
        assertEquals(Row.TrackedHeader, out.first())
        val labels = out.map {
            when (it) {
                is Row.App -> it.entry.label
                Row.TrackedHeader -> "<tracked>"
                Row.UntrackedHeader -> "<untracked>"
                is Row.HorizonHeader -> "<horizon>"
            }
        }
        assertEquals(
            listOf(
                "<tracked>", "Instagram", "YouTube",
                "<untracked>", "Calculator", "Maps", "X",
            ),
            labels,
        )
    }

    @Test
    fun `one horizon in use shows no horizon header`() {
        // The common case, and the one the brief's "20m vs 25m" reading would
        // have got wrong. Every tracked app sits at the default until someone
        // moves it, and a lone 25m header labels nothing that is varying.
        val out = rows(tracked = setOf(insta.pkg, tube.pkg))
        assertTrue(out.none { it is Row.HorizonHeader })
    }

    @Test
    fun `two horizons in use show a header before each run, ascending`() {
        val out = rows(
            tracked = setOf(insta.pkg, tube.pkg, x.pkg),
            horizons = mapOf(
                insta.pkg to 15 * 60_000L,
                x.pkg to 15 * 60_000L,
                tube.pkg to 60 * 60_000L,
            ),
        )
        val shape = out.mapNotNull {
            when (it) {
                is Row.HorizonHeader -> "h=${it.horizonMs / 60_000}"
                is Row.App -> if (it.tracked) it.entry.label else null
                else -> null
            }
        }
        assertEquals(listOf("h=15", "Instagram", "X", "h=60", "YouTube"), shape)
    }

    @Test
    fun `a horizon header is emitted once per run, not once per app`() {
        val out = rows(
            tracked = setOf(insta.pkg, tube.pkg, x.pkg),
            horizons = mapOf(
                insta.pkg to 15 * 60_000L,
                x.pkg to 15 * 60_000L,
                tube.pkg to 60 * 60_000L,
            ),
        )
        assertEquals(2, out.count { it is Row.HorizonHeader })
    }

    @Test
    fun `untracked apps are never asked for a horizon`() {
        // An untracked app has no curve, so a horizon for it is a number with
        // nothing on the other end. The lambda throwing is the assertion.
        val out = TargetGrouping.rows(all, setOf(insta.pkg)) { pkg ->
            if (pkg != insta.pkg) error("asked for the horizon of an untracked app: $pkg")
            25 * 60_000L
        }
        assertEquals(null, out.filterIsInstance<Row.App>().first { !it.tracked }.horizonMs)
    }

    @Test
    fun `sorting ignores case, so a lowercase label is not exiled to the end`() {
        val out = rows(apps = listOf(Entry("a", "zebra"), Entry("b", "Apple")))
        assertEquals(
            listOf("Apple", "zebra"),
            out.filterIsInstance<Row.App>().map { it.entry.label },
        )
    }

    @Test
    fun `an empty list produces no rows at all`() {
        assertEquals(emptyList<Row>(), rows(apps = emptyList()))
    }

    @Test
    fun `grouping a filtered list never leaves a header over an empty run`() {
        // The host filters first and groups second. Swept over every subset
        // of a five app list, because a header describing nothing is the
        // exact failure this ordering exists to prevent.
        val tracked = setOf(insta.pkg, tube.pkg)
        for (mask in 0 until (1 shl all.size)) {
            val subset = all.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
            val out = rows(apps = subset, tracked = tracked)
            out.forEachIndexed { i, row ->
                if (row is Row.TrackedHeader || row is Row.UntrackedHeader ||
                    row is Row.HorizonHeader
                ) {
                    val next = out.getOrNull(i + 1)
                    assertTrue("header at $i with no app after it in $subset", next is Row.App)
                }
            }
        }
    }

    @Test
    fun `appsOf keeps render order and drops every header`() {
        val out = rows(tracked = setOf(insta.pkg))
        val apps = TargetGrouping.appsOf(out)
        assertFalse(apps.isEmpty())
        assertEquals(out.filterIsInstance<Row.App>(), apps)
        assertEquals(all.size, apps.size)
    }
}
