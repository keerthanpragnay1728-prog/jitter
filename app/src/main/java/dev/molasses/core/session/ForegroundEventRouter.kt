package dev.molasses.core.session

/**
 * Decides what an accessibility event means. Pure.
 *
 * ## Why this exists
 * `accessibility_service_config.xml` scopes `packageNames` to the target apps,
 * so no event arrives when the user leaves one and the session is never
 * closed. Adding our own package to that list fixes the gap and creates a
 * collision: every overlay this app shows belongs to `dev.molasses` and emits
 * `TYPE_WINDOW_STATE_CHANGED`. Unfiltered, showing the movement gate looks
 * exactly like the user going home, and the service closes the session it is
 * in the middle of gating.
 *
 * ## The filter, in order
 * 1. Any event from a window this service added is dropped first, before
 *    anything else looks at it.
 * 2. Any other event from our own package is dropped unless it carries the
 *    launcher's class name. The mascot and the touch sink have no activity
 *    class, so a class check alone would catch the launcher case and miss both
 *    overlays. That is why the window-id check comes first and is not
 *    optional.
 * 3. Target packages route normally.
 *
 * App-to-app switches never reach here at all, because the other app is not in
 * `packageNames`. Those are the `UsageStatsManager` watchdog's job.
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
    /**
     * @param ownWindowIds window ids this service currently owns. See
     *   [WindowEvent.windowId] for why this is not a token set.
     */
    fun route(
        event: WindowEvent,
        targets: Set<String>,
        ownWindowIds: Set<Int>,
    ): EventRoute {
        // 1. Our own windows, dropped before any other handling.
        //
        // This branch runs before the package is even read, so when it
        // misfires it hides every other explanation: a row of ignored events
        // looks identical whether the target set is wrong or this guard is
        // eating the device. That is why the reason is carried out.
        if (event.windowId in ownWindowIds) {
            return EventRoute.Ignore(IgnoreReason.OWN_WINDOW)
        }

        // 2. Anything else wearing our package name.
        if (event.packageName == ownPackage) {
            val isLauncher = event.className == launcherClassName &&
                event.kind == WindowEvent.Kind.WINDOW_STATE_CHANGED
            return if (isLauncher) {
                EventRoute.ExitToHome
            } else {
                EventRoute.Ignore(IgnoreReason.OWN_PACKAGE)
            }
        }

        // 3. Target apps.
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
