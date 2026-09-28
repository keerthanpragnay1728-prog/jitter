package dev.molasses.core.command

/**
 * How long an acknowledgement stays up when it is on a clock. Pure.
 *
 * Most answers are held until the user moves on (the next keystroke, the next
 * command, leaving the launcher) because they are content the user asked for.
 * The reminder acknowledgement is not content. It confirms something the
 * user already knows they did, and left up indefinitely it sat on the
 * console over whatever came next, so it is given a reading window and then
 * goes by itself. The three early exits still apply.
 *
 * 2000 ms plus 250 ms a word, never under 3000 ms and never over 10000 ms.
 */
object ReadingWindow {

    const val BASE_MS = 2_000L
    const val PER_WORD_MS = 250L
    const val FLOOR_MS = 3_000L
    const val CEILING_MS = 10_000L

    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    fun holdMs(text: String): Long = (BASE_MS + words(text) * PER_WORD_MS).coerceIn(FLOOR_MS, CEILING_MS)
}
