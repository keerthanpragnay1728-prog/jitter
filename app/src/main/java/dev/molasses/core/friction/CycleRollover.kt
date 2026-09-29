package dev.molasses.core.friction

import dev.molasses.core.model.AppSnapshot

/**
 * What one app carries into a new cycle. Pure, and the only answer to that
 * question: the engine's rollover and the reconciler's rollover on connect
 * both call it.
 *
 * ## Why one function
 * There were two rollovers and they disagreed. The engine's replaced each app
 * with a fresh state and promoted a pending horizon widen, which is the one
 * thing a rollover exists to land. The reconciler's, which runs when a cycle
 * came due while the process was dead (the common case), emptied the per-app
 * map outright. A widen waiting on that app was dropped, and the stored
 * preference re-applied afterwards against a fresh default state went back to
 * pending, so it waited a whole extra cycle.
 *
 * Everything else starts over: accumulated time, tier, leases, the lease mark
 * and the penalty with its anchor. The horizon crosses, promoted.
 */
object CycleRollover {

    fun carryOver(app: AppSnapshot): AppSnapshot {
        val promoted = HorizonPolicy.promote(HorizonPolicy.of(app.horizonMs, app.pendingHorizonMs))
        return AppSnapshot(
            pkg = app.pkg,
            accumulatedMs = 0,
            tierIndex = 0,
            leasesTaken = 0,
            leaseUntilAccumulatedMs = 0,
            penaltyMs = 0,
            horizonMs = promoted.horizonMs,
            pendingHorizonMs = promoted.pendingHorizonMs,
            penaltyAnchorMs = null,
        )
    }

    fun carryOver(perApp: Map<String, AppSnapshot>): Map<String, AppSnapshot> =
        perApp.mapValues { (_, app) -> carryOver(app) }
}
