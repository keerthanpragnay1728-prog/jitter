package dev.molasses.core.ui

import dev.molasses.core.model.AppSnapshot

/**
 * The one line of engine state the launcher shows.
 *
 * ```
 * CYCLE 38m +4m  TIER 02  RESETS 4h12m
 * ```
 *
 * ## Why this exists
 * Nothing in the launcher showed accumulated time, tier, penalty or cycle
 * remaining. The ledger page reads `UsageStatsManager` and nothing else, so
 * every number on it is the system's rather than Jitter's, and the engine's
 * own state lived in a debug screen and in one step of a readout behind a
 * retreated Bit. A user could not answer "how deep am I in this cycle"
 * without three taps into a developer page.
 *
 * ## Why the deepest app and not the sum
 * The same reason the mood reads it: the friction curve is per package, so a
 * tier is a property of one app. Summing would produce a figure with no tier
 * that describes it.
 *
 * ## Why the penalty is shown at all
 * A user whose curve has been accelerated by an ignored checkpoint currently
 * has no way to find that out. The ratchet is the one part of the friction
 * model that responds to something they chose to do, and it is the only
 * number here they can still act on.
 *
 * Every field is [UNKNOWN] rather than zero when the engine has no value.
 * Zero and "nothing recorded" are different, and a confident 0 is the kind of
 * small lie that makes the rest of a readout untrustworthy.
 *
 * Pure; no Android imports. Unit-tested in `CycleLineTest`.
 */
object CycleLine {

    /** Shown for anything the engine has no value for. Never a zero. */
    const val UNKNOWN = "--"

    /**
     * The rendered fields. [penalty] is null when the ratchet has not run,
     * which is the normal case, and the caller omits the segment entirely
     * rather than printing a zero.
     */
    data class Fields(
        val cycle: String,
        val penalty: String?,
        val tier: String,
        val resets: String,
    )

    /**
     * @param deepest the app with the most accumulated time, or null when
     *   nothing has been recorded this cycle.
     * @param cycleRemainingMs null when no cycle is anchored, which is not
     *   the same as a cycle with no time left.
     */
    fun fields(deepest: AppSnapshot?, cycleRemainingMs: Long?): Fields = Fields(
        cycle = if (deepest == null) UNKNOWN else duration(deepest.accumulatedMs),
        penalty = deepest?.penaltyMs?.takeIf { it > 0L }?.let { duration(it) },
        tier = if (deepest == null) UNKNOWN else tier(deepest.tierIndex),
        resets = if (cycleRemainingMs == null) UNKNOWN else duration(cycleRemainingMs),
    )

    /**
     * Two digits, so the line does not reflow between tier 9 and tier 10.
     *
     * The index is unbounded past the terminal by design, so it can exceed
     * two digits on a long enough cycle. Padding rather than clamping keeps
     * the number honest; the line reflowing at tier 100 is a price worth
     * paying over a tier column that lies.
     */
    fun tier(index: Int): String =
        if (index < 0) UNKNOWN else index.toString().padStart(2, '0')

    /**
     * Largest two units, seconds dropped.
     *
     * A cycle figure in seconds is noise: nobody reads `38m14s` differently
     * from `38m`. Anything under a minute reads `0m` rather than empty,
     * because an empty field and a missing field must not look the same.
     */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0m"
        var rest = ms
        val parts = mutableListOf<String>()
        for ((unit, suffix) in UNITS) {
            val n = rest / unit
            if (n > 0) {
                parts += "$n$suffix"
                rest -= n * unit
            }
        }
        if (parts.isEmpty()) return "0m"
        return parts.take(2).joinToString("")
    }

    private val UNITS: List<Pair<Long, String>> = listOf(
        24 * 60 * 60 * 1000L to "d",
        60 * 60 * 1000L to "h",
        60 * 1000L to "m",
    )
}
