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
 * **Seven will be read as a bug, in the other direction from the percent
 * one.** The reading it looks wrong against is both dates inside the year of
 * `today`, which puts 25 December after 1 January and answers three hundred
 * and fifty eight for a range a person wrote as a week. Nobody asks "how long
 * between Christmas and New Year" meaning the wrong way round the calendar,
 * and a span that can come out backwards has to be signed or absolute: signed
 * makes `between` answer negative numbers, absolute makes it answer the long
 * way round. Reaching forward is the only one of the three that answers the
 * question asked. The resolved pair is shown beside the count whenever a date
 * had to be resolved, so the years are on screen rather than inferred.
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

        /**
         * A month name with a year beside it, as in `25 dec 2026`.
         *
         * Its own refusal rather than [UNREADABLE_DATE] because the user
         * wrote a date this understands in every part, and the only thing
         * wrong is the spelling. Nobody guesses from "not a date I know"
         * that ISO is how a year goes in, so the message names the form
         * instead of just refusing.
         */
        YEAR_NEEDS_ISO,

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
        data class Span(
            val days: Long,
            val from: LocalDate,
            val to: LocalDate,
            /**
             * True when at least one date was written in a form that had to
             * be resolved: a bare day and month, or a relative word.
             *
             * The caller shows the resolved pair only when this is set. An
             * ISO date needs no explaining and an echo of it would be a line
             * that input has not earned. A bare `25 dec` does: which year it
             * landed in is a decision this made, and so is the forward reach
             * that puts `between 25 dec and 1 jan` at seven days.
             */
            val resolved: Boolean,
        ) : Result

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
        val parsed = dateFrom(tokens, today)
        if (parsed is Parsed.Bad) return Result.Failed(parsed.error)

        val date = when (parsed) {
            is Parsed.Fixed -> parsed.date
            is Parsed.DayMonth ->
                if (forward) onOrAfter(parsed, today) else onOrBefore(parsed, today)
            is Parsed.Bad -> null
        } ?: return Result.Failed(Error.UNREADABLE_DATE)

        val resolved = parsed !is Parsed.Fixed || parsed.resolved
        return if (forward) {
            Result.Span(date.toEpochDay() - today.toEpochDay(), today, date, resolved)
        } else {
            Result.Span(today.toEpochDay() - date.toEpochDay(), date, today, resolved)
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

        val resolved = (firstParsed !is Parsed.Fixed || firstParsed.resolved) ||
            (secondParsed !is Parsed.Fixed || secondParsed.resolved)
        val from = if (second < first) second else first
        val to = if (second < first) first else second
        return Result.Span(to.toEpochDay() - from.toEpochDay(), from, to, resolved)
    }

    /** A date as written, before the verb decides which year it means. */
    private sealed interface Parsed {
        /**
         * @param resolved true for a relative word, which named a date
         *   without writing one. False for ISO, which wrote it.
         */
        data class Fixed(val date: LocalDate, val resolved: Boolean) : Parsed
        data class DayMonth(val month: Int, val day: Int) : Parsed
        data class Bad(val error: Error) : Parsed
    }

    /**
     * A date as written, before any reference day is known. Public because
     * `$ rem` takes a date too, and a second date parser would be a second
     * set of rules to keep in step with this one: the same forms are
     * accepted and the same ones refused, slash dates included.
     */
    sealed interface DateSpec {
        /** `today`, `tomorrow`, `yesterday`: a number of days from the reference day. */
        data class Offset(val days: Long) : DateSpec

        /** ISO, which names its own year. */
        data class Iso(val date: LocalDate) : DateSpec

        /** A day and a month name, in either order, with the year left open. */
        data class DayMonth(val month: Int, val day: Int) : DateSpec
    }

    sealed interface SpecResult {
        data class Ok(val spec: DateSpec) : SpecResult
        data class Bad(val error: Error) : SpecResult
    }

    /** Read [tokens], lowercased, as one date. No reference day is needed to know the form. */
    fun spec(tokens: List<String>): SpecResult {
        if (tokens.isEmpty()) return SpecResult.Bad(Error.MISSING_DATE)
        if (tokens.any { it.contains('/') }) return SpecResult.Bad(Error.NUMERIC_DATE)

        if (tokens.size == 1) {
            val token = tokens[0]
            when (token) {
                "today" -> return SpecResult.Ok(DateSpec.Offset(0))
                "tomorrow" -> return SpecResult.Ok(DateSpec.Offset(1))
                "yesterday" -> return SpecResult.Ok(DateSpec.Offset(-1))
            }
            val iso = ISO.matchEntire(token)
            if (iso != null) {
                val date = dateOf(
                    iso.groupValues[1].toInt(),
                    iso.groupValues[2].toInt(),
                    iso.groupValues[3].toInt(),
                )
                return if (date != null) SpecResult.Ok(DateSpec.Iso(date))
                else SpecResult.Bad(Error.UNREADABLE_DATE)
            }
            if (NUMERIC.matches(token)) return SpecResult.Bad(Error.NUMERIC_DATE)
            return SpecResult.Bad(Error.UNREADABLE_DATE)
        }

        // Two tokens, a day and a month in either order. A year alongside a
        // month name is not one of the accepted forms: ISO is how a year is
        // written, and a second spelling of the same date is a second thing
        // to keep correct. It gets its own refusal rather than the generic
        // one, because the user wrote something this understands in every
        // part and will not guess from "not a date" that the fix is ISO.
        if (tokens.size == 3 && tokens.any { monthOf(it) != null } && tokens.any { isYear(it) }) {
            return SpecResult.Bad(Error.YEAR_NEEDS_ISO)
        }
        if (tokens.size != 2) return SpecResult.Bad(Error.UNREADABLE_DATE)
        val month = monthOf(tokens[0]) ?: monthOf(tokens[1]) ?: return SpecResult.Bad(Error.UNREADABLE_DATE)
        val dayToken = if (monthOf(tokens[0]) != null) tokens[1] else tokens[0]
        if (NUMERIC.matches(dayToken)) return SpecResult.Bad(Error.NUMERIC_DATE)
        // `dec 2026` is the same mistake one token shorter: a month name and
        // a year, with no day at all.
        if (isYear(dayToken)) return SpecResult.Bad(Error.YEAR_NEEDS_ISO)
        val day = dayToken.toIntOrNull() ?: return SpecResult.Bad(Error.UNREADABLE_DATE)
        if (day < 1 || day > 31) return SpecResult.Bad(Error.UNREADABLE_DATE)
        return SpecResult.Ok(DateSpec.DayMonth(month, day))
    }

    /**
     * The date [spec] names, looking forward from [today]: an offset from
     * it, the ISO date as written, or the next day-and-month on or after it.
     * Null only for a day that exists in no year in reach.
     */
    fun forwardFrom(spec: DateSpec, today: LocalDate): LocalDate? = when (spec) {
        is DateSpec.Offset -> today.plusDays(spec.days)
        is DateSpec.Iso -> spec.date
        is DateSpec.DayMonth -> onOrAfter(Parsed.DayMonth(spec.month, spec.day), today)
    }

    /** [spec] written back in a form [spec] reads as the same thing. */
    fun text(spec: DateSpec): String = when (spec) {
        is DateSpec.Offset -> when (spec.days) {
            0L -> "today"
            1L -> "tomorrow"
            -1L -> "yesterday"
            // Unreachable from [spec], which makes only these three. Written
            // as the ISO date it would be from no reference, rather than
            // guessed, so a render can never invent a relative word.
            else -> "today"
        }
        is DateSpec.Iso -> spec.date.toString()
        is DateSpec.DayMonth -> "${spec.day} ${MONTHS[spec.month - 1].take(3)}"
    }

    private fun dateFrom(tokens: List<String>, today: LocalDate): Parsed =
        when (val result = spec(tokens)) {
            is SpecResult.Bad -> Parsed.Bad(result.error)
            is SpecResult.Ok -> when (val spec = result.spec) {
                is DateSpec.Offset -> Parsed.Fixed(today.plusDays(spec.days), resolved = true)
                is DateSpec.Iso -> Parsed.Fixed(spec.date, resolved = false)
                is DateSpec.DayMonth -> Parsed.DayMonth(spec.month, spec.day)
            }
        }

    /** Four digits, which in a date this app accepts can only be a year. */
    private fun isYear(token: String): Boolean =
        token.length == 4 && token.all { it.isDigit() }

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
