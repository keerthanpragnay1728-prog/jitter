package dev.molasses.core.model

import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.HorizonPolicy

/** Per-package friction state, as the UI and debug screen see it. */
data class AppSnapshot(
    val pkg: String,
    val accumulatedMs: Long,
    val tierIndex: Int,
    /** Leases granted on this package in the current cycle. */
    val leasesTaken: Int,
    /**
     * Accumulated-time mark where the last lease taken runs out, or 0 when
     * none has been taken this cycle. Zero is not a lease that expired at
     * zero: with none taken, nothing is overdue. See [pastLease].
     */
    val leaseUntilAccumulatedMs: Long,
    /**
     * Extra time added to the friction lookup for sitting past a lease.
     *
     * Deliberately a separate field and never folded into [accumulatedMs].
     * The ledger has to report true time, and the debug screen shows the two
     * side by side: a user looking at their own numbers should be able to see
     * what they actually spent and what it is costing them.
     */
    val penaltyMs: Long = 0,
    /**
     * The declared session horizon this app's curve is scaled to.
     *
     * Per app rather than per session on purpose. Chosen on the lease panel it
     * would be the largest button on the screen with no cost to pressing it,
     * and everyone would be on sixty minutes inside a week. In settings it is
     * a calm decision about what this app is for.
     */
    val horizonMs: Long = FrictionCurve.DEFAULT_HORIZON_MS,
    /**
     * A wider horizon waiting for the next cycle rollover, or 0 for none.
     * Narrowing does not wait, so this only ever holds a widen. See
     * [dev.molasses.core.friction.HorizonPolicy].
     */
    val pendingHorizonMs: Long = HorizonPolicy.NONE,
    /**
     * Live accumulated time at the penalty ratchet's last evaluation, the
     * point [penaltyMs] has been charged up to. Null for "no anchor", which is
     * what a file written before this was persisted reads as; the engine then
     * anchors at [accumulatedMs] and charges nothing retroactively.
     */
    val penaltyAnchorMs: Long? = null,
) {
    /**
     * Sitting in this app past the lease that was taken for it.
     *
     * The [leasesTaken] half is load bearing. Without it every app with any
     * accumulated time and no lease reads as overdue, which is every app on
     * a device where the gate is suppressed, and the ratchet would run
     * against users it was never meant to charge.
     */
    val pastLease: Boolean
        get() = leasesTaken > 0 && accumulatedMs > leaseUntilAccumulatedMs
}

/** Immutable view of the engine, published on [dev.molasses.engine.FrictionEngine.state]. */
data class EngineState(
    val perApp: Map<String, AppSnapshot> = emptyMap(),
    val openSessionPkg: String? = null,
    val cycleAnchorWallMs: Long = 0,
    val lastTargetUseWallMs: Long = 0,
    val resetPolicy: CycleResetPolicy = CycleResetPolicy.DEFAULT,
    /**
     * Deadline minus now, clamped into `[0, WINDOW_MS]`. This is the number
     * the status header shows as RESETS IN, and it is computed here rather
     * than in the UI so that the clamp is applied once, in the pure layer,
     * instead of once per surface that wants to display it.
     */
    val cycleRemainingMs: Long = CycleResetPolicy.WINDOW_MS,
)

/**
 * The serialisable form the engine is seeded from and checkpointed into. This
 * is deliberately a plain data class and not the generated proto type, so the
 * engine stays free of any dependency on protobuf or Android.
 */
data class EngineSnapshot(
    val perApp: Map<String, AppSnapshot> = emptyMap(),
    val cycleAnchorWallMs: Long = 0,
    val lastTargetUseWallMs: Long = 0,
    val resetPolicy: CycleResetPolicy = CycleResetPolicy.DEFAULT,
    /**
     * The monotonic and boot-count halves of the two wall-clock stamps above.
     * Persisted because without them a restart would reduce both stamps to
     * bare wall-clock timestamps, and a bare wall-clock deadline is defeated
     * by setting the system clock forward. See `StampedInstant`.
     */
    val cycleAnchorElapsedMs: Long = 0,
    val cycleAnchorBootId: Int = 0,
    val lastTargetUseElapsedMs: Long = 0,
    val lastTargetUseBootId: Int = 0,
)
