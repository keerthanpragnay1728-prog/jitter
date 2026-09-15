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

    /**
     * A debug build skipped the movement gate by hand. Never written by a
     * release build. Kept distinct from GATE_PASSED so a bypassed session can
     * never be read back as a cleared one when reviewing a capture.
     */
    GATE_BYPASSED_DEBUG,

    /**
     * A locked target was opened and the user was sent home.
     *
     * Distinct from RESUMED, which is still written for the same moment: the
     * session did open, time did start accruing, and the ledger has to show
     * both or an enforced bounce reads as the user never having tried.
     */
    LOCK_ENFORCED,
}
