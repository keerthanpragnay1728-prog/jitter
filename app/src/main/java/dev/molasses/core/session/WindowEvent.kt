package dev.molasses.core.session

/**
 * An accessibility window event, reduced to the fields the routing decision
 * needs. Pure, so [ForegroundEventRouter] is testable without the framework.
 */
data class WindowEvent(
    val packageName: String,
    val className: String?,
    /**
     * `AccessibilityEvent.getWindowId()`.
     *
     * This is an int, not an `IBinder`. There is no public path from an
     * `AccessibilityEvent` to a window token, so the "is this one of ours"
     * guard has to key on the id. `View.getWindowToken()` gives us our own
     * tokens but there is nothing on the event side to compare them against.
     */
    val windowId: Int,
    val kind: Kind,
) {
    enum class Kind { WINDOW_STATE_CHANGED, WINDOWS_CHANGED, VIEW_SCROLLED }
}

/**
 * Why an event was dropped.
 *
 * ## Why the reason is carried rather than inferred
 * A total of ignored events says the router rejected them and nothing about
 * which branch did it, and the branches have completely different fixes. On
 * hardware that cost a diagnosis: every event from every package was being
 * dropped, including the launcher's own, and the tally could say only that
 * it was happening. A window-id guard, since deleted, dropped events before
 * the package was ever looked at, so "the target set is wrong" and "the
 * collision guard is eating everything" produced an identical row. Its
 * reason, OWN_WINDOW, went with it at 1.0.3.
 */
enum class IgnoreReason {
    /** Our own package, and not the launcher activity. */
    OWN_PACKAGE,

    /** A package the user is not tracking. */
    NOT_A_TARGET,
}

/** What the service should do with an event. */
sealed interface EventRoute {
    /** Not ours to act on. */
    data class Ignore(val reason: IgnoreReason) : EventRoute

    data class EnterTarget(val pkg: String) : EventRoute

    /** The user reached the launcher, so any open target session has ended. */
    data object ExitToHome : EventRoute

    data class Scroll(val pkg: String) : EventRoute

    /**
     * A hint that the window stack moved without saying who owns it. Ask
     * `UsageStatsManager` rather than guessing.
     */
    data object ProbeForeground : EventRoute
}
