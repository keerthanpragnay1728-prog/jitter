package dev.molasses.core.model

/** Per-package friction state, as the UI and debug screen see it. */
data class AppSnapshot(
    val pkg: String,
    val accumulatedMs: Long,
    val tierIndex: Int,
    val gatesCleared: Int,
    val tierUnlockedUntilMs: Long,
    /** True when a gate is owed and has been neither cleared nor superseded. */
    val gatePending: Boolean,
)

/** Immutable view of the engine, published on [dev.molasses.engine.FrictionEngine.state]. */
data class EngineState(
    val perApp: Map<String, AppSnapshot> = emptyMap(),
    val openSessionPkg: String? = null,
    val cycleAnchorWallMs: Long = 0,
    val lastTargetUseWallMs: Long = 0,
    val resetPolicy: CycleResetPolicy = CycleResetPolicy.DEFAULT,
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
)
