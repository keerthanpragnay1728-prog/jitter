package dev.molasses.engine

/**
 * The tier index: how many whole five minute blocks of foreground time an app
 * has used this cycle. A count, and nothing else.
 *
 * It used to be the friction ladder itself: a gate at 5, 10, 15 and 20
 * minutes, fixed stalls between them, and a terminal rung. None of that
 * decides anything now. Stalls come from `FrictionCurve` at the app's
 * horizon, "terminal" means past the horizon (`FrictionCurve.isTerminal`),
 * and the gate is the lease gate. The ladder's stalls, entry times, terminal
 * rung and display rows were deleted with their last callers, and no screen
 * shows a tier any more.
 *
 * What is left does internal work:
 * - `FrictionEngine` raises `tierIndex` from it on every scroll, on true time,
 *   and writes it into the SCROLL ledger row's detail.
 * - `CycleStateStore` reconciles the stored `tierIndex` from it at startup.
 * - The debug state editor derives an index from it when none is typed.
 *
 * The index is unbounded rather than saturating, so it stays a faithful count
 * of use. See the README, DESIGN HISTORY.
 */
object TierPolicy {

    const val TIER_WIDTH_MS = 5L * 60 * 1000

    fun indexFor(accumulatedMs: Long): Int {
        if (accumulatedMs <= 0) return 0
        return (accumulatedMs / TIER_WIDTH_MS).toInt()
    }
}
