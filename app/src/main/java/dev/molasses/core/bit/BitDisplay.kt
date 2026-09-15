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
 * The glitch at the top of that table is
 * [BitStateMachine.Reaction.Glitching], a burst fired once when the user
 * crosses into the terminal of the friction curve. It used to be
 * [BitStateMachine.Mood.GLITCHED], which is permanent past the terminal, and
 * that was a bug rather than a strict reading: everything below the top of
 * the table became unreachable forever, so a user past the terminal got no
 * command feedback and no readout at all. The command bar went mute exactly
 * when someone was most likely to reach for it.
 *
 * The permanent terminal signal did not go away, it moved to where it
 * belongs: `Mood.GLITCHED` resolves at the bottom of the table like any other
 * mood, and the stall marker turns the terminal colour.
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
 * ## Where the docked slit sits
 * Below the armed tell, above the resting face:
 *
 * ```
 * glitch  >  HUD  >  reaction  >  armed  >  slit  >  mood
 * ```
 *
 * Above the mood because a retreated Bit is meant to be a glyph rather than a
 * face. Below the armed tell because that is the one thing in this app that
 * is otherwise completely invisible, and hiding it behind a retreat would
 * lose it in exactly the moment it means something. Nothing moves either way:
 * every glyph occupies the same padded slot, so swapping between a slit and a
 * face cannot touch the snap target.
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

    /**
     * The retreated glyph, carrying one character of status.
     *
     * A separate case rather than a face, because it is not one: it has no
     * mood, no reaction and no expression, and folding it in would mean every
     * face branch having to ask whether it was really a slit.
     */
    data class Slit(val glyph: String) : BitDisplay

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
         * @param docked Bit has retreated to the bezel. See [BitDock].
         * @param penaltyAccruing a checkpoint is overdue. See [BitStatus].
         */
        fun resolve(
            mood: BitStateMachine.Mood,
            reaction: BitStateMachine.Reaction,
            hud: Hud?,
            shutterArmed: Boolean,
            curfew: Boolean,
            docked: Boolean = false,
            penaltyAccruing: Boolean = false,
        ): BitDisplay {
            // 1. The glitch burst. Nothing displaces it, not even a tap the
            //    user just made, because a readout that survived the glitch
            //    would give the mechanism away. Transient, so unlike the old
            //    permanent glitch it cannot starve everything below it.
            if (reaction == BitStateMachine.Reaction.Glitching) return Face(mood, reaction)

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

            // 4. A reaction, or the armed tell. Both outrank the retreat: a
            //    command the user just typed is owed its answer, and the
            //    armed window is the one thing in this app that is otherwise
            //    invisible.
            if (reaction != BitStateMachine.Reaction.None) return Face(resting, reaction)
            if (shutterArmed) return Face(resting, reaction)

            // 5. Retreated. One glyph, no text, nothing to tap.
            if (docked) return Slit(BitGlyph.slitFor(curfew, penaltyAccruing))

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
