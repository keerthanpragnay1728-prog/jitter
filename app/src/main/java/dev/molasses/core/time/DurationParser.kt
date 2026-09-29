package dev.molasses.core.time

import dev.molasses.core.lock.LockLadder

/**
 * `45m`, `2h`, `3d`, `1h30m`, `30s`.
 *
 * Used by every REPL command that takes a duration. Deliberately strict: a
 * duration that parses to something other than what the user meant arms a real
 * lock, and there is no undo by design.
 *
 * Pure; no Android imports. Unit-tested in `DurationParserTest`.
 */
object DurationParser {

    sealed interface Result {
        data class Ok(val ms: Long) : Result
        data class Err(val kind: Kind) : Result
    }

    enum class Kind {
        EMPTY,

        /** Not a sequence of number-unit pairs at all. */
        MALFORMED,

        /** Parsed, but came to zero. `0m` is not a lock, it is a typo. */
        ZERO,

        /** Above the 30 day cap. */
        TOO_LONG,
    }

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * One or more `<digits><unit>` pairs, no separators, no sign.
     *
     * A leading sign is not accepted anywhere, so `-5m` is MALFORMED rather
     * than a negative that later code has to defend against. Rejecting at the
     * parser is the only place that guarantee can be made once.
     */
    private val PAIR = Regex("([0-9]+)([smhd])")
    private val WHOLE = Regex("^([0-9]+[smhd])+$")

    val MAX_MS: Long = LockLadder.MAX_MS

    fun parse(input: String): Result {
        val text = input.trim().lowercase()
        if (text.isEmpty()) return Result.Err(Kind.EMPTY)
        if (!WHOLE.matches(text)) return Result.Err(Kind.MALFORMED)

        var total = 0L
        for (m in PAIR.findAll(text)) {
            val value = m.groupValues[1].toLongOrNull() ?: return Result.Err(Kind.MALFORMED)
            val unit = when (m.groupValues[2]) {
                "s" -> SECOND
                "m" -> MINUTE
                "h" -> HOUR
                "d" -> DAY
                else -> return Result.Err(Kind.MALFORMED)
            }
            // Checked before multiplying. A user typing 99999999999d would
            // otherwise overflow Long and land on a negative duration, which
            // reads as "already expired" and is the one failure that silently
            // grants relief.
            if (value > MAX_MS / unit) return Result.Err(Kind.TOO_LONG)
            val part = value * unit
            if (total > MAX_MS - part) return Result.Err(Kind.TOO_LONG)
            total += part
        }

        if (total == 0L) return Result.Err(Kind.ZERO)
        if (total > MAX_MS) return Result.Err(Kind.TOO_LONG)
        return Result.Ok(total)
    }
}
