package dev.molasses.core.time

import dev.molasses.core.time.ForegroundIntervals.Event
import dev.molasses.core.time.ForegroundIntervals.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReplayTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val targets = setOf(ig, yt)

    private val t0 = 1_700_000_000_000L

    /** The old shape, so each case reads as it did: package, kind, time. */
    @Suppress("TestFunctionName")
    private fun Transition(pkg: String, kind: Kind, at: Long) = Event(kind, at, pkg)

    /** The reconciler's question, with the screen on unless a test says otherwise. */
    private fun replay(
        transitions: List<Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        targets: Set<String>,
        assumeOpenPkg: String? = null,
        interactiveNow: Boolean = true,
    ) = ForegroundReplay.replay(transitions, windowStartMs, windowEndMs, targets, interactiveNow, assumeOpenPkg)

    @Test
    fun `balanced resume pause pair sums correctly`() {
        val r = replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(ig, Kind.CLOSED, t0 + 90_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(90_000L, r.foregroundMsByPkg[ig])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `a seeded session is closed by the next app to come to the front`() {
        // This test used to credit Instagram the whole 200 s while YouTube
        // was in front from +5 s: the seed stayed open until its own pause,
        // and none came. Two apps are not in front at once, so YouTube's
        // resume ends Instagram's interval. Crediting past it was friction
        // time the user did not spend and never gets back.
        val r = replay(
            transitions = listOf(
                Transition(yt, Kind.RESUMED, t0 + 5_000),
                Transition(yt, Kind.CLOSED, t0 + 15_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 200_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(5_000L, r.foregroundMsByPkg[ig])
        assertEquals(10_000L, r.foregroundMsByPkg[yt])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `mid-session process death still credits up to the window end while the app is in front`() {
        // Reconciler step 2's whole reason for existing: we died while
        // Instagram was still in front, so there is no pause to pair with and
        // the in-memory ticker went with the process.
        val r = replay(
            transitions = emptyList(),
            windowStartMs = t0,
            windowEndMs = t0 + 200_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(200_000L, r.foregroundMsByPkg[ig])
        assertEquals(ig, r.stillOpenPkg)
    }

    @Test
    fun `a non-target app coming to the front bounds a target too`() {
        val r = replay(
            transitions = listOf(Transition(chrome, Kind.RESUMED, t0 + 30_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 200_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(30_000L, r.foregroundMsByPkg[ig])
        assertNull(r.foregroundMsByPkg[chrome])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `the screen going off bounds a session whose pause never came`() {
        val r = replay(
            transitions = listOf(Event(Kind.SCREEN_OFF, t0 + 40_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 3_600_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(40_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `with the screen off now and nothing to bound it, an open session runs to its last evidence only`() {
        val r = replay(
            transitions = listOf(Transition(ig, Kind.RESUMED, t0 + 10_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 3_600_000,
            targets = targets,
            assumeOpenPkg = ig,
            interactiveNow = false,
        )
        assertEquals(10_000L, r.foregroundMsByPkg[ig])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `a reconciler credit can never exceed the bounded interval`() {
        // Over many shapes of stream: what the reconciler credits a target is
        // exactly what the shared bound gives it, and all targets together
        // never exceed the window, because bounded intervals do not overlap.
        val rnd = kotlin.random.Random(11)
        val pkgs = listOf(ig, yt, chrome)
        val end = t0 + 3_600_000
        repeat(300) {
            val events = List(rnd.nextInt(0, 30)) {
                val at = t0 + rnd.nextLong(-60_000, 3_700_000)
                when (rnd.nextInt(5)) {
                    0, 1 -> Transition(pkgs.random(rnd), Kind.RESUMED, at)
                    2 -> Transition(pkgs.random(rnd), Kind.CLOSED, at)
                    3 -> Event(Kind.SCREEN_OFF, at)
                    else -> Event(Kind.KEYGUARD_SHOWN, at)
                }
            }
            val interactive = rnd.nextBoolean()
            val seed = listOf(ig, yt, null).random(rnd)
            val r = replay(events, t0, end, targets, assumeOpenPkg = seed, interactiveNow = interactive)
            val bounded = ForegroundIntervals.bound(events, t0, end, interactive, ForegroundIntervals.Orphan.DROP, seed)
            for (pkg in targets) {
                val cap = bounded.filter { it.pkg == pkg }.sumOf { it.ms }
                assertTrue("$pkg credited ${r.foregroundMsByPkg[pkg]} over its bound $cap", (r.foregroundMsByPkg[pkg] ?: 0L) <= cap)
            }
            assertTrue(r.totalMs <= end - t0)
        }
    }

    @Test
    fun `a resume inside the window does not shorten a seeded open session`() {
        // Deliberate anti-gaming choice. open_session_pkg says Instagram was
        // live at window start; the stream also shows a RESUMED at +10 s
        // (apps fire ACTIVITY_RESUMED per activity, so this is routine
        // in-app navigation, not a fresh launch).
        //
        // Trusting the later RESUMED would credit 190 s instead of 200 s --
        // and, more importantly, would hand out a bypass: kill the process,
        // relaunch, and the reconciler forgets everything before the new
        // RESUMED. Under-crediting is the exploitable direction here, so the
        // seed wins.
        val r = replay(
            transitions = listOf(Transition(ig, Kind.RESUMED, t0 + 10_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 200_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(200_000L, r.foregroundMsByPkg[ig])
        assertEquals(ig, r.stillOpenPkg)
    }

    @Test
    fun `open session that began before the window is credited from the window start`() {
        // open_session_pkg says Instagram was live, but the RESUMED happened
        // before last_seen_wall_ms so it is not in the queried range.
        val r = replay(
            transitions = emptyList(),
            windowStartMs = t0,
            windowEndMs = t0 + 45_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(45_000L, r.foregroundMsByPkg[ig])
        assertEquals(ig, r.stillOpenPkg)
    }

    @Test
    fun `a death mid-session followed by a pause we did observe`() {
        val r = replay(
            transitions = listOf(Transition(ig, Kind.CLOSED, t0 + 30_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals("only up to the pause", 30_000L, r.foregroundMsByPkg[ig])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `non-target packages are ignored entirely`() {
        val r = replay(
            transitions = listOf(
                Transition(chrome, Kind.RESUMED, t0),
                Transition(chrome, Kind.CLOSED, t0 + 60_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertTrue(r.foregroundMsByPkg.isEmpty())
        assertEquals(0L, r.totalMs)
    }

    @Test
    fun `interleaved target apps are accounted separately`() {
        val r = replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(yt, Kind.RESUMED, t0 + 20_000),
                Transition(ig, Kind.CLOSED, t0 + 25_000),
                Transition(yt, Kind.CLOSED, t0 + 80_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        // Instagram's interval ends at YouTube's resume, not at its own late
        // pause: two apps are not in front at once.
        assertEquals(20_000L, r.foregroundMsByPkg[ig])
        assertEquals(60_000L, r.foregroundMsByPkg[yt])
    }

    @Test
    fun `a duplicate resume keeps the earlier start`() {
        val r = replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(ig, Kind.RESUMED, t0 + 30_000),
                Transition(ig, Kind.CLOSED, t0 + 60_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(60_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `an orphan pause is dropped rather than credited`() {
        val r = replay(
            transitions = listOf(Transition(ig, Kind.CLOSED, t0 + 30_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertTrue(r.foregroundMsByPkg.isEmpty())
    }

    @Test
    fun `transitions outside the window are clamped into it`() {
        val r = replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0 - 500_000),
                Transition(ig, Kind.CLOSED, t0 + 500_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 60_000,
            targets = targets,
        )
        assertEquals(60_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `unsorted input is handled`() {
        val r = replay(
            transitions = listOf(
                Transition(ig, Kind.CLOSED, t0 + 60_000),
                Transition(ig, Kind.RESUMED, t0),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(60_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `an empty window credits nothing`() {
        val r = replay(
            transitions = emptyList(),
            windowStartMs = t0,
            windowEndMs = t0,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(0L, r.totalMs)
    }
}
