package dev.molasses.core.time

/**
 * A time of day: `6am`, `6:00`, `06:00`, `18:30`, `6pm`, `12am`, `12pm`.
 *
 * Used by `$ bedtime` and by the wake-time setting. Returns minutes since
 * midnight rather than a timestamp, because the caller is the only thing that
 * knows which day is meant and a parser that guessed would be wrong at
 * midnight.
 *
 * ## The two cases people get wrong
 * `12am` is midnight, minute 0. `12pm` is noon, minute 720. The rule is that
 * the 12 hour clock runs 12, 1, 2 ... 11 within each half, so hour 12 is the
 * *start* of its half rather than the end. Both are covered by tests because
 * getting either backwards moves a bedtime lock by twelve hours, which the
 * user would experience as the app locking their phone all day.
 *
 * Pure; no Android imports. Unit-tested in `TimeParserTest`.
 */
object TimeParser {

    sealed interface Result {
        /** Minutes since midnight, 0 to 1439. */
        data class Ok(val minuteOfDay: Int) : Result
        data class Err(val kind: Kind) : Result
    }

    enum class Kind {
        EMPTY,
        MALFORMED,

        /** Shaped right, out of range: `25:00`, `6:75`, `13pm`. */
        OUT_OF_RANGE,
    }

    const val MINUTES_PER_DAY = 24 * 60

    /** `18:30`, `6:00`, `06:00`. Minutes are required. */
    private val TWENTY_FOUR = Regex("^([0-9]{1,2}):([0-9]{2})$")

    /** `6am`, `6:30pm`, `12am`. Minutes optional. */
    private val TWELVE = Regex("^([0-9]{1,2})(?::([0-9]{2}))?(am|pm)$")

    fun parse(input: String): Result {
        val text = input.trim().lowercase().replace(" ", "")
        if (text.isEmpty()) return Result.Err(Kind.EMPTY)

        TWELVE.matchEntire(text)?.let { m ->
            val hour12 = m.groupValues[1].toIntOrNull() ?: return Result.Err(Kind.MALFORMED)
            val minute = m.groupValues[2].ifEmpty { "0" }.toIntOrNull()
                ?: return Result.Err(Kind.MALFORMED)
            if (hour12 !in 1..12 || minute !in 0..59) return Result.Err(Kind.OUT_OF_RANGE)
            // 12am is hour 0 and 12pm is hour 12. Everything else adds 12 in
            // the afternoon and nothing in the morning.
            val hour24 = when {
                m.groupValues[3] == "am" -> if (hour12 == 12) 0 else hour12
                else -> if (hour12 == 12) 12 else hour12 + 12
            }
            return Result.Ok(hour24 * 60 + minute)
        }

        TWENTY_FOUR.matchEntire(text)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return Result.Err(Kind.MALFORMED)
            val minute = m.groupValues[2].toIntOrNull() ?: return Result.Err(Kind.MALFORMED)
            if (hour !in 0..23 || minute !in 0..59) return Result.Err(Kind.OUT_OF_RANGE)
            return Result.Ok(hour * 60 + minute)
        }

        return Result.Err(Kind.MALFORMED)
    }

    /**
     * Milliseconds from [nowMinuteOfDay] forward to [targetMinuteOfDay].
     *
     * Always strictly in the future: a target equal to now means a full day,
     * not zero. `$ bedtime` at exactly the wake time should lock for a day
     * rather than no-op, because a zero length lock is indistinguishable from
     * the command having failed.
     */
    fun msUntil(nowMinuteOfDay: Int, targetMinuteOfDay: Int): Long {
        val diff = ((targetMinuteOfDay - nowMinuteOfDay) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val minutes = if (diff == 0) MINUTES_PER_DAY else diff
        return minutes * 60_000L
    }
}
