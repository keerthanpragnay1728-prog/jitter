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
        assertEquals(EventRoute.Ignore, route(gate, ownWindows = setOf(77)))
    }

    @Test
    fun `dragging the mascot does not read as leaving the app`() {
        val mascot = WindowEvent(own, null, 78, Kind.WINDOWS_CHANGED)
        assertEquals(EventRoute.Ignore, route(mascot, ownWindows = setOf(77, 78)))
    }

    @Test
    fun `arming the shutter sink does not read as leaving the app`() {
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore, route(sink, ownWindows = setOf(79)))
    }

    @Test
    fun `an overlay is dropped even when the window id was not registered yet`() {
        // Belt and braces. The id set is refreshed asynchronously after
        // addView, so an event can arrive in the gap. The package check is the
        // second line of defence.
        val sink = WindowEvent(own, "android.view.View", 79, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore, route(sink, ownWindows = emptySet()))
    }

    @Test
    fun `the window id check runs before anything else`() {
        // Even an event wearing the launcher class name is dropped if it came
        // from a window we own. Nothing we add is ever the launcher.
        val impostor = WindowEvent(
            own, ForegroundEventRouter.LAUNCHER_CLASS_NAME, 77, Kind.WINDOW_STATE_CHANGED,
        )
        assertEquals(EventRoute.Ignore, route(impostor, ownWindows = setOf(77)))
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
        assertEquals(EventRoute.Ignore, route(home))
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
        assertEquals(EventRoute.Ignore, route(e))
    }

    @Test
    fun `a package removed from the target list stops routing`() {
        val e = WindowEvent(ig, "x", 3, Kind.WINDOW_STATE_CHANGED)
        assertEquals(EventRoute.Ignore, router.route(e, setOf(yt), emptySet()))
    }
}
