package dev.molasses.core.console

/**
 * Every line Bit can say, by its stable id.
 *
 * The id is the copy key and the dedupe key at once, so a line is said at
 * most once per cycle by construction. Listed here rather than only at the
 * site that produces it, so `ConsoleCopyTest` can require copy to exist for
 * each one: a line with no copy would enqueue, deliver, spend one of three an
 * hour, and render as nothing.
 *
 * Pure; no Android imports.
 */
object ConsoleIds {

    /**
     * Past tense, deliberately.
     *
     * Bit only speaks on the console, and the observation is made inside a
     * target app, so the line is always delivered after the fact. "You have
     * been scrolling" would be a lie by the time anyone read it.
     */
    const val SCROLLED = "scrolled"

    /**
     * Arrival, on the first launcher visit after an unlock.
     *
     * Three of them rather than one with a time argument, because the copy
     * differs by more than a word and a format string that had to switch
     * greeting on an argument would put the branch in the resource.
     */
    const val GREETING_MORNING = "greeting_morning"
    const val GREETING_AFTERNOON = "greeting_afternoon"
    const val GREETING_LATE = "greeting_late"

    val ALL: List<String> = listOf(
        SCROLLED,
        GREETING_MORNING,
        GREETING_AFTERNOON,
        GREETING_LATE,
    )
}
