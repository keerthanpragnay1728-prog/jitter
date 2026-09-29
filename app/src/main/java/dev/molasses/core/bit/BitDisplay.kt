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
 * glitch > PROMPT > HUD > reaction > ANSWER > NOTICE > armed > battery > slit > mood
 * ```
 *
 * Above the mood because a retreated Bit is meant to be a glyph rather than a
 * face. Below the armed tell because that is the one thing in this app that
 * is otherwise completely invisible, and hiding it behind a retreat would
 * lose it in exactly the moment it means something.
 *
 * This used to claim that nothing moves either way, because every glyph
 * occupied the same padded slot. That is no longer true and was never quite
 * the win it sounded like: a three character slit in a seven character slot
 * drew four cells in from the right bezel, so the one state whose purpose is
 * to be out of the way was the one state that could not reach the edge. The
 * slit now has its own narrower slot, and the resulting change of snap target
 * is handled in `BezelSnap.reSnap` rather than by pretending it does not
 * happen.
 *
 * What makes a second width safe is this table, not the renderer: [Slit] is
 * reachable only from step 8, every other step returns a [Face] or a [Hud],
 * and [Speech] carries a mood rather than a glyph, so it renders a face too.
 * A slit and a face therefore cannot share a frame. `BitGlyphTest` pins that
 * over every case here, because it is now load bearing for the layout and not
 * merely tidy.
 *
 * ## Where Bit's own speech sits
 * A PROMPT is second, under the glitch burst alone. It is a question waiting
 * for an answer, so nothing that is merely informative may bury it, and in
 * particular the readout must not: a stray tap on Bit while a question is up
 * would otherwise replace the question with a timer. That placement is only
 * safe because the glitch is a burst now. While it was a permanent mood a
 * prompt at the terminal tier could never have been seen at all.
 *
 * An ANSWER is between them. Below `reaction` so a poke still interrupts it,
 * because a poke is the user acting on Bit and an answer already on screen
 * must not swallow that. Above `NOTICE` because the user asked for the answer
 * and Bit volunteered the notice, which is the same principle holding up two
 * other rows of this table.
 *
 * A NOTICE is below `reaction`, which is a change from the brief. A reaction
 * is the answer to something the user just did; a notice is something Bit
 * volunteered, and when the two collide the user's own action wins. That is
 * the principle already holding up two other rows of this table. The cap
 * concern that argued for putting it higher is answered in `ConsoleSpeech`
 * instead, by counting renders rather than attempts.
 *
 * ## Which conditions rise above the slit and which do not
 * One rule: a condition the slit can express stays below it, and one it
 * cannot rises above it. The curfew has a glyph, `[z]`, so a retreated Bit
 * already says it. A dying battery has none, so it comes out of the bezel to
 * say it. That also keeps the slit at three states rather than growing one
 * per condition, which is the difference between a mode indicator and a
 * status bar.
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
     * Anything drawn on the row above the prompt.
     *
     * [mood] is the resting state the face should show while it is up, so
     * the host renders one Bit rather than a face in its usual row and a
     * second one in the speech row. Two variants because the text comes from
     * two different places, one lookup and one already rendered, and the row
     * that draws them is the same row.
     */
    sealed interface Spoken : BitDisplay {
        val mood: BitStateMachine.Mood
    }

    /** Bit is saying something it queued. The copy is looked up from [line]. */
    data class Speech(
        val line: dev.molasses.core.console.ConsoleLine,
        override val mood: BitStateMachine.Mood,
    ) : Spoken

    /**
     * The answer to something the user typed.
     *
     * ## Why this is not a reaction
     * It was one, for two commits, and the wrong thing about it was not the
     * number. A reaction expires on a clock because it is a response to a
     * gesture: a poke, a stall, a face changing and changing back. An answer
     * is content the user asked for and may be copying somewhere, and the
     * honest lifetime for that is "until you do something else", which is not
     * a duration. Five seconds was short on the device and nine was a guess
     * at a number that should not exist.
     *
     * So it expires on events rather than on a clock: the next keystroke, the
     * next command, or leaving the launcher. Every one of those is the user
     * moving on, and none of them is a timer.
     *
     * ## Why it does not carry a ConsoleLine
     * `ConsoleLine` is persisted and charged to a budget, and its `id`,
     * `category` and `args` all exist for those two jobs. An answer is
     * neither persisted nor budgeted: it is already rendered, it is gone when
     * you leave, and it is prompted rather than volunteered, so the cap on
     * unprompted lines has nothing to say about it. Making it one would have
     * meant three members that lie, or splitting `ConsoleLine` and threading
     * the split through the proto mapping, the repository and four host
     * signatures, none of which anything here compiles.
     *
     * The thing that actually mattered is that it ranks in this table rather
     * than in the reaction ladder, and that is what [Spoken] buys.
     */
    data class Answer(
        val text: String,
        override val mood: BitStateMachine.Mood,
    ) : Spoken

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
         * @param batteryCritical the battery is about to go. See
         *   `PowerBar.isCritical`.
         * @param curfew a bedtime lock is standing.
         * @param docked Bit has retreated to the bezel. See [BitDock].
         * @param penaltyAccruing a checkpoint is overdue. See [BitStatus].
         * @param prompt a question awaiting an answer, or null.
         * @param notice a delivered observation still inside its eight
         *   seconds, or null.
         */
        fun resolve(
            mood: BitStateMachine.Mood,
            reaction: BitStateMachine.Reaction,
            hud: Hud?,
            shutterArmed: Boolean,
            curfew: Boolean,
            docked: Boolean = false,
            penaltyAccruing: Boolean = false,
            batteryCritical: Boolean = false,
            prompt: dev.molasses.core.console.ConsoleLine.Prompt? = null,
            notice: dev.molasses.core.console.ConsoleLine.Notice? = null,
            answer: String? = null,
        ): BitDisplay {
            val resting = when {
                shutterArmed -> BitStateMachine.Mood.ARMED
                batteryCritical -> BitStateMachine.Mood.BATTERY_CRITICAL
                curfew -> BitStateMachine.Mood.DORMANT
                else -> mood
            }

            // 1. The glitch burst. Nothing displaces it, not even a tap the
            //    user just made, because a readout that survived the glitch
            //    would give the mechanism away. Transient, so unlike the old
            //    permanent glitch it cannot starve everything below it.
            if (reaction == BitStateMachine.Reaction.Glitching) return Face(mood, reaction)

            // 2. A question. Nothing informative may bury something waiting
            //    for an answer, the readout included.
            if (prompt != null) return Speech(prompt, resting)

            // 3. The HUD. The user asked a question 40 ms ago.
            if (hud != null && hud.step != HudStep.NONE) return hud

            // 4. A reaction. The answer to something the user just did
            //    outranks anything Bit volunteered.
            if (reaction != BitStateMachine.Reaction.None) return Face(resting, reaction)

            // 5. An answer. Below the reaction because a poke is the user
            //    acting on Bit and must not be swallowed by a line already
            //    on screen, and above a notice because the user asked for
            //    this one and Bit volunteered that one.
            if (answer != null) return Answer(answer, resting)

            // 6. An observation. Below the reaction deliberately; see the
            //    class doc and ConsoleSpeech for why that is safe.
            if (notice != null) return Speech(notice, resting)

            // 7. The armed tell outranks the retreat: it is the one thing in
            //    this app that is otherwise completely invisible.
            if (shutterArmed) return Face(resting, reaction)

            // 8. A dying battery comes out of the bezel, because no slit
            //    glyph carries it. A curfew stays behind it, because `[z]`
            //    does.
            if (batteryCritical) return Face(resting, reaction)

            // 9. Retreated. One glyph, no text, nothing to tap.
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
