package dev.molasses.core.diag

import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.WindowEvent
import dev.molasses.core.session.WindowEvent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTallyTest {

    private val ig = "com.instagram.android"
    private val other = "com.example.other"

    private fun event(pkg: String, kind: Kind, id: Int = 1) =
        WindowEvent(pkg, null, id, kind)

    @Test
    fun `counts by kind`() {
        val t = RouteTally()
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.record(event(ig, Kind.WINDOW_STATE_CHANGED), EventRoute.EnterTarget(ig))
        t.record(event(ig, Kind.WINDOWS_CHANGED), EventRoute.ProbeForeground)

        val tally = t.snapshot().single().second
        assertEquals(2, tally.scrolled)
        assertEquals(1, tally.windowState)
        assertEquals(1, tally.windowsChanged)
        assertEquals(4, tally.total)
    }

    @Test
    fun `routed and ignored are counted separately`() {
        // The whole point: "no events" and "every event ignored" look
        // identical from outside and have completely different fixes.
        val t = RouteTally()
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore)
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore)

        val byPkg = t.snapshot().toMap()
        assertEquals(1, byPkg.getValue(ig).routed)
        assertEquals(0, byPkg.getValue(ig).ignored)
        assertEquals(0, byPkg.getValue(other).routed)
        assertEquals(2, byPkg.getValue(other).ignored)
    }

    @Test
    fun `the empty-target signature is visible in the numbers`() {
        // Many packages, all ignored, nothing routed. That is what an empty
        // stored target list produces, and it should be unmistakable.
        val t = RouteTally()
        repeat(20) { i ->
            t.record(event("com.app$i", Kind.VIEW_SCROLLED), EventRoute.Ignore)
        }
        val snap = t.snapshot()
        assertEquals(20, snap.size)
        assertTrue(snap.all { it.second.routed == 0L })
        assertTrue(snap.all { it.second.ignored == 1L })
    }

    @Test
    fun `busiest package sorts first`() {
        val t = RouteTally()
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore)
        repeat(5) { t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig)) }
        assertEquals(ig, t.snapshot().first().first)
    }

    @Test
    fun `the package map is bounded and overflow is counted`() {
        // A service that runs for weeks with an empty target list would
        // otherwise grow this without limit.
        val t = RouteTally(maxPackages = 4)
        repeat(10) { i ->
            t.record(event("com.app$i", Kind.VIEW_SCROLLED), EventRoute.Ignore)
        }
        assertEquals(4, t.snapshot().size)
        assertEquals(6, t.overflowedPackages)
    }

    @Test
    fun `a known package keeps counting after the cap is reached`() {
        val t = RouteTally(maxPackages = 2)
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore)
        repeat(3) { t.record(event("com.filler$it", Kind.VIEW_SCROLLED), EventRoute.Ignore) }
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))

        assertEquals(2, t.snapshot().toMap().getValue(ig).scrolled)
        assertEquals(3, t.overflowedPackages)
    }

    @Test
    fun `an empty package name is bucketed rather than dropped`() {
        val t = RouteTally()
        t.record(event("", Kind.WINDOWS_CHANGED), EventRoute.Ignore)
        assertEquals(RouteTally.UNKNOWN, t.snapshot().single().first)
    }

    @Test
    fun `reset clears everything`() {
        val t = RouteTally()
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.reset()
        assertTrue(t.snapshot().isEmpty())
        assertEquals(0, t.overflowedPackages)
    }
}
