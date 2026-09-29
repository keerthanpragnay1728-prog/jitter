package dev.molasses.core.lock

/**
 * The snap points a lock duration is offered at.
 *
 * A free-text duration is still accepted through `DurationParser`, capped at
 * [MAX_MS]; this is for the places that need a small ordered set, such as a
 * picker or a "lengthen this lock" control. The steps are roughly geometric so
 * each is a meaningful jump rather than a nudge: an hour, an afternoon, a
 * night, a day, a long weekend, a week, a fortnight, a month.
 *
 * ## The one ladder
 * Every tapped surface reads this list: the CFG scrubber today, and any
 * surface that offers a subset reads it from here and filters, rather than
 * keeping a list of its own. Two lists of lock durations are two answers to
 * "what can I tap", and the one that drifts is the one nobody is looking at.
 *
 * Nothing persists an index into it. Stored locks are deadlines, so changing
 * the rungs changes no standing lock, and the scrubber saves the duration it
 * is pointing at rather than a position on this list.
 *
 * Pure; no Android imports. Unit-tested in `LockLadderTest`.
 */
object LockLadder {

    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

    /** Ascending, and required to stay ascending by a test. */
    val STEPS_MS: List<Long> = listOf(
        1 * HOUR,
        4 * HOUR,
        12 * HOUR,
        1 * DAY,
        3 * DAY,
        7 * DAY,
        14 * DAY,
        30 * DAY,
    )

    val MAX_MS: Long = STEPS_MS.last()
    val MIN_MS: Long = STEPS_MS.first()

    /** Duration at [index], clamped into range rather than throwing. */
    fun durationAt(index: Int): Long = STEPS_MS[index.coerceIn(STEPS_MS.indices)]

    /** Index of [durationMs] if it is exactly a step, else -1. */
    fun indexOf(durationMs: Long): Int = STEPS_MS.indexOf(durationMs)

    /**
     * Nearest step to an arbitrary duration.
     *
     * Ties round **up**, toward more friction. A value exactly between two
     * steps is ambiguous, and in an app whose entire purpose is to make usage
     * harder the tie-break should not quietly pick the softer option.
     */
    fun snap(durationMs: Long): Long {
        if (durationMs <= MIN_MS) return MIN_MS
        if (durationMs >= MAX_MS) return MAX_MS
        var best = STEPS_MS.first()
        var bestDistance = Long.MAX_VALUE
        for (step in STEPS_MS) {
            val distance = kotlin.math.abs(step - durationMs)
            // <= rather than <, so a tie keeps the later (longer) step.
            if (distance <= bestDistance) {
                bestDistance = distance
                best = step
            }
        }
        return best
    }

    /** The next step up from [durationMs], or [MAX_MS] at the top. */
    fun next(durationMs: Long): Long = STEPS_MS.firstOrNull { it > durationMs } ?: MAX_MS
}
