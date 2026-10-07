package dev.molasses.core.diag

/**
 * Today's screen time by our rule beside the system's own aggregate, for
 * DBG, so drift between them is visible without opening Digital Wellbeing.
 * Pure: the caller reads both numbers.
 *
 * The system number is `queryAndAggregateUsageStats`' `totalTimeInForeground`
 * summed over every package but ours. It is a reference and not the truth:
 * `DayUsage` explains why the ledger stopped using it (its daily buckets
 * straddle midnight and roll up on the system's schedule). A small gap is
 * expected. A large one, the size of the 1 h 56 min against 3 h 40 min that
 * found the per-activity fault, is the thing this line exists to show.
 */
object ScreenTimeDrift {

    data class Reading(val oursMs: Long, val systemMs: Long) {
        /** How much more the system counted. Negative when ours is larger. */
        val gapMs: Long get() = systemMs - oursMs

        /** Ours as a share of the system's, or null when the system counted nothing. */
        val oursPercentOfSystem: Int? get() = if (systemMs <= 0L) null else ((oursMs * 100) / systemMs).toInt()
    }

    /** The system's total: every package's foreground time but [exclude]'s, ignoring negatives. */
    fun systemTotal(foregroundMsByPkg: Map<String, Long>, exclude: Set<String>): Long =
        foregroundMsByPkg.filterKeys { it !in exclude }.values.sumOf { it.coerceAtLeast(0L) }
}
