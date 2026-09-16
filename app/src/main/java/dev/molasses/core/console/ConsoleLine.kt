package dev.molasses.core.console

/**
 * Something Bit says, on the launcher console and nowhere else.
 *
 * ## Never an overlay
 * No new window, no second entry in the collision guard, no exposure to the
 * financial suppression set. Bit speaks on a row above the prompt or it does
 * not speak. That is the whole reason this can exist at all without
 * relitigating the overlay safety rules.
 *
 * ## Why the id is a string and not a resource id
 * This is persisted. Resource ids are regenerated on every build, so a stored
 * `@StringRes Int` would point at unrelated copy after an upgrade, and the
 * "never the same line twice in a cycle" rule would be keyed on a number that
 * silently changed meaning. A stable string survives both.
 *
 * Pure; no Android imports. Unit-tested in `ConsoleSpeechTest`.
 */
sealed interface ConsoleLine {

    /**
     * Which budget a line is charged to.
     *
     * An observation is earned and a greeting is arrival, and they are capped
     * separately because sharing would mean a morning greeting costing the
     * day one of its three remarks, or a day spent scrolling silencing the
     * greeting entirely.
     */
    enum class Category { OBSERVATION, GREETING }

    /** Stable across builds. The copy key and the dedupe key, one thing. */
    val id: String

    val category: Category

    /** Format arguments, already rendered to strings so they can persist. */
    val args: List<String>

    /**
     * An observation with nothing to answer.
     *
     * Delivered on returning to the console and gone eight seconds later. It
     * is allowed to expire unread because nothing depends on the user having
     * read it.
     */
    data class Notice(
        override val id: String,
        override val args: List<String> = emptyList(),
        override val category: Category = Category.OBSERVATION,
    ) : ConsoleLine

    /**
     * A question, with two answers.
     *
     * Persists until answered or dismissed, never on a timer. A prompt that
     * expired on a clock would be a question asked into an empty room, and
     * the only thing worse than not asking is asking and then pretending you
     * did not.
     *
     * [action] is an opaque identifier the host maps to an effect. Opaque
     * because this module cannot know what a launcher can do, and because a
     * persisted action has to survive a build the same way an id does.
     */
    data class Prompt(
        override val id: String,
        override val args: List<String> = emptyList(),
        val action: String,
        override val category: Category = Category.OBSERVATION,
    ) : ConsoleLine
}
