package dev.molasses.core.model

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
