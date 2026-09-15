package dev.molasses.core.bit

/**
 * What occupies Bit's slot this frame.
 *
 * ## Why an axis and not four more booleans
 * Bit now has a mood, a transient reaction, a readout, an armed state and a
 * curfew. As flags on one frame those are thirty two combinations and no
 * statement anywhere about which wins. As a type with one resolver there is
 * one table, it is total, and every pair in it is assertable.
 *
 * ## The order, and why it is that order
 *
 * ```
 * glitch  >  HUD  >  reaction  >  mood
 * ```
 *
 * **Glitch first.** A stall landing while the user is reading the HUD shows
 * the glitch. The illusion outranks the readout, always: the whole premise is
 * that the phone appears to be failing, and a readout that stayed legible
 * through a glitch would say plainly that something is in control of it.
 *
 * **HUD second.** The user just tapped and asked a question. A blink or a
 * poke arriving in the next 40 ms must not eat the answer.
 *
 * **Reaction third, mood last.** Unchanged, and the reason is unchanged: a
 * reaction is something that just happened, a mood is what is true anyway.
 *
 * `ARMED` and `DORMANT` resolve in at the mood level rather than as
 * reactions, because they are conditions rather than events. That means a
 * confirmation still shows over an armed sink, which is right: the user typed
 * something and is owed the answer.
 *
 * Pure; no Android imports. Unit-tested in `BitDisplayTest`.
 */
sealed interface BitDisplay {

    /** A face, with an optional reaction playing over it. */
    data class Face(
        val mood: BitStateMachine.Mood,
        val reaction: BitStateMachine.Reaction,
    ) : BitDisplay

    /** A readout, occupying the same fixed-width slot the face would. */
    data class Hud(val step: HudStep, val text: String) : BitDisplay

    companion object {

        /**
         * The one place precedence is decided.
         *
         * @param mood what [BitStateMachine.moodFor] returned for the
         *   accumulated time. Never [BitStateMachine.Mood.ARMED] or
         *   [BitStateMachine.Mood.DORMANT]; those are produced here.
         * @param hud the readout, or null when no HUD step is showing.
         * @param shutterArmed the sink is armed right now.
         * @param curfew a bedtime lock is standing.
         */
        fun resolve(
            mood: BitStateMachine.Mood,
            reaction: BitStateMachine.Reaction,
            hud: Hud?,
            shutterArmed: Boolean,
            curfew: Boolean,
        ): BitDisplay {
            // 1. Glitch. Nothing displaces it, not even a tap the user just
            //    made, because a readout that survived the glitch would give
            //    the mechanism away.
            if (mood == BitStateMachine.Mood.GLITCHED) {
                return Face(mood, BitStateMachine.Reaction.None)
            }

            // 2. The HUD. The user asked a question 40 ms ago.
            if (hud != null && hud.step != HudStep.NONE) return hud

            // 3. The resting state, which the reaction plays over. Resolved
            //    before the reaction check so that a reaction with no frames
            //    left falls back to the right face.
            val resting = when {
                shutterArmed -> BitStateMachine.Mood.ARMED
                curfew -> BitStateMachine.Mood.DORMANT
                else -> mood
            }

            return Face(resting, reaction)
        }
    }
}

/** Which page of the readout is showing. Advanced by a tap, never by a timer. */
enum class HudStep {
    NONE,

    /** Time until the cycle resets, or until a curfew lifts. */
    PRIMARY,

    /** Cumulative time inside the current cycle. */
    SECONDARY,
}
