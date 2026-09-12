package dev.molasses.core.model

/**
 * How a 6-hour cycle ends. See README SS0.1 ambiguity 2.
 *
 * The brief's phrase "resets only after 6 continuous hours" is ambiguous
 * between an abstinence window and a wall-clock window. Both are implemented;
 * [ABSTINENCE_6H] is the default because it is the reading that matches the
 * product intent -- under [FIXED_WINDOW_6H] a user can simply wait out the
 * clock while still scrolling, which makes the friction ladder decorative.
 */
enum class CycleResetPolicy {
    /** Reset after 6 continuous hours with zero foreground time on *any* target app. */
    ABSTINENCE_6H,

    /** Reset 6 hours after the cycle anchor, regardless of use. */
    FIXED_WINDOW_6H,
    ;

    companion object {
        val DEFAULT = ABSTINENCE_6H
        const val WINDOW_MS = 6L * 60 * 60 * 1000
    }
}
