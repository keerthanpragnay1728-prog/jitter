package dev.molasses.core.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DateMathTest {

    /** A Friday in the middle of a year, so nothing depends on an edge. */
    private val today: LocalDate = LocalDate.of(2026, 9, 18)

    private fun days(input: String, on: LocalDate = today): Long =
        (DateMath.parse(input, on) as DateMath.Result.Span).days

    private fun span(input: String, on: LocalDate = today): DateMath.Result.Span =
        DateMath.parse(input, on) as DateMath.Result.Span

    private fun error(input: String, on: LocalDate = today): DateMath.Error =
        (DateMath.parse(input, on) as DateMath.Result.Failed).error

    // ------------------------------------------------------------ the verbs

    @Test
    fun `until an explicit date`() {
        assertEquals(98L, days("days until 2026-12-25"))
    }

    @Test
    fun `since an explicit date`() {
        assertEquals(260L, days("days since 2026-01-01"))
    }

    @Test
    fun `between two explicit dates`() {
        assertEquals(365L, days("days between 2026-01-01 and 2027-01-01"))
    }

    @Test
    fun `the leading days is optional`() {
        assertEquals(days("days until 2026-12-25"), days("until 2026-12-25"))
    }

    @Test
    fun `an explicit date on the wrong side of the verb gives a negative count`() {
        // The true answer, rather than a refusal. A date in the past is
        // still a date the user named.
        assertEquals(-261L, days("days until 2025-12-31"))
    }

    // ------------------------------------------------------------ the forms

    @Test
    fun `relative words`() {
        assertEquals(0L, days("days until today"))
        assertEquals(1L, days("days until tomorrow"))
        assertEquals(1L, days("days since yesterday"))
    }

    @Test
    fun `month name in either order and at either length`() {
        val expected = days("days until 2026-12-25")
        assertEquals(expected, days("days until 25 dec"))
        assertEquals(expected, days("days until dec 25"))
        assertEquals(expected, days("days until 25 december"))
        assertEquals(expected, days("days until december 25"))
    }

    @Test
    fun `iso does not need its zeros`() {
        assertEquals(days("days until 2026-12-05"), days("days until 2026-12-5"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(days("days until 25 dec"), days("DAYS UNTIL 25 DEC"))
    }

    @Test
    fun `extra whitespace does not change the answer`() {
        assertEquals(days("days until 25 dec"), days("  days   until    25   dec  "))
    }

    // ------------------------------------- which year a bare day-month means

    @Test
    fun `until takes the next occurrence`() {
        assertEquals(LocalDate.of(2026, 12, 25), span("days until 25 dec").to)
    }

    @Test
    fun `since takes the previous occurrence`() {
        assertEquals(LocalDate.of(2025, 12, 25), span("days since 25 dec").from)
    }

    @Test
    fun `until crosses the year boundary forwards`() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertEquals(1L, days("days until 1 jan", newYearsEve))
        assertEquals(LocalDate.of(2027, 1, 1), span("days until 1 jan", newYearsEve).to)
    }

    @Test
    fun `since crosses the year boundary backwards`() {
        val newYearsDay = LocalDate.of(2026, 1, 2)
        assertEquals(8L, days("days since 25 dec", newYearsDay))
        assertEquals(LocalDate.of(2025, 12, 25), span("days since 25 dec", newYearsDay).from)
    }

    @Test
    fun `both inclusive verbs answer zero on the day itself`() {
        assertEquals(0L, days("days until 18 sep"))
        assertEquals(0L, days("days since 18 sep"))
    }

    @Test
    fun `a span runs forward, so the second bare date follows the first`() {
        assertEquals(7L, days("days between 25 dec and 1 jan"))
        assertEquals(358L, days("days between 1 jan and 25 dec"))
    }

    @Test
    fun `a span is never negative`() {
        assertEquals(
            days("days between 2026-01-01 and 2027-01-01"),
            days("days between 2027-01-01 and 2026-01-01"),
        )
    }

    // ------------------------------------------------------------ leap years

    @Test
    fun `2024 is a leap year`() {
        assertEquals(2L, days("days between 2024-02-28 and 2024-03-01"))
        assertEquals(366L, days("days between 2024-01-01 and 2025-01-01"))
    }

    @Test
    fun `2100 is not a leap year, which is the century rule`() {
        assertEquals(1L, days("days between 2100-02-28 and 2100-03-01"))
        assertEquals(365L, days("days between 2100-01-01 and 2101-01-01"))
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until 2100-02-29"))
    }

    @Test
    fun `a bare 29 feb walks forward to the next leap year`() {
        assertEquals(LocalDate.of(2028, 2, 29), span("days until 29 feb").to)
    }

    @Test
    fun `a bare 29 feb walks over the century that has none`() {
        // 2096 is a leap year, 2100 is not, so the next one after March 2096
        // is 2104. Eight years is the widest that gap ever gets.
        val after = LocalDate.of(2096, 3, 1)
        assertEquals(LocalDate.of(2104, 2, 29), span("days until 29 feb", after).to)
        val before = LocalDate.of(2104, 2, 28)
        assertEquals(LocalDate.of(2096, 2, 29), span("days since 29 feb", before).from)
    }

    // ----------------------------------------------------------- the refusals

    @Test
    fun `numeric slash dates are refused in both readings`() {
        assertEquals(DateMath.Error.NUMERIC_DATE, error("days until 25/12/2026"))
        assertEquals(DateMath.Error.NUMERIC_DATE, error("days until 12/25/2026"))
    }

    @Test
    fun `a numeric dash date that is not iso is refused`() {
        assertEquals(DateMath.Error.NUMERIC_DATE, error("days until 12-25"))
        assertEquals(DateMath.Error.NUMERIC_DATE, error("days until 25-12-2026"))
    }

    @Test
    fun `a numeric dot date is refused`() {
        assertEquals(DateMath.Error.NUMERIC_DATE, error("days until 25.12.2026"))
    }

    @Test
    fun `weekday names, times and other units are not dates`() {
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until monday"))
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until 3pm"))
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until next week"))
    }

    @Test
    fun `a day that month does not have is not a date`() {
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until 31 feb"))
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until 2026-02-30"))
    }

    @Test
    fun `a year alongside a month name is not one of the forms`() {
        assertEquals(DateMath.Error.UNREADABLE_DATE, error("days until 25 dec 2026"))
    }

    @Test
    fun `each rejection says which thing was wrong`() {
        assertEquals(DateMath.Error.EMPTY, error("   "))
        assertEquals(DateMath.Error.UNKNOWN_VERB, error("days"))
        assertEquals(DateMath.Error.UNKNOWN_VERB, error("days after 25 dec"))
        assertEquals(DateMath.Error.MISSING_DATE, error("days until"))
        assertEquals(DateMath.Error.MISSING_RANGE, error("days between 25 dec"))
        assertEquals(DateMath.Error.MISSING_RANGE, error("days between 25 dec and"))
    }

    @Test
    fun `nothing in here keeps state between calls`() {
        val first = days("days until 25 dec")
        DateMath.parse("days since 1 jan", LocalDate.of(2001, 5, 5))
        assertEquals(first, days("days until 25 dec"))
    }
}
