package dev.molasses.core.command

/**
 * The reading-window clock of the console's answer, tied to the answer it
 * was started for. Pure.
 *
 * Every show and every clear moves [serial] on. A clock remembers the serial
 * it started under and may dismiss only while that is still the serial in
 * force, so a clock can never dismiss an answer it was not started for, and
 * an answer already cleared (by an edit, a command or leaving) has nothing
 * left for its clock to do: no dismissal, and no "expired" line in the log.
 *
 * The host also restarts its clock on each serial, which cancels the old
 * one. [mayExpire] is the rule that holds even if that cancel were late.
 */
data class AnswerTimer(val serial: Int = 0, val holdMs: Long? = null) {

    companion object {
        /** A new answer, held for [holdMs], or until the user moves on when null. */
        fun shown(timer: AnswerTimer, holdMs: Long?): AnswerTimer = AnswerTimer(timer.serial + 1, holdMs)

        /** The answer went, by any route. Whatever clock was running has nothing left to dismiss. */
        fun cleared(timer: AnswerTimer): AnswerTimer = AnswerTimer(timer.serial + 1, null)

        /** Whether the clock started under [startedSerial] may dismiss the answer now in [current]. */
        fun mayExpire(current: AnswerTimer, startedSerial: Int): Boolean =
            current.serial == startedSerial && current.holdMs != null
    }
}
