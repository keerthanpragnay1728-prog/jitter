package dev.molasses.core.command

/**
 * The last twenty command lines, newest first.
 *
 * ## Why a history at all
 * The grammar is only worth having if it can be reused. Retyping
 * `block instagram 30m` every evening is the thing that makes a REPL feel
 * like a puzzle rather than a tool, and a list of what you have already typed
 * is the cheapest possible teaching aid: it is made of the user's own
 * successes.
 *
 * ## The confirmation step is never recorded
 * A long lock echoes its canonical form back and waits for a second Enter.
 * That second Enter carries text the app generated, not text the user typed,
 * and recording it would put a fully formed `block instagram 30d` into a list
 * the user taps through. Tapping would refill the prompt with an armed line,
 * one Enter from a month. The first Enter, the one the user actually typed, is
 * recorded instead, so the line comes back and asks to be confirmed again.
 *
 * ## Why a repeat moves rather than duplicates
 * Shells only drop adjacent duplicates, because their history is a scroll.
 * This one is a short tappable list, and twenty slots filled with four
 * distinct commands would be worse than useless. An exact repeat is removed
 * from wherever it was and put back on top.
 *
 * Pure; no Android imports. Unit-tested in `CommandHistoryTest`.
 */
object CommandHistory {

    /**
     * Twenty. Long enough to hold a week of habits, short enough that the
     * list stays scannable and that a line typed months ago has fallen off
     * rather than waiting to be tapped by accident.
     */
    const val MAX = 20

    /**
     * [history] with [line] on top.
     *
     * @param confirmation true when this Enter was the second Enter on a long
     *   lock. Those are dropped: see the class doc.
     */
    fun record(history: List<String>, line: String, confirmation: Boolean): List<String> {
        if (confirmation) return history
        val text = line.trim()
        if (text.isEmpty()) return history
        return (listOf(text) + history.filterNot { it == text }).take(MAX)
    }

    /** Newest first, already capped. The stored order is the display order. */
    fun recent(history: List<String>): List<String> = history.take(MAX)
}
