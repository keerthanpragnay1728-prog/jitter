package dev.molasses.core.console

/**
 * The line Bit says on the first launcher visit after an unlock.
 *
 * ## Why it is a category and not just another notice
 * An observation is earned: the user did something and Bit remarks on it.
 * A greeting is not earned, it is arrival, and it is the one thing on the
 * console that exists to make the phone feel like somewhere rather than
 * something. Sharing the observation budget would mean a morning greeting
 * costing the day one of its three remarks, and it would mean a day spent
 * scrolling silencing the greeting entirely, which is exactly backwards.
 *
 * So it has its own counter, and the spec always said so.
 *
 * ## Why most of the day is silent
 * A greeting at six in the evening is a greeting to someone who has been
 * holding the phone all afternoon. The bands are the two ends of a day and
 * the moment it has gone on too long; the middle has nothing to say, and
 * saying nothing costs nothing, which is why a silent window does not spend
 * a greeting.
 *
 * Pure; no Android imports. Unit-tested in `GreetingTest`.
 */
object Greeting {

    /** Three a day. Separate from the observation caps by design. */
    const val MAX_PER_DAY = 3

    const val MINUTES_PER_DAY = 24 * 60

    enum class Band(val id: String) {
        MORNING(ConsoleIds.GREETING_MORNING),
        AFTERNOON(ConsoleIds.GREETING_AFTERNOON),
        LATE(ConsoleIds.GREETING_LATE),
    }

    /**
     * The band [minuteOfDay] falls in, or null when Bit has nothing to say.
     *
     * ```
     * 05:00 - 11:59  morning
     * 12:00 - 16:59  afternoon
     * 17:00 - 22:29  silent
     * 22:30 - 23:59  late
     * 00:00 - 04:59  silent
     * ```
     *
     * The brief wrote the afternoon as 12:00 to 17:00 and the silence as
     * 17:00 to 22:29, which overlap at exactly 17:00. Resolved downward: the
     * afternoon ends at 16:59 and silence begins on the hour, because a
     * boundary that belongs to the quieter side is the one that cannot
     * produce a line nobody wanted.
     */
    fun bandFor(minuteOfDay: Int): Band? {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return when {
            m < 5 * 60 -> null
            m < 12 * 60 -> Band.MORNING
            m < 17 * 60 -> Band.AFTERNOON
            m < 22 * 60 + 30 -> null
            else -> Band.LATE
        }
    }

    /** The line for [minuteOfDay], or null in a silent window. */
    fun lineFor(minuteOfDay: Int): ConsoleLine.Notice? =
        bandFor(minuteOfDay)?.let {
            ConsoleLine.Notice(id = it.id, category = ConsoleLine.Category.GREETING)
        }
}
