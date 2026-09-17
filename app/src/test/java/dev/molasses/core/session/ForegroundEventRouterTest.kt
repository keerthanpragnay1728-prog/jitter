package dev.molasses.core.session

import dev.molasses.core.session.WindowEvent.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collision created by adding our own package to `packageNames`.
 *
 * Every overlay this app shows belongs to `dev.molasses`. If those events are
 * not filtered, showing the movement gate is indistinguishable from the user
 * going home, and the service closes the session it is gating.
 */
class ForegroundEventRouterTest {

    private val own = "dev.molasses"
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val targets = setOf(ig, yt)
    private val router = ForegroundEventRouter(own, ForegroundEventRouter.LAUNCHER_CLASS_NAME)

    private fun route(e: WindowEvent, ownWindows: Set<Int> = emptySet()) =
        router.route(e, targets, ownWindows)

    // ------------------------------------------- our own windows never exit

    @Test
    fun `showing the gate does not read as leaving the app`() {
        // The gate is a ComposeView in a TYPE_ACCESSIBILITY_OVERLAY window. It
        // has no activity class, so a class check alone would not catch it.
        val gate = WindowEvent(own, "android.widget.FrameLayout", 77, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_WINDOW), route(gate, ownWindows = setOf(77)))
    }

    @Test
    fun `dragging the mascot does not read as leaving the app`() {
        val mascot = WindowEvent(own, null, 78, Kind.WINDOWS_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_WINDOW), route(mascot, ownWindows = setOf(77, 78)))
    }

    @Test
    fun `arming the shutter sink does not read as leaving the app`() {
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_WINDOW), route(sink, ownWindows = setOf(79)))
    }

    @Test
    fun `an overlay is dropped even when the window id was not registered yet`() {
        // Belt and braces. The id set is refreshed asynchronously after
        // addView, so an event can arrive in the gap. The package check is the
        // second line of defence.
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), route(sink, ownWindows = emptySet()))
    }

    @Test
    fun `the window id check runs before anything else`() {
        // Even an event wearing the launcher class name is dropped if it came
        // from a window we own. Nothing we add is ever the launcher.
        val impostor = WindowEvent(
            own, ForegroundEventRouter.LAUNCHER_CLASS_NAME, 77, Kind.WINDOW_STATE_CHANGED,
        )
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_WINDOW), route(impostor, ownWindows = setOf(77)))
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
        assertEquals(EventRoute.Ignore(IgnoreReason.NOT_A_TARGET), router.route(e, setOf(yt), emptySet()))
    }

    // ------------------------------------------- the degraded guard (no flag)

    /**
     * `flagRetrieveInteractiveWindows` is gone for banking-app compatibility,
     * so `getWindows()` returns an empty list and the service can no longer
     * enumerate its own windows up front. The id set is learned from events
     * instead, which means it is **empty for the very first event from a new
     * overlay window**.
     *
     * These tests pin the behaviour in exactly that worst case: an empty id
     * set, which is what the guard degrades to. If rule 2 (the package-name
     * check) did not carry it alone, showing a gate would read as the user
     * going home and close the session underneath.
     *
     * This is weaker than the token set it replaced and needs device
     * verification. See the README.
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
                ownWindowIds = emptySet(),
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
            ownWindowIds = emptySet(),
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
            ownWindowIds = emptySet(),
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
            ownWindowIds = emptySet(),
        )
        assertEquals(EventRoute.ExitToHome, route)
    }
}
