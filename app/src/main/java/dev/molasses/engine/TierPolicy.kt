package dev.molasses.engine

/** One rung of the friction ladder. */
data class Tier(
    val index: Int,
    val entryAtMs: Long,
    val stallMs: Long,
    val gateOnEntry: Boolean,
)

/**
 * The ladder, as data.
 *
 * ```
 *  0-5 min   normal
 *  at 5      gate, then 1000 ms stall
 *  at 10     gate, then 3000 ms stall
 *  at 15     gate, then 5000 ms stall
 *  at 20     TERMINAL: 5000 ms stall, gate re-arms every 5 min, indefinitely
 * ```
 *
 * The terminal tier is expressed by letting the index keep climbing rather
 * than clamping it: `index = floor(accumulated / 5 min)`, unbounded, with
 * [stallMsFor] pinned at [TERMINAL_STALL_MS] from index 4 on. That gives the
 * "re-arms every 5 minutes indefinitely" behaviour of SS0.1 for free, and it
 * keeps the index a faithful description of how much has been used instead of
 * a saturated counter -- which matters because the index is what the ledger
 * and debug screen report.
 */
object TierPolicy {

    const val TIER_WIDTH_MS = 5L * 60 * 1000
    const val TERMINAL_INDEX = 4
    const val TERMINAL_STALL_MS = 5_000L

    /** Stalls for indices 0..TERMINAL_INDEX; beyond that, [TERMINAL_STALL_MS]. */
    private val STALL_MS = longArrayOf(0L, 1_000L, 3_000L, 5_000L, TERMINAL_STALL_MS)

    /** The named rungs, for display. The ladder itself continues past the last. */
    val ladder: List<Tier> = (0..TERMINAL_INDEX).map { tierFor(it) }

    fun indexFor(accumulatedMs: Long): Int {
        if (accumulatedMs <= 0) return 0
        return (accumulatedMs / TIER_WIDTH_MS).toInt()
    }

    fun stallMsFor(index: Int): Long = when {
        index <= 0 -> 0L
        index >= TERMINAL_INDEX -> TERMINAL_STALL_MS
        else -> STALL_MS[index]
    }

    fun entryAtMs(index: Int): Long = index.coerceAtLeast(0) * TIER_WIDTH_MS

    fun isTerminal(index: Int): Boolean = index >= TERMINAL_INDEX

    fun tierFor(index: Int): Tier = Tier(
        index = index,
        entryAtMs = entryAtMs(index),
        stallMs = stallMsFor(index),
        gateOnEntry = index >= 1,
    )
}
