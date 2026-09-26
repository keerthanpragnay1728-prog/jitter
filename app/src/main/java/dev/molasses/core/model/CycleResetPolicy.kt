package dev.molasses.core.model

/**
 * How a 6-hour cycle ends. See README SS0.1 ambiguity 2.
 *
 * The brief's phrase "resets only after 6 continuous hours" is ambiguous
 * between an abstinence window and a wall-clock window. The app runs
 * [FIXED_WINDOW_6H]: the cycle is anchored on the first target app opened from
 * a clean state and runs six hours from there whether the user keeps
 * scrolling or not.
 *
 * [ABSTINENCE_6H] was the default originally, on the reasoning that a fixed
 * window lets a user wait out the clock while still scrolling. That is true,
 * but waiting out six hours of a fixed window means the friction was doing its
 * job for those six hours, and an abstinence window has the worse property
 * that it is impossible to ever see a reset if you check the app once an hour.
 *
 * It is no longer selectable and nothing implements it: the setting was
 * removed and the engine's branch for it was deleted. The enum survives only
 * because `reset_policy` in the proto is typed by it, and stored files must
 * still parse. [DEFAULT] and [WINDOW_MS] are what the code reads.
 */
enum class CycleResetPolicy {
    /** Reset after 6 continuous hours with zero foreground time on *any* target app. */
    ABSTINENCE_6H,

    /** Reset 6 hours after the cycle anchor, regardless of use. */
    FIXED_WINDOW_6H,
    ;

    companion object {
        val DEFAULT = FIXED_WINDOW_6H
        const val WINDOW_MS = 6L * 60 * 60 * 1000
    }
}
