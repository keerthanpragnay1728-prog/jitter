package dev.molasses.core.command

import dev.molasses.core.remind.ReminderBook.When
import dev.molasses.core.time.DurationParser
import dev.molasses.core.time.TimeParser
import dev.molasses.core.util.DateMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemParseTest {

    private fun ok(line: String) = (CommandParser.parse(line) as ParseResult.Ok).command as Command.Rem
    private fun err(line: String) = (CommandParser.parse(line) as ParseResult.Err).error

    @Test
    fun `a duration form`() {
        assertEquals(Command.Rem(When.In(45 * 60_000L), "tea"), ok("rem 45m tea"))
        assertEquals(Command.Rem(When.In(90 * 60_000L), "stretch"), ok("\$ rem 1h30m stretch"))
    }

    @Test
    fun `a time form, twelve and twenty four hour`() {
        assertEquals(Command.Rem(When.At(18 * 60), "call mum"), ok("rem 6pm call mum"))
        assertEquals(Command.Rem(When.At(18 * 60 + 30), "bins"), ok("rem 18:30 bins"))
    }

    @Test
    fun `the text is the rest of the line exactly as typed`() {
        assertEquals("buy  milk, then  eggs", ok("rem 10m buy  milk, then  eggs").text)
        assertEquals("6pm is not a time here", ok("rem 5m 6pm is not a time here").text)
    }

    @Test
    fun `a bare rem lists, and a missing text is still named`() {
        assertEquals(Command.RemList, (CommandParser.parse("rem") as ParseResult.Ok).command)
        assertEquals(Command.RemList, (CommandParser.parse("\$ rem") as ParseResult.Ok).command)
        assertEquals(Command.RemList, (CommandParser.parse("rem   ") as ParseResult.Ok).command)
        assertEquals(ParseError.MissingArgument("rem", "text"), err("rem 10m"))
        assertEquals(ParseError.MissingArgument("rem", "text"), err("rem 10m   "))
    }

    @Test
    fun `a token that is neither is refused as such, and each form keeps its own range refusal`() {
        assertEquals(ParseError.BadWhen("soon"), err("rem soon tea"))
        assertEquals(ParseError.BadTime("25:00", TimeParser.Kind.OUT_OF_RANGE), err("rem 25:00 tea"))
        assertEquals(ParseError.BadDuration("99d", DurationParser.Kind.TOO_LONG), err("rem 99d tea"))
    }

    @Test
    fun `space completion never rewrites the text`() {
        // A space after the verb prefix completes the verb, once. Every
        // space after that is left exactly where the user put it.
        assertEquals("rem ", CommandParser.completeOnSpace("rem", "rem "))
        var line = "rem 10m"
        for (ch in " block focus ?") {
            val next = line + ch
            assertEquals(next, CommandParser.completeOnSpace(line, next))
            line = next
        }
    }

    @Test
    fun `the canonical form reparses to the same command`() {
        for (line in listOf("rem 45m tea", "rem 6pm call mum", "rem 18:30 bins")) {
            val c = ok(line)
            assertEquals(c, ok(CommandRender.render(c)))
        }
    }

    @Test
    fun `the usage shape carries no value`() {
        val usage = CommandParser.USAGE.getValue("rem")
        assertTrue(usage.none { it.isDigit() })
    }

    @Test
    fun `a bare rem renders back to itself and is the rem verb`() {
        assertEquals("rem", CommandRender.render(Command.RemList))
        assertEquals("rem", CommandRegistry.verbOf(Command.RemList))
    }

    // ------------------------------------------------------------ dated

    private fun on(line: String) = ok(line).whenSpec as When.On

    @Test
    fun `an ISO date and a time`() {
        val r = ok("rem 2026-10-03 9am dentist")
        assertEquals(When.On(DateMath.DateSpec.Iso(java.time.LocalDate.of(2026, 10, 3)), 9 * 60), r.whenSpec)
        assertEquals("dentist", r.text)
    }

    @Test
    fun `a day and a month name, in either order, full or short`() {
        val expected = When.On(DateMath.DateSpec.DayMonth(10, 3), 18 * 60 + 30)
        assertEquals(expected, on("rem 3 oct 18:30 call mum"))
        assertEquals(expected, on("rem oct 3 18:30 call mum"))
        assertEquals(expected, on("rem 3 October 18:30 call mum"))
        assertEquals("call  mum", ok("rem 3 oct 6:30pm call  mum").text)
    }

    @Test
    fun `today and tomorrow, as days reads them`() {
        assertEquals(When.On(DateMath.DateSpec.Offset(1), 9 * 60), on("rem tomorrow 9am bins"))
        assertEquals(When.On(DateMath.DateSpec.Offset(0), 21 * 60), on("rem today 9pm lights"))
    }

    @Test
    fun `slash dates are refused as ambiguous, as in days`() {
        assertEquals(ParseError.RemDate("12/10", DateMath.Error.NUMERIC_DATE), err("rem 12/10 9am x"))
        assertEquals(ParseError.RemDate("3/10/2026", DateMath.Error.NUMERIC_DATE), err("rem 3/10/2026 9am x"))
    }

    @Test
    fun `a month name with a year is told to use ISO, as in days`() {
        assertEquals(ParseError.RemDate("3 oct 2026", DateMath.Error.YEAR_NEEDS_ISO), err("rem 3 oct 2026 9am x"))
        assertEquals(ParseError.RemDate("oct 2026", DateMath.Error.YEAR_NEEDS_ISO), err("rem oct 2026 9am x"))
    }

    @Test
    fun `a date without a time is refused, saying a time is needed`() {
        assertEquals(ParseError.RemNeedsTime("3 oct"), err("rem 3 oct"))
        assertEquals(ParseError.RemNeedsTime("3 oct"), err("rem 3 oct call mum"))
        assertEquals(ParseError.RemNeedsTime("tomorrow"), err("rem tomorrow"))
        assertEquals(ParseError.RemNeedsTime("2026-10-03"), err("rem 2026-10-03 dentist"))
    }

    @Test
    fun `a date and a time with no text still asks for the text`() {
        assertEquals(ParseError.MissingArgument("rem", "text"), err("rem 3 oct 9am"))
    }

    @Test
    fun `a bad time after a date keeps the time's own refusal`() {
        assertEquals(ParseError.BadTime("25:00", TimeParser.Kind.OUT_OF_RANGE), err("rem 3 oct 25:00 x"))
    }

    @Test
    fun `an impossible ISO date and a word that is no date are refused`() {
        assertEquals(ParseError.BadWhen("2026-02-30"), err("rem 2026-02-30 9am x"))
        assertEquals(ParseError.BadWhen("soon"), err("rem soon 9am tea"))
    }

    @Test
    fun `dated reminders render back to a line that reparses to them`() {
        for (line in listOf("rem 2026-10-03 9am dentist", "rem 3 oct 18:30 call mum", "rem tomorrow 9am bins")) {
            val cmd = ok(line)
            assertEquals(line, cmd, ok(CommandRender.render(cmd)))
        }
    }
}
