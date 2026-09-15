package dev.molasses.core.bit

/**
 * What a tap on Bit does.
 *
 * ## Why this is not three lines in the composable
 * It was, and it was wrong in a way that cost two thirds of the feature. The
 * host set "last interacted" before choosing a branch, so the tap that opened
 * the readout also un-docked Bit, and by the next frame the second tap took
 * the other branch: the readout could never advance past its first step, and
 * tapping again dismissed it and poked instead.
 *
 * The rule that was missing is that **stepping the readout is not an
 * interaction**. Bit is retreated, and reading it does not bring it back out.
 * Encoding that on the result type rather than in the order of two statements
 * is what makes it impossible to reintroduce.
 *
 * Pure; no Android imports. Unit-tested in `BitTapTest`.
 */
object BitTap {

    /** Taps past this turn Bit away entirely. */
    const val TURN_AWAY_TAPS = 5

    /** Taps past this read as pestering rather than as a poke. */
    const val IRRITATED_TAPS = 2

    sealed interface Action {
        /**
         * True when this should reset the idle clock, bringing Bit out of the
         * bezel. False for a readout step: reading a retreated Bit leaves it
         * retreated.
         */
        val undocks: Boolean

        /** Advance the readout to [step]. */
        data class StepHud(val step: HudStep) : Action {
            override val undocks: Boolean get() = false
        }

        /** Play [reaction]. */
        data class React(val reaction: BitStateMachine.Reaction) : Action {
            override val undocks: Boolean get() = true
        }
    }

    /**
     * @param docked whether Bit has retreated. See [BitDock].
     * @param hudStep the readout's current step, as the host sees it after
     *   its own timeout has been applied.
     * @param taps taps in the current burst, one-based.
     */
    fun onTap(docked: Boolean, hudStep: HudStep, taps: Int): Action = when {
        docked -> Action.StepHud(BitHud.next(hudStep))
        taps >= TURN_AWAY_TAPS -> Action.React(BitStateMachine.Reaction.TurnedAway)
        taps >= IRRITATED_TAPS -> Action.React(BitStateMachine.Reaction.Irritated)
        else -> Action.React(BitStateMachine.Reaction.Poked)
    }
}
