package dev.molasses.core.lease

import dev.molasses.core.lock.GateBlock

/**
 * Which answers the lease gate shows at a given moment of its countdown.
 * Pure.
 *
 * ## The wait is the toll, the exit is not
 * The lease options appear only when the countdown reaches zero, on either
 * gate: waiting is what a lease costs. Leaving never costs anything, and on
 * the LEASE EXPIRED gate it is there from the first frame, as
 * [ ARCHITECT'S SPACE ] beside [ BLOCK THIS APP ]. On device the expired
 * gate showed only the block control until zero, which left a user who just
 * wanted to stop with no way out but to wait or lock themselves out.
 *
 * One exit, not two: on the expired gate [ ARCHITECT'S SPACE ] replaces
 * [ TAKE ME OUT ]. The entry gate is unchanged: [ TAKE ME OUT ] sits in the
 * decision panel at zero, and there is no block control.
 */
object GateControls {

    enum class Exit { TAKE_ME_OUT, ARCHITECTS_SPACE }

    /** @param exit the way out on screen now, or null when there is none yet. */
    data class Visible(val exit: Exit?, val leases: Boolean, val block: Boolean)

    fun visible(expired: Boolean, remainingMs: Long): Visible {
        val atZero = GateReadout.panelUp(remainingMs)
        return if (expired) {
            Visible(exit = Exit.ARCHITECTS_SPACE, leases = atZero, block = GateBlock.offered(expired))
        } else {
            Visible(exit = if (atZero) Exit.TAKE_ME_OUT else null, leases = atZero, block = GateBlock.offered(expired))
        }
    }
}
