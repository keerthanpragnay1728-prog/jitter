package dev.molasses.core.session

/**
 * A stand-in for TYPE_VIEW_SCROLLED, from TYPE_WINDOW_CONTENT_CHANGED, for a
 * target that never sends a real scroll. Pure.
 *
 * ## Why, and why it is reversible
 * On hardware YouTube, past its horizon and at the ceiling, scrolled with no
 * stall. The case this answers is YouTube sending no TYPE_VIEW_SCROLLED on
 * that surface at all, which the Molasses.Service log shows as window-state
 * events and no scrolls. If the log shows real scrolls instead, the whole
 * proxy is one commit and is reverted as one.
 *
 * ## What it reads
 * The event's package, its time, and `getContentChangeTypes()`, an int on
 * the event itself. Never `getSource()` and never a node: the profile keeps
 * `canRetrieveWindowContent` false, so there is no content to read.
 *
 * ## The limits, all of them in here
 * - Only a subtree change, or an undefined one, qualifies: the shape a list
 *   rebinding its rows under a finger makes. A text or state-description
 *   change, which is what a playing video's clock and progress bar send, is
 *   ignored.
 * - Only for a package that has sent no real scroll this session. The first
 *   real TYPE_VIEW_SCROLLED switches the proxy off for that package until
 *   the session closes, so an app that does send scrolls is never stalled
 *   twice for one gesture.
 * - At most one pass through to the engine per [MIN_INTERVAL_MS], so the
 *   curve and the ledger see at most one proxy scroll a second however many
 *   content changes arrive.
 * - At most one arm per stall window: after the proxy arms a stall of S ms,
 *   nothing passes again until S ms have gone by.
 */
object ScrollProxy {

    /** `AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED`. */
    const val CONTENT_CHANGE_UNDEFINED = 0

    /** `AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE`. */
    const val CONTENT_CHANGE_SUBTREE = 1

    const val MIN_INTERVAL_MS = 1_000L

    data class State(
        val sawRealScroll: Boolean = false,
        val lastPassedAtMs: Long? = null,
        val armedUntilMs: Long = 0L,
    )

    fun qualifies(contentChangeTypes: Int): Boolean =
        contentChangeTypes == CONTENT_CHANGE_UNDEFINED || (contentChangeTypes and CONTENT_CHANGE_SUBTREE) != 0

    /** A real TYPE_VIEW_SCROLLED arrived: the proxy is off for this package for the session. */
    fun onRealScroll(state: State): State = state.copy(sawRealScroll = true)

    /** Whether a qualifying content change at [nowMs] may go through to the arm path. */
    fun mayPass(state: State, nowMs: Long): Boolean =
        !state.sawRealScroll &&
            nowMs >= state.armedUntilMs &&
            (state.lastPassedAtMs == null || nowMs - state.lastPassedAtMs >= MIN_INTERVAL_MS)

    /** It went through; [armedStallMs] is what the shutter armed, zero for nothing. */
    fun onPassed(state: State, nowMs: Long, armedStallMs: Long): State = state.copy(
        lastPassedAtMs = nowMs,
        armedUntilMs = if (armedStallMs > 0L) nowMs + armedStallMs else state.armedUntilMs,
    )
}
