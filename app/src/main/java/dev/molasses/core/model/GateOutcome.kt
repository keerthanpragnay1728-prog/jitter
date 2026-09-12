package dev.molasses.core.model

/**
 * A gate session ending successfully. Exactly one of these is emitted per
 * session, on a channel that cannot conflate.
 *
 * This exists because the first device build could not clear the gate. The
 * progress ring reached 100%, which proved the sustain had completed, but the
 * collector never acted on it. Pass was being inferred from a `passed` flag on
 * [GateProgress], and [GateProgress] travels on a `StateFlow`, which conflates.
 * A terminal event that holds for one sample can be overwritten by the next
 * sample before the collector is scheduled, and then it is simply gone.
 *
 * Sampled state and terminal events are different things and need different
 * carriers. [GateProgress] stays on a StateFlow because only its newest value
 * matters. This travels on a replaying SharedFlow because every value matters
 * and losing one makes the gate unclearable.
 */
sealed interface GateOutcome {

    /** The movement or challenge criterion was met. */
    data class Passed(
        val path: GateProgress.Path,
        /** Net credit at the moment of passing, ms. */
        val creditMs: Long,
        /** Evaluation ticks the session took. */
        val ticks: Int,
    ) : GateOutcome

    /**
     * A debug build bypassed the gate by hand. Never produced in release, and
     * ledgered as [EventType.GATE_BYPASSED_DEBUG] so a bypassed session can
     * never be read back as a cleared one.
     */
    data class BypassedForDebug(val path: GateProgress.Path) : GateOutcome
}
