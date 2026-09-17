package dev.molasses.core.friction

/**
 * When a change to an app's horizon takes effect.
 *
 * ## Widening waits, narrowing does not
 * A wider horizon means friction starts later and stays lighter at the
 * ceiling. If that applied the moment it was set, the first bad stall would
 * become a trip to settings, and the setting would stop being a description of
 * what a session in this app is for and become a button that turns friction
 * off. So a widen is stored as pending and promoted at the next cycle
 * rollover, the same rule and the same reason as the gate mode.
 *
 * A narrow applies immediately. Someone tightening their own leash should not
 * have to wait six hours to do it, and a narrower horizon cannot be an escape
 * from anything. The asymmetry is the point: every delay in this app exists to
 * stop relief arriving on demand, and none of them should stand in the way of
 * a user asking for more friction.
 *
 * ## Why this is a type and not two lines in the engine
 * Same reason as `LockEnforcement`: it is a precedence rule whose two halves
 * point in opposite directions, and one written as a function is one that can
 * be asserted. It also has to give the same answer in the engine and in the
 * settings screen, which reads it to decide whether to show a pending line.
 *
 * Pure; no Android imports. Unit-tested in `HorizonPolicyTest`.
 */
object HorizonPolicy {

    /**
     * The offered horizons, in minutes, as milliseconds.
     *
     * Discrete rather than a free slider. A slider invites tuning a number
     * nobody can feel the difference in, and the useful question is which kind
     * of session this app is for, which has about nine answers.
     */
    val STEPS_MS: List<Long> =
        listOf(10L, 15L, 18L, 20L, 25L, 30L, 40L, 50L, 60L).map { it * 60_000 }

    /** No pending change. Zero rather than null because the proto stores it. */
    const val NONE = 0L

    /** The current horizon and whatever is waiting for the next rollover. */
    data class State(val horizonMs: Long, val pendingHorizonMs: Long = NONE) {
        val hasPending: Boolean get() = pendingHorizonMs != NONE
    }

    /**
     * The nearest offered step. A value from an older build, a corrupt read or
     * a rounding error resolves to a real step rather than being rejected.
     */
    fun snap(horizonMs: Long): Long {
        val clamped = FrictionCurve.clampHorizon(horizonMs)
        return STEPS_MS.minBy { kotlin.math.abs(it - clamped) }
    }

    /** One step wider, or the same value when already at the top. */
    fun wider(horizonMs: Long): Long {
        val i = STEPS_MS.indexOf(snap(horizonMs))
        return STEPS_MS.getOrElse(i + 1) { STEPS_MS.last() }
    }

    /** One step narrower, or the same value when already at the bottom. */
    fun narrower(horizonMs: Long): Long {
        val i = STEPS_MS.indexOf(snap(horizonMs))
        return STEPS_MS.getOrElse(i - 1) { STEPS_MS.first() }
    }

    /**
     * Apply a request against the state it is made from.
     *
     * Pure and **idempotent**: applying the same request twice gives the same
     * state, which is what lets the stored request be the user's standing
     * preference rather than a one-shot command that has to be cleared after
     * it is read. Nothing has to remember whether it already handled it.
     *
     * A request equal to the current horizon cancels a pending widen. That is
     * the only way to take one back, and it has to exist: setting sixty and
     * then thinking better of it before the cycle turns is the case the delay
     * is for, and a delay with no cancel would be a trap rather than a pause.
     */
    fun request(state: State, requestedMs: Long): State {
        val requested = snap(requestedMs)
        val current = snap(state.horizonMs)
        return when {
            // Narrower, or back to where we started: now, and drop any widen
            // that was waiting.
            requested <= current -> State(requested, NONE)
            // Wider: waits. A newer widen replaces an older one rather than
            // queueing behind it.
            else -> State(current, requested)
        }
    }

    /** The cycle turned. Anything waiting is now in force. */
    fun promote(state: State): State =
        if (state.hasPending) State(snap(state.pendingHorizonMs), NONE) else state

    /**
     * Read a stored pair, substituting the default for an unset horizon.
     *
     * A zero horizon is what every existing install reads on the first launch
     * after this ships, and it has to mean the default rather than ten
     * minutes clamped up from zero.
     */
    fun of(horizonMs: Long, pendingHorizonMs: Long): State = State(
        horizonMs = if (horizonMs <= 0L) FrictionCurve.DEFAULT_HORIZON_MS else snap(horizonMs),
        pendingHorizonMs = if (pendingHorizonMs <= 0L) NONE else snap(pendingHorizonMs),
    )
}
