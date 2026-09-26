package dev.molasses.core.settings

/**
 * Turning a target OFF waits 150 seconds. Turning one ON does not. Pure.
 *
 * ## Why only one direction
 * Tracking an app is a decision to have more friction and can be acted on at
 * once. Untracking one is the decision this app exists to slow down: it is
 * the same two taps a user in the middle of a scroll would make to get out
 * of a gate, so it gets the same thing a gate gives, which is time.
 *
 * ## What abandons it
 * Leaving the screen in any way: the activity pausing (home, recents, the
 * screen going off), a rotation, back. The app stays tracked and nothing is
 * written, because nothing is persisted. The countdown is Compose state held
 * with `remember`, not `rememberSaveable`, precisely so a configuration
 * change or process death forgets it. A cooling-off that survived leaving
 * would be a timer to start and walk away from.
 *
 * ## Honest seconds
 * The remainder is recomputed from `elapsedRealtime` on each tick and never
 * decremented, so it neither drifts nor pauses while the device sleeps. The
 * two answers appear only at zero.
 *
 * ## A locked app never starts one
 * [start] refuses a package with a standing lock. The screen already holds
 * that row's toggle, and the store's write runs the `TargetLock` guard inside
 * its own transaction anyway, which is what answers if a lock is armed while
 * the countdown runs.
 */
object UntrackCoolingOff {

    const val DURATION_MS: Long = 150_000L

    data class State(val pkg: String, val label: String, val startedElapsedMs: Long)

    sealed interface Phase {
        data class Counting(val remainingMs: Long) : Phase
        data object Ready : Phase
    }

    /**
     * A new cooling-off for [pkg], or null when there is nothing to cool off
     * from: it is not tracked, or a lock stands on it.
     */
    fun start(pkg: String, label: String, tracked: Boolean, lockRemainingMs: Long, nowElapsedMs: Long): State? =
        if (!tracked || lockRemainingMs > 0L || pkg.isEmpty()) null else State(pkg, label, nowElapsedMs)

    fun phase(state: State, nowElapsedMs: Long): Phase {
        // A clock that reads earlier than the start is not time served.
        val served = (nowElapsedMs - state.startedElapsedMs).coerceAtLeast(0L)
        val remaining = (DURATION_MS - served).coerceAtLeast(0L)
        return if (remaining == 0L) Phase.Ready else Phase.Counting(remaining)
    }

    /**
     * Whether [pkg] is the only tracked target, so removing it leaves nothing
     * gated. A chosen empty selection resolves to no targets, not back to
     * the defaults (see TargetScope.resolve), so this is exactly the case
     * where confirming turns the app off in effect. The panel says so.
     */
    fun isLastTarget(pkg: String, tracked: Collection<String>): Boolean =
        pkg in tracked && tracked.all { it == pkg }

    /** Whole seconds left, rounded up, so it reads 150 at the start and 0 only at zero. */
    fun seconds(remainingMs: Long): Long = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L

    /** Whether the remove may be committed now: only once the countdown has run out. */
    fun mayConfirm(state: State, nowElapsedMs: Long): Boolean = phase(state, nowElapsedMs) == Phase.Ready

    enum class Leave { PAUSED, ROTATED, BACK, NAVIGATED_AWAY }

    /**
     * The state after the user leaves, in any of the ways [Leave] names:
     * gone, and the app still tracked. A function rather than a bare null at
     * each call site, so every way out is one tested rule.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onLeave(state: State?, how: Leave): State? = null
}
