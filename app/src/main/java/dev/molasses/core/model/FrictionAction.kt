package dev.molasses.core.model

/** The only three things [dev.molasses.engine.FrictionEngine] can ask for. */
sealed interface FrictionAction {
    /** Arm the touch sink for [ms]. */
    data class Stall(val ms: Long) : FrictionAction

    /** Show the movement gate for [tier]. */
    data class Gate(val tier: Int) : FrictionAction

    /** Do nothing. Under five minutes, the OS behaves normally. */
    data object None : FrictionAction
}
