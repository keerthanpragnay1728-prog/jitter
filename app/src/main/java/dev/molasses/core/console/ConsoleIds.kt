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

    val ALL: List<String> = listOf(SCROLLED)
}
