package dev.molasses.core.util

import java.time.LocalDate

/**
 * Day counting, for the three questions a person actually asks a phone about
 * a date: how long until, how long since, how long between.
 *
 * ## Why `java.time.LocalDate` is here and nothing else from `java.time` is
 * `SimpleDateFormat` and friends are locale and timezone dependent, which is
 * exactly the environmental dependency the pure rule exists to keep out.
 * `LocalDate` is arithmetic on three integers: no I/O, no locale, no
 * timezone. Hand-rolling days-from-civil to protect the convention would buy
 * nothing and would put the century leap rule (2100 is not a leap year) in
 * our own hands, where an off-by-one hides for a year.
 *
 * So `LocalDate` is admitted and **no other `java.time` type is**. Not
 * `LocalDateTime`, not `ZonedDateTime`, not `Instant`, not `Duration`, and
 * not `ChronoUnit`: day differences go through [LocalDate.toEpochDay], which
 * is a method on the admitted type rather than a second import.
 * `PurityTest` names this file and this type, so the next `java.*` import has
 * to argue for itself rather than inherit a precedent.
 *
 * ## Today is injected
 * Never read from a clock. `today` is a parameter, so every answer is a
 * function of its input and every test is deterministic.
 *
 * ## What a bare day and month means
 * `until` resolves it to the next occurrence and `since` to the previous
 * one, which is what the words mean. Both are inclusive of today, so
 * `days until 25 dec` asked on 25 December is zero rather than a year.
 *
 * `between` is a span and a span runs forward, so the first date resolves
 * inside the year of `today` and the second resolves to its first occurrence
 * on or after the first. That is what makes `days between 25 dec and 1 jan`
 * seven rather than three hundred and fifty eight. A span has no direction,
 * so two explicit dates in the other order give the same count.
 *
 * ## Numeric dates are refused
 * `25/12/2026` and `12/25/2026` are the same characters read two ways by
 * different users, with nothing on screen to say which. Refusing them is the
 * same refusal this app makes everywhere else, and it is what keeps locale
 * out of the parser. ISO is accepted because the year comes first, which
 * settles the order without knowing where the reader lives.
 *
 * Unit-tested in `DateMathTest`.
 */
object DateMath {

    /** The three questions. */
    enum class Verb { UNTIL, SINCE, BETWEEN }

    enum class Error {
        /** Nothing was typed. */
        EMPTY,

        /** The first word is not one of the three verbs. */
        UNKNOWN_VERB,

        /** A verb with no date after it. */
        MISSING_DATE,

        /** `between` without an `and` and a second date. */
        MISSING_RANGE,

        /** A slash, dash or dot numeric date, which is read two ways. */
        NUMERIC_DATE,

        /** Not a date this understands. */
        UNREADABLE_DATE,
    }

    sealed interface Result {
        /**
         * [days] from [from] to [to], both resolved.
         *
         * The count is signed for `until` and `since`, because an explicit
         * date on the wrong side of today has a true answer and a negative
         * number is it. It is never negative for `between`, which is a span.
         */
        data class Span(val days: Long, val from: LocalDate, val to: LocalDate) : Result

        data class Failed(val error: Error) : Result
    }

    /** Full names first, so a three-letter prefix match cannot shadow one. */
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    /** ISO, and only ISO: the year leads, so the order needs no locale. */
    private val ISO = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")

    /** Anything else built out of digits and a separator. */
    private val NUMERIC = Regex("^[0-9]+[-/.][0-9./-]*$")

    /**
     * How far either side of [LocalDate.getYear] a bare day and month may
     * land. Only `29 feb` ever needs more than one step, and the widest gap
     * between leap years is the eight the century rule makes.
     */
    private const val YEAR_REACH = 8

    /**
     * Parse and answer, or say what was wrong.
     *
     * The leading `days` is optional so that a caller which has already
     * consumed the command word passes only what is left.
     */
    fun parse(input: String, today: LocalDate): Result {
        val tokens = input.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.Failed(Error.EMPTY)

        val head = if (tokens[0] == "days") tokens.drop(1) else tokens
        if (head.isEmpty()) return Result.Failed(Error.UNKNOWN_VERB)

        val verb = when (head[0]) {
            "until" -> Verb.UNTIL
            "since" -> Verb.SINCE
            "between" -> Verb.BETWEEN
            else -> return Result.Failed(Error.UNKNOWN_VERB)
        }
        val rest = head.drop(1)
        if (rest.isEmpty()) return Result.Failed(Error.MISSING_DATE)

        return when (verb) {
            Verb.UNTIL -> single(rest, today, forward = true)
            Verb.SINCE -> single(rest, today, forward = false)
            Verb.BETWEEN -> between(rest, today)
        }
    }

    private fun single(tokens: List<String>, today: LocalDate, forward: Boolean): Result {
        val date = when (val parsed = dateFrom(tokens, today)) {
            is Parsed.Bad -> return Result.Failed(parsed.error)
            is Parsed.Fixed -> parsed.date
            is Parsed.DayMonth ->
                if (forward) onOrAfter(parsed, today) else onOrBefore(parsed, today)
        } ?: return Result.Failed(Error.UNREADABLE_DATE)

        return if (forward) {
            Result.Span(date.toEpochDay() - today.toEpochDay(), today, date)
        } else {
            Result.Span(today.toEpochDay() - date.toEpochDay(), date, today)
        }
    }

