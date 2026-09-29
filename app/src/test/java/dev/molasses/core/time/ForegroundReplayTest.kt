package dev.molasses.core.time

import dev.molasses.core.time.ForegroundReplay.Kind
import dev.molasses.core.time.ForegroundReplay.Transition
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

    @Test
    fun `balanced resume pause pair sums correctly`() {
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(ig, Kind.PAUSED, t0 + 90_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(90_000L, r.foregroundMsByPkg[ig])
        assertNull(r.stillOpenPkg)
    }

    @Test
    fun `mid-session process death credits time up to the window end`() {
        // Reconciler step 2's whole reason for existing: we died while
        // Instagram was still in front, so there is no PAUSED to pair with and
        // the in-memory ticker went with the process.
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(yt, Kind.RESUMED, t0 + 5_000),
                Transition(yt, Kind.PAUSED, t0 + 15_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 200_000,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(200_000L, r.foregroundMsByPkg[ig])
        assertEquals(10_000L, r.foregroundMsByPkg[yt])
        assertEquals(ig, r.stillOpenPkg)
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
        val r = ForegroundReplay.replay(
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
        val r = ForegroundReplay.replay(
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
        val r = ForegroundReplay.replay(
            transitions = listOf(Transition(ig, Kind.PAUSED, t0 + 30_000)),
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
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(chrome, Kind.RESUMED, t0),
                Transition(chrome, Kind.PAUSED, t0 + 60_000),
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
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(yt, Kind.RESUMED, t0 + 20_000),
                Transition(ig, Kind.PAUSED, t0 + 25_000),
                Transition(yt, Kind.PAUSED, t0 + 80_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(25_000L, r.foregroundMsByPkg[ig])
        assertEquals(60_000L, r.foregroundMsByPkg[yt])
    }

    @Test
    fun `a duplicate resume keeps the earlier start`() {
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0),
                Transition(ig, Kind.RESUMED, t0 + 30_000),
                Transition(ig, Kind.PAUSED, t0 + 60_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertEquals(60_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `an orphan pause is dropped rather than credited`() {
        val r = ForegroundReplay.replay(
            transitions = listOf(Transition(ig, Kind.PAUSED, t0 + 30_000)),
            windowStartMs = t0,
            windowEndMs = t0 + 120_000,
            targets = targets,
        )
        assertTrue(r.foregroundMsByPkg.isEmpty())
    }

    @Test
    fun `transitions outside the window are clamped into it`() {
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(ig, Kind.RESUMED, t0 - 500_000),
                Transition(ig, Kind.PAUSED, t0 + 500_000),
            ),
            windowStartMs = t0,
            windowEndMs = t0 + 60_000,
            targets = targets,
        )
        assertEquals(60_000L, r.foregroundMsByPkg[ig])
    }

    @Test
    fun `unsorted input is handled`() {
        val r = ForegroundReplay.replay(
            transitions = listOf(
                Transition(ig, Kind.PAUSED, t0 + 60_000),
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
        val r = ForegroundReplay.replay(
            transitions = emptyList(),
            windowStartMs = t0,
            windowEndMs = t0,
            targets = targets,
            assumeOpenPkg = ig,
        )
        assertEquals(0L, r.totalMs)
    }
}
