package dev.molasses.core.model

/**
 * Ledger row kinds. The first seven are the set named in the brief; the last
 * two are additions required by reconciler steps 3 and 4, which mandate
 * "mark the ledger row" for a boot boundary and for a detected clock warp.
 * Encoding those in [UsageEventEntity.meta] would have hidden them from the
 * debug screen's type filter, which is where you actually go looking after a
 * suspicious accumulation.
 */
enum class EventType {
    RESUMED,
    PAUSED,
    SCROLL,
    GATE_SHOWN,
    GATE_PASSED,
    GATE_ABANDONED,
    STALL_ARMED,

    /** A [dev.molasses.monitor.ForegroundReconciler] pass rebuilt lost time. */
    RECONCILED,

    /** `abs(wallDelta - elapsedDelta) > 60s`, or a boot-count change. */
    CLOCK_WARP,
}
