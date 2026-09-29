package dev.molasses.core.lock

/**
 * Whether a locked package may actually be bounced, and how long the message
 * stays up before it is.
 *
 * ## Why this is a type and not three lines in the service
 * It encodes two precedence rules that point in opposite directions, and both
 * of them are the kind that get "simplified" by someone reading the service a
 * year from now. Writing them as a function makes them assertable.
 *
 * Pure; no Android imports. Unit-tested in `LockEnforcementTest`.
 */
object LockEnforcement {


    /**
     * How long the message stays up after the home action, so it covers the
     * transition rather than vanishing to reveal the app for a frame.
     */
    const val HOME_SETTLE_MS = 400L

    sealed interface Decision {
        /** Show the message, then go home. */
        data class Enforce(val remainingMs: Long) : Decision

        /** Do nothing. [why] is for the log, not for the user. */
        data class Stand(val why: String) : Decision
    }

    /**
     * @param sensitiveForeground true when the foreground package is in the
     *   never-draw-over set.
     * @param paused true when the user has armed the 15 minute pause.
     */
    fun decide(
        remainingMs: Long,
        sensitiveForeground: Boolean,
        paused: Boolean,
    ): Decision = when {
        remainingMs <= 0L -> Decision.Stand("not locked")

        // The suppression set outranks a lock, unconditionally. An overlay
        // sets FLAG_WINDOW_IS_OBSCURED on that app's touches and a hardened
        // payment app is entitled to refuse the transaction. Enforcing here
        // would also mean sending someone home mid-payment, which is worse
        // than the overlay.
        sensitiveForeground -> Decision.Stand("sensitive package")

        // A pause does NOT outrank a lock, and this is the asymmetry worth
        // being explicit about. A pause suspends graduated friction, stalls
        // and checkpoints, which are nudges the user never agreed to
        // individually. A lock is a commitment they made deliberately, past a
        // confirmation step for anything over a day. A fifteen minute button
        // in settings that cancels a thirty day block would make every lock
        // in this app decorative, which is the same failure as a bare
        // wall-clock deadline and gets the same answer: err toward friction.
        paused -> Decision.Enforce(remainingMs)

        else -> Decision.Enforce(remainingMs)
    }
}
