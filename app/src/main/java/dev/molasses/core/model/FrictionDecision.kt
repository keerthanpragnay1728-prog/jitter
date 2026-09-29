package dev.molasses.core.model

/**
 * What a scroll earns: a stall, or nothing.
 *
 * ## Why this replaced the three-way FrictionAction
 * `FrictionAction` was a sealed interface returning exactly one thing, which
 * encoded a precedence that turned out to be backwards: a pending gate
 * suppressed every stall until it was cleared, so **ignoring a checkpoint
 * switched friction off entirely**. A user who walked away from the gate got
 * less friction than one who paid the toll, which inverts the incentive the
 * whole app rests on.
 *
 * ## Why it no longer carries a gate
 * It carried `gate: Int?`, the tier of a checkpoint owed, because a scroll
 * could earn a stall and a checkpoint at once. Gating moved to the launch:
 * see [dev.molasses.core.lease.GatePolicy]. A scroll can no longer produce a
 * gate, so the field is gone rather than nulled, which keeps the engine from
 * looking like it still decides something it does not.
 */
data class FrictionDecision(
    /** Arm the touch sink for this long. Zero means do not arm. */
    val stallMs: Long = 0,
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
    /**
     * What the curve commanded before the probability roll: the stall it
     * would arm, and the chance it arms at all. For the log only, so a scroll
     * that did not stall can say whether the curve had nothing to give or
     * the roll missed. [stallMs] is still the only thing that arms.
     */
    val curveStallMs: Int = 0,
    val probability: Float = 0f,
) {
    val stalls: Boolean get() = stallMs > 0

    companion object {
        /** Under the onset, with nothing owed. */
        val NONE = FrictionDecision()
    }
}
