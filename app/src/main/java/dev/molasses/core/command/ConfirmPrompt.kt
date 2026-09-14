package dev.molasses.core.command

/**
 * The prompt's pending-confirmation state, as pure functions.
 *
 * ## Why this is not a dialog
 * A modal over a terminal is a different interface wearing the terminal's
 * clothes. The confirmation happens where the command was typed: the line is
 * replaced by its canonical rendering and a second Enter runs it. Anything
 * else the user does cancels, which is the safe direction, and the whole of
 * the mechanism is a nullable [Pending] the host holds.
 *
 * ## Why the echo replaces the line
 * `$ block ig 30d` and `block instagram 30d` are the same command, and the
 * second is the one the user is agreeing to. Echoing the canonical form is
 * the only way the confirmation step is worth having: it shows the parse, so
 * a duration that was read differently than it was typed is visible before it
 * is armed rather than a month later.
 *
 * ## Why editing cancels rather than re-arming
 * The pending line is armed text. Leaving it armed while the user edits it
 * would mean an Enter on a half-typed line runs something that was confirmed
 * in a different form. Cancelling on the first keystroke costs one retype and
 * removes that entirely.
 *
 * Pure; no Android imports. Unit-tested in `ConfirmPromptTest`.
 */
object ConfirmPrompt {

    /**
     * A command held for a second Enter.
     *
     * [line] is what the prompt must contain for the next Enter to count as
     * confirmation. It is the canonical echo, not what the user typed.
     */
    data class Pending(val command: Command, val line: String)

    /** What the host should do with an Enter. */
    sealed interface Decision {
        /** The second Enter. Dispatch [command] with `confirmed = true`. */
        data class Confirm(val command: Command) : Decision

        /** Nothing is pending, or the line changed. Dispatch the line afresh. */
        data object Dispatch : Decision
    }

    fun arm(command: Command, echo: String): Pending = Pending(command, echo)

    /**
     * Whether this Enter confirms.
     *
     * Compares against the armed line rather than merely checking that
     * something is pending, so a pending state that somehow outlived an edit
     * still cannot confirm a line the user did not agree to.
     */
    fun onSubmit(pending: Pending?, line: String): Decision =
        if (pending != null && line.trim() == pending.line) {
            Decision.Confirm(pending.command)
        } else {
            Decision.Dispatch
        }

    /**
     * The pending state after a keystroke: unchanged while the text matches,
     * null the moment it does not.
     */
    fun onTextChanged(pending: Pending?, line: String): Pending? =
        if (pending != null && line.trim() == pending.line) pending else null
}
