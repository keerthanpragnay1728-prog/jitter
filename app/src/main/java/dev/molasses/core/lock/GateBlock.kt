package dev.molasses.core.lock

import dev.molasses.core.command.CommandRegistry

/**
 * [ BLOCK THIS APP ] on the LEASE EXPIRED gate. Pure.
 *
 * Offered on the returning gate only, the one that says a lease ran out, and
 * from the moment it appears rather than at zero: choosing more friction
 * should never have to wait out a countdown. The entry gate does not offer
 * it; a user who has not yet had a lease this cycle has not yet run out of
 * anything.
 */
object GateBlock {

    /**
     * The longest block this surface offers: one day.
     *
     * It is reached by running out of time, with a thumb, on a screen the
     * user wants gone. That is the worst place in the app for an irreversible
     * week, so the rungs stop at a day. Longer blocks stay in CFG, where the
     * decision is calm and the confirmation step applies.
     *
     * It sits at [CommandRegistry.CONFIRM_ABOVE_MS], not above it, so nothing
     * offered here needs confirming and there is no second screen on top of a
     * screen the user is trying to leave. `GateBlockTest` fails if either
     * constant moves so that a rung here would need a confirmation.
     */
    const val CAP_MS: Long = 24L * 60 * 60 * 1000

    /** 1h, 4h, 12h, 1d: the one ladder, cut at [CAP_MS]. Never a list of its own. */
    val RUNGS_MS: List<Long> = LockLadder.STEPS_MS.filter { it <= CAP_MS }

    /** Whether the gate shows the block option at all. */
    fun offered(expired: Boolean): Boolean = expired

    /**
     * The same evaluation every other way of arming a lock runs, with the
     * real threshold rather than none. A rung that somehow exceeded it would
     * come back as Confirm, which this surface refuses to act on rather than
     * arm unconfirmed.
     */
    fun evaluate(durationMs: Long, standingMs: Long): LockRequest.Verdict = LockRequest.evaluate(
        durationMs = durationMs,
        standingMs = standingMs,
        confirmAboveMs = CommandRegistry.CONFIRM_ABOVE_MS,
        confirmed = false,
    )
}
