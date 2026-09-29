package dev.molasses.core.session

import dev.molasses.core.session.WindowEvent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collision created by adding our own package to `packageNames`.
 *
 * Every overlay this app shows belongs to our own package (`org.jitteros.app`). If those events are
 * not filtered, showing the movement gate is indistinguishable from the user
 * going home, and the service closes the session it is gating.
 */
class ForegroundEventRouterTest {

    private val own = "org.jitteros.app"
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val targets = setOf(ig, yt)
    private val router = ForegroundEventRouter(own, ForegroundEventRouter.LAUNCHER_CLASS_NAME)

    private fun route(e: WindowEvent) = router.route(e, targets)

    // ------------------------------------------- our own windows never exit

    @Test
    fun `showing the gate does not read as leaving the app`() {
        // The gate is a ComposeView in a TYPE_ACCESSIBILITY_OVERLAY window. It
        // has no activity class, so a class check alone would not catch it.
        val gate = WindowEvent(own, "android.widget.FrameLayout", 77, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(gate))
    }

    @Test
    fun `dragging the mascot does not read as leaving the app`() {
        val mascot = WindowEvent(own, null, 78, Kind.WINDOWS_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(mascot))
    }

    @Test
    fun `arming the shutter sink does not read as leaving the app`() {
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(sink))
    }

    @Test
    fun `an overlay is dropped even when the window id was not registered yet`() {
        // Belt and braces. The id set is refreshed asynchronously after
        // addView, so an event can arrive in the gap. The package check is the
        // second line of defence.
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(sink))
    }

    @Test
    fun `the window id is not consulted at all`() {
        // There was a rule above the package check that dropped any event
        // whose window id was one we had added. It is gone: under this app's
        // profile getWindowId() is -1 for every event, so the rule learned -1
        // as ours and then matched it against the entire device.
        //
        // Same event, four different ids, one answer. If a window id ever
        // decides a route again, this fails.
        for (id in listOf(-1, 0, 77, Int.MAX_VALUE)) {
            val gate = WindowEvent(own, "android.widget.FrameLayout", id, Kind.WINDOW_STATE_CHANGED)
            assertEquals("id=$id", EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(gate))
            val scroll = WindowEvent(ig, null, id, Kind.VIEW_SCROLLED)
            assertEquals("id=$id", EventRoute.Scroll(ig), route(scroll))
        }
    }

    @Test
    fun `an event wearing the launcher class name is an exit whatever its id`() {
        // The old rule dropped this when the id matched one of ours. Nothing
        // we add is ever the launcher, so the class name is sufficient and
        // the id was never adding anything here.
        val e = WindowEvent(
            own, ForegroundEventRouter.LAUNCHER_CLASS_NAME, -1, Kind.WINDOW_STATE_CHANGED,
        )
        assertEquals(EventRoute.ExitToHome, route(e))
    }

    // ------------------------------------------------------ the launcher

    @Test
    fun `reaching the launcher ends the session`() {
        val home = WindowEvent(
            own, ForegroundEventRouter.LAUNCHER_CLASS_NAME, 12, Kind.WINDOW_STATE_CHANGED,
        )
        assertEquals(EventRoute.ExitToHome, route(home))
    }

    @Test
    fun `a windows-changed event from the launcher is not an exit`() {
        // Only a window state change names the activity that took the screen.
        val home = WindowEvent(
            own, ForegroundEventRouter.LAUNCHER_CLASS_NAME, 12, Kind.WINDOWS_CHANGED,
        )
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(home))
    }

    // --------------------------------------------------------- target apps

    @Test
    fun `a target window state change enters that app`() {
        val e = WindowEvent(ig, "com.instagram.MainActivity", 3, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.EnterTarget(ig), route(e))
    }

    @Test
    fun `a target scroll routes as a scroll`() {
        val e = WindowEvent(ig, "androidx.recyclerview.widget.RecyclerView", 3, Kind.VIEW_SCROLLED)
        assertEquals(EventRoute.Scroll(ig), route(e))
    }

    @Test
    fun `a windows-changed hint from a target asks usage stats rather than guessing`() {
        val e = WindowEvent(yt, null, 4, Kind.WINDOWS_CHANGED)
        assertEquals(EventRoute.ProbeForeground, route(e))
    }

    @Test
    fun `a package that is neither ours nor a target is ignored`() {
        // Cannot happen while packageNames is scoped, but the router must not
        // depend on that filtering being correct.
        val e = WindowEvent("com.android.chrome", "x", 9, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.NOT_A_TARGET), route(e))
    }

    @Test
    fun `a package removed from the target list stops routing`() {
        val e = WindowEvent(ig, "x", 3, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.NOT_A_TARGET), router.route(e, setOf(yt)))
    }

    // ------------------------------------------- the degraded guard (no flag)

    /**
     * The package check, carrying the collision guard alone.
     *
     * It always was. `flagRetrieveInteractiveWindows` is gone for banking-app
     * compatibility, so `getWindows()` returns empty and the window-id rule
     * that replaced it could never match: `getWindowId()` is -1 for every
     * event under this profile. The rule is deleted, and these are what is
     * left standing. If any of them regressed, showing a gate would read as
     * the user going home and close the session underneath.
     *
     * Device verified: the id rule was dropping every event from every
     * package until it was removed.
     */
    @Test
    fun `an overlay event is ignored even when no window ids are known`() {
        for (kind in Kind.entries) {
            val route = router.route(
                WindowEvent(
                    packageName = own,
                    // A gate or the touch sink: a plain view class, not an
                    // activity, so a class-name check alone would not catch it.
                    className = "android.widget.FrameLayout",
                    windowId = 4242,
                    kind = kind,
                ),
            targets = targets,
            )
            assertEquals("kind=$kind", EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route)
        }
    }

    @Test
    fun `a compose overlay event is ignored even when no window ids are known`() {
        val route = router.route(
            WindowEvent(
                packageName = own,
                className = "androidx.compose.ui.platform.ComposeView",
                windowId = 77,
                kind = Kind.WINDOW_STATE_CHANGED,
            ),
            targets = targets,
        )
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route)
    }

    @Test
    fun `an overlay event with no class name at all is ignored`() {
        // Some overlay windows report a null class. The package check is the
        // only thing left standing in that case.
        val route = router.route(
            WindowEvent(
                packageName = own,
                className = null,
                windowId = 9,
                kind = Kind.WINDOW_STATE_CHANGED,
            ),
            targets = targets,
        )
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route)
    }

    @Test
    fun `the launcher still reads as an exit under the degraded guard`() {
        // The guard must not have degraded into "ignore everything from our
        // own package", or leaving a target app for the launcher would stop
        // closing the session.
        val route = router.route(
            WindowEvent(
                packageName = own,
                className = ForegroundEventRouter.LAUNCHER_CLASS_NAME,
                windowId = 3,
                kind = Kind.WINDOW_STATE_CHANGED,
            ),
            targets = targets,
        )
        assertEquals(EventRoute.ExitToHome, route)
    }
}

