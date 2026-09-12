package dev.molasses.sensing

import dev.molasses.core.model.GateProgress

/**
 * What one gate pipeline reports after processing input.
 *
 * [passed] is deliberately here and not on [GateProgress]. Progress travels on
 * a conflating StateFlow for the UI; a pass is terminal and is routed by
 * [MovementDetector] onto its own non-conflating channel. Keeping the two
 * apart in the type system is what stops a future collector from re-creating
 * the bug where a pass was inferred from sampled state and dropped.
 */
data class GateEvaluation(
    val progress: GateProgress,
    val passed: Boolean,
    /** Present only on the IMU paths, which run the seven-test battery. */
    val tick: TickEvaluation? = null,
)
