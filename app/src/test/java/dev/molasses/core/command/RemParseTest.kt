package dev.molasses.core.command

import dev.molasses.core.remind.ReminderBook.When
import dev.molasses.core.time.DurationParser
import dev.molasses.core.time.TimeParser
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
    fun `missing pieces are named`() {
        assertEquals(ParseError.MissingArgument("rem", "time"), err("rem"))
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
}
