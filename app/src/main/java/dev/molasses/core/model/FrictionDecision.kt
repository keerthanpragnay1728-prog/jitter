package dev.molasses.core.model

/**
 * What a scroll earns: a stall, a checkpoint, both, or neither.
 *
 * ## Why this replaced the three-way FrictionAction
 * `FrictionAction` was a sealed interface returning exactly one thing, which
 * encoded a precedence that turned out to be backwards: a pending gate
 * suppressed every stall until it was cleared, so **ignoring a checkpoint
 * switched friction off entirely**. A user who walked away from the gate got
 * less friction than one who paid the toll, which inverts the incentive the
 * whole app rests on.
 *
 * Stalls now run continuously from the curve and checkpoints are layered over
 * them, so a scroll can legitimately produce both. One type carrying both
 * keeps the engine the single decision point rather than splitting the
 * decision between the engine and whatever reads `EngineState` for gates.
 */
data class FrictionDecision(
    /** Arm the touch sink for this long. Zero means do not arm. */
    val stallMs: Long = 0,
    /** Tier of the checkpoint owed, or null when none is due. */
    val gate: Int? = null,
    /**
     * The curve has saturated for this package.
     *
     * Carried on the decision rather than recomputed at the call site because
     * the stall marker turns the terminal colour on it, and a second
     * derivation of "is this terminal" is a second thing to keep in step with
     * the curve. This is the permanent terminal signal: Bit's glitch is a
     * burst on the crossing, so the marker is what is still saying it at
     * minute forty.
     */
    val terminal: Boolean = false,
) {
    val stalls: Boolean get() = stallMs > 0
    val gates: Boolean get() = gate != null

    companion object {
        /** Under the onset, with nothing owed. */
        val NONE = FrictionDecision()
    }
}
