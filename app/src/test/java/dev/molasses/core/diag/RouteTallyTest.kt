package dev.molasses.core.diag

import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.IgnoreReason
import dev.molasses.core.session.WindowEvent
import dev.molasses.core.session.WindowEvent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))

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
            t.record(event("com.app$i", Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
        }
        val snap = t.snapshot()
        assertEquals(20, snap.size)
        assertTrue(snap.all { it.second.routed == 0L })
        assertTrue(snap.all { it.second.ignored == 1L })
    }

    @Test
    fun `busiest package sorts first`() {
        val t = RouteTally()
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
        repeat(5) { t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig)) }
        assertEquals(ig, t.snapshot().first().first)
    }

    @Test
    fun `the package map is bounded and overflow is counted`() {
        // A service that runs for weeks with an empty target list would
        // otherwise grow this without limit.
        val t = RouteTally(maxPackages = 4)
        repeat(10) { i ->
            t.record(event("com.app$i", Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
        }
        assertEquals(4, t.snapshot().size)
        assertEquals(6, t.overflowedPackages)
    }

    @Test
    fun `a known package keeps counting after the cap is reached`() {
        val t = RouteTally(maxPackages = 2)
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        t.record(event(other, Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
        repeat(3) { t.record(event("com.filler$it", Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET)) }
        t.record(event(ig, Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))

        assertEquals(2, t.snapshot().toMap().getValue(ig).scrolled)
        assertEquals(3, t.overflowedPackages)
    }

    @Test
    fun `an empty package name is bucketed rather than dropped`() {
        val t = RouteTally()
        t.record(event("", Kind.WINDOWS_CHANGED), EventRoute.Ignore(IgnoreReason.NOT_A_TARGET))
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

/**
 * The split that was missing when it was needed.
 *
 * A total of ignored events says the router rejected them and nothing about
 * which rule did it. The first rule runs before the package is read, so a
 * collision guard eating every event on the device and a wrong target set
 * produce an identical row.
 */
class RouteTallyReasonTest {

    private val ig = "com.instagram.android"
    private val own = "dev.molasses"

    private fun event(pkg: String, kind: WindowEvent.Kind) =
        WindowEvent(pkg, null, 1, kind)

    @Test
    fun `ignored is split by the branch that dropped it`() {
        val t = RouteTally()
        t.record(event(ig, WindowEvent.Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.OWN_WINDOW))
        t.record(event(ig, WindowEvent.Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.OWN_WINDOW))
        t.record(
            event(ig, WindowEvent.Kind.VIEW_SCROLLED),
            EventRoute.Ignore(IgnoreReason.NOT_A_TARGET),
        )

        val tally = t.snapshot().toMap().getValue(ig)
        assertEquals(3, tally.ignored)
        assertEquals(2, tally.ignoredBy(IgnoreReason.OWN_WINDOW))
        assertEquals(1, tally.ignoredBy(IgnoreReason.NOT_A_TARGET))
        assertEquals(0, tally.ignoredBy(IgnoreReason.OWN_PACKAGE))
    }

    @Test
    fun `the two failures that looked identical no longer do`() {
        // Left: the collision guard rejecting a real app. Right: that app
        // simply not being tracked. Same ignored total, different diagnosis,
        // and only one of them can also swallow our own launcher.
        val guard = RouteTally()
        val untracked = RouteTally()
        repeat(75) {
            guard.record(
                event(ig, WindowEvent.Kind.VIEW_SCROLLED),
                EventRoute.Ignore(IgnoreReason.OWN_WINDOW),
            )
            untracked.record(
                event(ig, WindowEvent.Kind.VIEW_SCROLLED),
                EventRoute.Ignore(IgnoreReason.NOT_A_TARGET),
            )
        }
        val a = guard.snapshot().toMap().getValue(ig)
        val b = untracked.snapshot().toMap().getValue(ig)
        assertEquals("the totals are the same, which was the problem", a.ignored, b.ignored)
        assertNotEquals(a.ignoredBy, b.ignoredBy)
    }

    @Test
    fun `a routed event contributes to no reason`() {
        val t = RouteTally()
        t.record(event(ig, WindowEvent.Kind.VIEW_SCROLLED), EventRoute.Scroll(ig))
        val tally = t.snapshot().toMap().getValue(ig)
        assertEquals(1, tally.routed)
        assertEquals(0, tally.ignored)
        assertEquals(emptyMap<IgnoreReason, Long>(), tally.ignoredBy)
    }

    @Test
    fun `our own package being dropped as own-package is ordinary`() {
        // Every scroll inside our own launcher lands here and always has.
        // It is only a finding when the count is OWN_WINDOW instead, because
        // that means the guard ran before the package was ever looked at.
        val t = RouteTally()
        t.record(event(own, WindowEvent.Kind.VIEW_SCROLLED), EventRoute.Ignore(IgnoreReason.OWN_PACKAGE))
        val tally = t.snapshot().toMap().getValue(own)
        assertEquals(1, tally.ignoredBy(IgnoreReason.OWN_PACKAGE))
        assertEquals(0, tally.ignoredBy(IgnoreReason.OWN_WINDOW))
    }
}
