package dev.molasses.core.lease

import dev.molasses.core.lock.GateBlock

/**
 * Which answers the lease gate shows at a given moment of its countdown.
 * Pure.
 *
 * ## The wait is the toll, the exit is not
 * The lease options appear only when the countdown reaches zero, on either
 * gate: waiting is what a lease costs. Leaving never costs anything, and on
 * both gates it is there from the first frame, as [ ARCHITECT'S SPACE ]. On
 * device the expired gate showed only the block control until zero, which
 * left a user who just wanted to stop with no way out but to wait or lock
 * themselves out. The entry gate had the same fault for longer: its only
 * exit, [ TAKE ME OUT ], sat in the decision panel and appeared at zero.
 *
 * One exit on both gates, with one handler and one meaning: dismiss, stay
 * home, grant nothing, LEASE_DECLINED reason=exit. [ TAKE ME OUT ] is gone
 * rather than kept beside it. The entry gate still has no block control.
 */
object GateControls {

    enum class Exit { ARCHITECTS_SPACE }

    /**
     * @param exit the way out on screen now. Nullable so `OverlayExit` can
     *   state its invariant over it: nothing produces null today, and a
     *   state that did would fail `OverlayExitTest`.
     */
    data class Visible(val exit: Exit?, val leases: Boolean, val block: Boolean)

    fun visible(expired: Boolean, remainingMs: Long): Visible = Visible(
        exit = Exit.ARCHITECTS_SPACE,
        leases = GateReadout.panelUp(remainingMs),
        block = GateBlock.offered(expired),
    )
}
