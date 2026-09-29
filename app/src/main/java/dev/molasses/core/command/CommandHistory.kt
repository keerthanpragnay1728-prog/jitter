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
 * ## A lock confirmation is never recorded, by construction
 * A long lock is confirmed on a full-screen panel with a button, not by a
 * second Enter on an echoed line, so there is no generated text that could
 * reach this list. The line the user typed is recorded when they type it,
 * and comes back from here asking to be confirmed again. This used to take a
 * flag saying "this Enter was the confirmation, drop it"; with no such Enter
 * left, the flag had no caller and is gone.
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

    /** [history] with [line] on top. */
    fun record(history: List<String>, line: String): List<String> {
        val text = line.trim()
        if (text.isEmpty()) return history
        return (listOf(text) + history.filterNot { it == text }).take(MAX)
    }

    /** Newest first, already capped. The stored order is the display order. */
    fun recent(history: List<String>): List<String> = history.take(MAX)
}
