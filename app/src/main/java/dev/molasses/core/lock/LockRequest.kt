package dev.molasses.core.lock

/**
 * One decision, shared by every path that can arm a lock.
 *
 * ## Why this is not two implementations
 * `$ block instagram 30d` and dragging a scrubber to 30d must behave
 * identically, and the dangerous half of that is the confirmation step. A
 * second code path that arms a month without echoing it back is not a
 * different UI, it is a hole in the one guard this app has against a mistyped
 * duration that cannot be undone. So both callers evaluate here, and
 * `LockRequestTest` asserts they split the ladder the same way.
 *
 * ## The order of the checks
 * Invalid, then too short, then confirmation. Too short comes before the
 * confirmation on purpose: asking someone to confirm thirty days and then
 * telling them it changed nothing is two steps to reach an outcome that was
 * knowable at the first.
 *
 * Pure; no Android imports. Unit-tested in `LockRequestTest`.
 */
object LockRequest {

    sealed interface Verdict {
        /** Arm it. */
        data class Arm(val durationMs: Long) : Verdict

        /** Echo it back and wait for a second, deliberate confirmation. */
        data class Confirm(val durationMs: Long) : Verdict

        /**
         * A lock at least this long already stands, so this would change
         * nothing. Carries the standing remainder so the caller can say so.
         */
        data class TooShort(val standingMs: Long) : Verdict

        /** Not a duration. A no-op, never an unlock. */
        data object Invalid : Verdict
    }

    /**
     * @param standingMs what `LockRegistry.remainingMs` reports right now.
     * @param confirmAboveMs the threshold from the registry's own row for the
     *   command, so there is one number rather than one per caller. Null
     *   disables the step.
     * @param confirmed true when this is the second, deliberate pass.
     */
    fun evaluate(
        durationMs: Long,
        standingMs: Long,
        confirmAboveMs: Long?,
        confirmed: Boolean = false,
    ): Verdict = when {
        durationMs <= 0L -> Verdict.Invalid
        standingMs >= durationMs -> Verdict.TooShort(standingMs)
        !confirmed && confirmAboveMs != null && durationMs > confirmAboveMs ->
            Verdict.Confirm(durationMs)
        else -> Verdict.Arm(durationMs)
    }
}
