package dev.molasses.core.session

/**
 * Decides what an accessibility event means. Pure.
 *
 * ## Why this exists
 * `accessibility_service_config.xml` scopes `packageNames` to the target apps,
 * so no event arrives when the user leaves one and the session is never
 * closed. Adding our own package to that list fixes the gap and creates a
 * collision: every overlay this app shows belongs to our own package and emits
 * `TYPE_WINDOW_STATE_CHANGED`. Unfiltered, showing the movement gate looks
 * exactly like the user going home, and the service closes the session it is
 * in the middle of gating.
 *
 * ## The filter, in order
 * 1. Any event from our own package is dropped unless it carries the
 *    launcher's class name. The mascot and the touch sink have no activity
 *    class, so this is what keeps them from reading as the user going home.
 * 2. Target packages route normally.
 *
 * App-to-app switches never reach here at all, because the other app is not in
 * `packageNames`. Those are the `UsageStatsManager` watchdog's job.
 *
 * ## The window-id check that used to come first
 * There was a rule above both of these: drop any event whose `windowId` is one
 * this service added. It is gone, and its absence is the point.
 *
 * It could not work under this app's accessibility profile.
 * `AccessibilityEvent.getWindowId()` returns `-1` when the platform declines
 * to say, and without `flagRetrieveInteractiveWindows` it declines for every
 * event. The service learned `-1` from one of its own events, then matched it
 * against every event from every package, and the whole app routed nothing on
 * a device that was bound, ready, correctly scoped and reporting healthy.
 *
 * With ids stripped, the rule can never match a real window, so it cannot come
 * back as a narrower version of itself. A guard that cannot fire is worse than
 * no guard: the next reader takes it as cover for a case the package check is
 * actually carrying alone.
 *
 * Its stated extra value was covering an event that arrived without a usable
 * package name. That case is unreachable anyway: the service returns on a null
 * package before the router is called, so such an event never gets here.
 *
 * See CLAUDE.md, "What dropping the flag costs, in full".
 */
class ForegroundEventRouter(
    private val ownPackage: String,
    /**
     * Fully qualified name of the launcher activity. Held as a string rather
     * than a class reference so this stays in the pure module, and so the
     * routing rule can be written and tested before the launcher exists.
     */
    private val launcherClassName: String,
) {
    fun route(
        event: WindowEvent,
        targets: Set<String>,
    ): EventRoute {
        // 1. Anything wearing our package name.
        if (event.packageName == ownPackage) {
            val isLauncher = event.className == launcherClassName &&
                event.kind == WindowEvent.Kind.WINDOW_STATE_CHANGED
            return if (isLauncher) {
                EventRoute.ExitToHome
            } else {
                EventRoute.Ignore(IgnoreReason.OWN_PACKAGE)
            }
        }

        // 2. Target apps.
        if (event.packageName in targets) {
            return when (event.kind) {
                WindowEvent.Kind.VIEW_SCROLLED -> EventRoute.Scroll(event.packageName)
                WindowEvent.Kind.WINDOW_STATE_CHANGED -> EventRoute.EnterTarget(event.packageName)
                WindowEvent.Kind.WINDOWS_CHANGED -> EventRoute.ProbeForeground
            }
        }

        return EventRoute.Ignore(IgnoreReason.NOT_A_TARGET)
    }

    companion object {
        /**
         * The launcher's fully qualified name. The activity itself arrives in
         * Phase 1; until then no event will ever carry this class name, so
         * [EventRoute.ExitToHome] is unreachable through the accessibility
         * path and the `UsageStatsManager` watchdog carries exit detection on
         * its own.
         */
        const val LAUNCHER_CLASS_NAME = "dev.molasses.ui.launcher.LauncherActivity"
    }
}