/**
 * The one case where an event from our own overlay can wear someone else's
 * package name, and what stops it doing damage.
 *
 * `TYPE_WINDOWS_CHANGED` is not sourced from a view. It is emitted because the
 * window stack moved, and which package the platform attributes it to is
 * version dependent. Adding the lease gate over Instagram can plausibly
 * produce one attributed to `com.instagram.android` rather than to us, so the
 * package check in rule 1 misses it and it reaches rule 2 as a target event.
 *
 * That is the only hole in resting the collision guard on the package name,
 * and it is closed by where the event lands rather than by catching it:
 * `WINDOWS_CHANGED` from a target routes to [EventRoute.ProbeForeground],
 * which asks `UsageStatsManager` who is really in front. It opens no session,
 * closes none, and attributes no time.
 *
 * Before this file, that was true by reading. These assert it.
 */
class WindowsChangedProbeTest {

    private val own = "org.jitteros.app"
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val targets = setOf(ig, yt)
    private val router = ForegroundEventRouter(own, ForegroundEventRouter.LAUNCHER_CLASS_NAME)

    private fun route(e: WindowEvent) = router.route(e, targets)

    @Test
    fun `a windows-changed from a target probes and never enters`() {
        // The damaging misread would be EnterTarget: it opens a session and
        // starts accruing time against an app the user may not be in.
        for (pkg in targets) {
            val e = WindowEvent(pkg, null, -1, Kind.WINDOWS_CHANGED)
            assertEquals("pkg=$pkg", EventRoute.ProbeForeground, route(e))
        }
    }

    @Test
    fun `a windows-changed from a target never reads as an exit`() {
        // The other damaging misread: closing the session the gate is
        // covering, which is the original Phase 0.1 collision.
        val e = WindowEvent(ig, ForegroundEventRouter.LAUNCHER_CLASS_NAME, -1, Kind.WINDOWS_CHANGED)
        assertEquals(EventRoute.ProbeForeground, route(e))
    }

    @Test
    fun `the class name on a windows-changed cannot change the route`() {
        // Whatever the platform puts in className when our overlay provokes
        // the event, the route is the same. Only the kind decides.
        val names = listOf(
            null,
            "android.widget.FrameLayout",
            "androidx.compose.ui.platform.ComposeView",
            ForegroundEventRouter.LAUNCHER_CLASS_NAME,
            "com.instagram.android.MainActivity",
        )
        for (name in names) {
            val e = WindowEvent(ig, name, -1, Kind.WINDOWS_CHANGED)
            assertEquals("className=$name", EventRoute.ProbeForeground, route(e))
        }
    }

    @Test
    fun `probing is the only route a windows-changed can take`() {
        // Stated over the whole input space this event kind can present, so a
        // future branch cannot quietly give WINDOWS_CHANGED a second meaning.
        val routes = buildList {
            for (pkg in listOf(ig, yt, own, "com.android.chrome")) {
                for (name in listOf(null, "x", ForegroundEventRouter.LAUNCHER_CLASS_NAME)) {
                    add(route(WindowEvent(pkg, name, -1, Kind.WINDOWS_CHANGED)))
                }
            }
        }
        // A target probes; our own package and an untracked one are ignored.
        // Nothing enters, nothing exits.
        assertTrue(routes.none { it is EventRoute.EnterTarget })
        assertTrue(routes.none { it == EventRoute.ExitToHome })
        assertTrue(routes.none { it is EventRoute.Scroll })
        assertEquals(6, routes.count { it == EventRoute.ProbeForeground })
    }
}
