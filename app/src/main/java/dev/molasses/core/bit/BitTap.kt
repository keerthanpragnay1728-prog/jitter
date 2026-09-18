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
 * The rule that was missing is that **opening or advancing the readout is not
 * an interaction, and closing it is**. Bit is retreated, and reading it does
 * not bring it back out; putting it away is the user saying they are done.
 * Encoding that on the result type rather than in the order of two statements
 * is what makes it impossible to reintroduce.
 *
 * ## The half of that rule that was missing in turn
 * The first version said only that stepping is not an interaction, with no
 * exception, and that shipped a worse lockup than the one it fixed. `isDocked`
 * is derived from the host's last-interaction clock and the tap was the only
 * thing that moved it, so once Bit docked on idle every tap was a step, every
 * step declined to move the clock, and the reaction ladder on the undocked
 * branch became unreachable. Tapping cycled three readouts forever and no
 * number of taps ever returned a face.
 *
 * The first bug made two thirds of the readout unreachable; the second made
 * all of the faces unreachable. Trading one for the other is what happens when
 * a rule is written without its exit.
 *
 * So the cycle now ends where it started and lets go on the way out: tap, tap,
 * tap, and the third one closes the readout and hands Bit back. A drag also
 * reached the faces the whole time, through the host's drag-start callback,
 * but a drag is not the gesture the readout teaches and it moves Bit as a side
 * effect.
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
         * bezel. False while the readout is being read: reading a retreated
         * Bit leaves it retreated.
         */
        val undocks: Boolean

        /**
         * Advance the readout to [step].
         *
         * Undocks only on the step that closes it, which is the one arriving
         * back at [HudStep.NONE]. Opening and advancing are reading, and
         * reading leaves Bit where it is; closing is the user putting it away,
         * which is an interaction and hands the face back.
         *
         * Without that exit the readout is a trap. See the class doc: the
         * host's dock state is derived from a clock only an interaction moves,
         * so a cycle that never reports one never ends.
         */
        data class StepHud(val step: HudStep) : Action {
            override val undocks: Boolean get() = step == HudStep.NONE
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