    private fun between(tokens: List<String>, today: LocalDate): Result {
        val joiner = tokens.indexOf("and")
        if (joiner <= 0 || joiner == tokens.lastIndex) return Result.Failed(Error.MISSING_RANGE)

        val firstParsed = dateFrom(tokens.take(joiner), today)
        if (firstParsed is Parsed.Bad) return Result.Failed(firstParsed.error)
        val secondParsed = dateFrom(tokens.drop(joiner + 1), today)
        if (secondParsed is Parsed.Bad) return Result.Failed(secondParsed.error)

        val first = when (firstParsed) {
            is Parsed.Fixed -> firstParsed.date
            // The year of `today`, or the next year the day exists in, which
            // only `29 feb` ever needs.
            is Parsed.DayMonth -> onOrAfter(firstParsed, LocalDate.of(today.year, 1, 1))
            is Parsed.Bad -> null
        } ?: return Result.Failed(Error.UNREADABLE_DATE)

        val second = when (secondParsed) {
            is Parsed.Fixed -> secondParsed.date
            is Parsed.DayMonth -> onOrAfter(secondParsed, first)
            is Parsed.Bad -> null
        } ?: return Result.Failed(Error.UNREADABLE_DATE)

        val from = if (second < first) second else first
        val to = if (second < first) first else second
        return Result.Span(to.toEpochDay() - from.toEpochDay(), from, to)
    }

    /** A date as written, before the verb decides which year it means. */
    private sealed interface Parsed {
        data class Fixed(val date: LocalDate) : Parsed
        data class DayMonth(val month: Int, val day: Int) : Parsed
        data class Bad(val error: Error) : Parsed
    }

    private fun dateFrom(tokens: List<String>, today: LocalDate): Parsed {
        if (tokens.isEmpty()) return Parsed.Bad(Error.MISSING_DATE)
        if (tokens.any { it.contains('/') }) return Parsed.Bad(Error.NUMERIC_DATE)

        if (tokens.size == 1) {
            val token = tokens[0]
            when (token) {
                "today" -> return Parsed.Fixed(today)
                "tomorrow" -> return Parsed.Fixed(today.plusDays(1))
                "yesterday" -> return Parsed.Fixed(today.minusDays(1))
            }
            val iso = ISO.matchEntire(token)
            if (iso != null) {
                val date = dateOf(
                    iso.groupValues[1].toInt(),
                    iso.groupValues[2].toInt(),
                    iso.groupValues[3].toInt(),
                )
                return if (date != null) Parsed.Fixed(date) else Parsed.Bad(Error.UNREADABLE_DATE)
            }
            if (NUMERIC.matches(token)) return Parsed.Bad(Error.NUMERIC_DATE)
            return Parsed.Bad(Error.UNREADABLE_DATE)
        }

        // Two tokens, a day and a month in either order. A year alongside a
        // month name is not one of the accepted forms: ISO is how a year is
        // written, and accepting a third token here would be a second way to
        // say the same thing.
        if (tokens.size != 2) return Parsed.Bad(Error.UNREADABLE_DATE)
        val month = monthOf(tokens[0]) ?: monthOf(tokens[1]) ?: return Parsed.Bad(Error.UNREADABLE_DATE)
        val dayToken = if (monthOf(tokens[0]) != null) tokens[1] else tokens[0]
        if (NUMERIC.matches(dayToken)) return Parsed.Bad(Error.NUMERIC_DATE)
        val day = dayToken.toIntOrNull() ?: return Parsed.Bad(Error.UNREADABLE_DATE)
        if (day < 1 || day > 31) return Parsed.Bad(Error.UNREADABLE_DATE)
        return Parsed.DayMonth(month, day)
    }

    /** The month [token] names, three letters or in full, or null. */
    private fun monthOf(token: String): Int? {
        val index = MONTHS.indexOfFirst { it == token || (token.length == 3 && it.startsWith(token)) }
        return if (index < 0) null else index + 1
    }

    /**
     * Build a date, or null if the day does not exist in that month.
     *
     * The validity question is asked of [LocalDate] rather than answered
     * here, because the answer for `29 feb` depends on the century rule and
     * that is the part worth not hand-rolling.
     */
    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? {
        if (year < 1 || year > 9999) return null
        if (month < 1 || month > 12) return null
        if (day < 1) return null
        val first = LocalDate.of(year, month, 1)
        if (day > first.lengthOfMonth()) return null
        return first.withDayOfMonth(day)
    }

    /**
     * The first occurrence of [dayMonth] on or after [from].
     *
     * The loop is for `29 feb` and nothing else: every other day exists in
     * the first year tried.
     */
    private fun onOrAfter(dayMonth: Parsed.DayMonth, from: LocalDate): LocalDate? {
        for (year in from.year..(from.year + YEAR_REACH)) {
            val candidate = dateOf(year, dayMonth.month, dayMonth.day) ?: continue
            if (!candidate.isBefore(from)) return candidate
        }
        return null
    }

    /** The last occurrence of [dayMonth] on or before [from]. */
    private fun onOrBefore(dayMonth: Parsed.DayMonth, from: LocalDate): LocalDate? {
        for (year in from.year downTo (from.year - YEAR_REACH)) {
            val candidate = dateOf(year, dayMonth.month, dayMonth.day) ?: continue
            if (!candidate.isAfter(from)) return candidate
        }
        return null
    }
}
