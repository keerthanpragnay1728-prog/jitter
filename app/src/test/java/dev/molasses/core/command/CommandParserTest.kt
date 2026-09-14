package dev.molasses.core.command

import dev.molasses.core.time.DurationParser
import dev.molasses.core.time.TimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val m = 60_000L
    private val h = 60 * m
    private val d = 24 * h

    private fun ok(line: String): Command {
        val r = CommandParser.parse(line)
        return (r as? ParseResult.Ok)?.command
            ?: throw AssertionError("expected Ok for '$line', got $r")
    }

    private fun err(line: String): ParseError {
        val r = CommandParser.parse(line)
        return (r as? ParseResult.Err)?.error
            ?: throw AssertionError("expected Err for '$line', got $r")
    }

    // --------------------------------------------------------------- valid forms

    @Test
    fun `block takes an app and a duration`() {
        assertEquals(Command.Block("instagram", 30 * m), ok("block instagram 30m"))
        assertEquals(Command.Block("instagram", 30 * m), ok("$ block instagram 30m"))
        assertEquals(Command.Block("instagram", 1 * h + 30 * m), ok("BLOCK instagram 1h30m"))
    }

    @Test
    fun `allow takes an app and a duration`() {
        assertEquals(Command.Allow("youtube", 10 * m), ok("allow youtube 10m"))
    }

    @Test
    fun `focus and timer take a duration`() {
        assertEquals(Command.Focus(2 * h), ok("focus 2h"))
        assertEquals(Command.Timer(90 * 1000L), ok("timer 90s"))
    }

    @Test
    fun `the no-argument verbs`() {
        assertEquals(Command.Bedtime, ok("bedtime"))
        assertEquals(Command.Bedtime, ok("sleep"))
        assertEquals(Command.Status, ok("status"))
        assertEquals(Command.Reboot, ok("reboot"))
        assertEquals(Command.PowerOff, ok("poweroff"))
    }

    @Test
    fun `log takes an optional app`() {
        assertEquals(Command.Log(null), ok("log"))
        assertEquals(Command.Log("instagram"), ok("log instagram"))
    }

    @Test
    fun `alarm takes a time`() {
        assertEquals(Command.Alarm(6 * 60), ok("alarm 6am"))
        assertEquals(Command.Alarm(18 * 60 + 30), ok("alarm 18:30"))
    }

    @Test
    fun `rem takes a duration and a free text tail`() {
        assertEquals(Command.Remind(10 * m, "call mum"), ok("rem 10m call mum"))
        assertEquals(Command.Remind(1 * h, "take the bins out"), ok("rem 1h take the bins out"))
    }

    @Test
    fun `wifi and dnd toggle or set`() {
        assertEquals(Command.Wifi(null), ok("wifi"))
        assertEquals(Command.Wifi(true), ok("wifi on"))
        assertEquals(Command.Wifi(false), ok("wifi OFF"))
        assertEquals(Command.Dnd(null), ok("dnd"))
        assertEquals(Command.Dnd(true), ok("dnd on"))
    }

    // ------------------------------------------------------------ malformed forms

    @Test
    fun `empty input`() {
        assertEquals(ParseError.Empty, err(""))
        assertEquals(ParseError.Empty, err("   "))
        assertEquals(ParseError.Empty, err("$"))
        assertEquals(ParseError.Empty, err("$   "))
    }

    @Test
    fun `unknown commands name the verb back`() {
        assertEquals(ParseError.UnknownCommand("frobnicate"), err("frobnicate now"))
        assertEquals(ParseError.UnknownCommand("blocks"), err("blocks instagram 5m"))
    }

    @Test
    fun `missing arguments never default`() {
        // This is the one that matters: a missing duration must not become a
        // default duration, or a half-typed line arms a real lock.
        assertEquals(ParseError.MissingArgument("block", "app"), err("block"))
        assertEquals(ParseError.MissingArgument("block", "duration"), err("block instagram"))
        assertEquals(ParseError.MissingArgument("allow", "app"), err("allow"))
        assertEquals(ParseError.MissingArgument("allow", "duration"), err("allow youtube"))
        assertEquals(ParseError.MissingArgument("focus", "duration"), err("focus"))
        assertEquals(ParseError.MissingArgument("timer", "duration"), err("timer"))
        assertEquals(ParseError.MissingArgument("alarm", "time"), err("alarm"))
        assertEquals(ParseError.MissingArgument("rem", "duration"), err("rem"))
        assertEquals(ParseError.MissingArgument("rem", "text"), err("rem 10m"))
    }

    @Test
    fun `surplus arguments are rejected rather than ignored`() {
        assertEquals(ParseError.TooManyArguments("block"), err("block instagram 30m 2h"))
        assertEquals(ParseError.TooManyArguments("focus"), err("focus 2h 3h"))
        assertEquals(ParseError.TooManyArguments("bedtime"), err("bedtime now"))
        assertEquals(ParseError.TooManyArguments("status"), err("status all"))
        assertEquals(ParseError.TooManyArguments("reboot"), err("reboot now"))
        assertEquals(ParseError.TooManyArguments("poweroff"), err("poweroff now"))
        assertEquals(ParseError.TooManyArguments("log"), err("log instagram youtube"))
        assertEquals(ParseError.TooManyArguments("alarm"), err("alarm 6am 7am"))
        assertEquals(ParseError.TooManyArguments("wifi"), err("wifi on off"))
        assertEquals(ParseError.TooManyArguments("dnd"), err("dnd on off"))
    }

    @Test
    fun `bad durations are reported with the offending token`() {
        assertEquals(
            ParseError.BadDuration("banana", DurationParser.Kind.MALFORMED),
            err("block instagram banana"),
        )
        assertEquals(
            ParseError.BadDuration("0m", DurationParser.Kind.ZERO),
            err("focus 0m"),
        )
        assertEquals(
            ParseError.BadDuration("90d", DurationParser.Kind.TOO_LONG),
            err("block instagram 90d"),
        )
        assertEquals(
            ParseError.BadDuration("-5m", DurationParser.Kind.MALFORMED),
            err("rem -5m call mum"),
        )
    }

    @Test
    fun `bad times are reported with the offending token`() {
        assertEquals(
            ParseError.BadTime("25:00", TimeParser.Kind.OUT_OF_RANGE),
            err("alarm 25:00"),
        )
        assertEquals(
            ParseError.BadTime("garbage", TimeParser.Kind.MALFORMED),
            err("alarm garbage"),
        )
    }

    @Test
    fun `a bad toggle is not silently treated as a toggle`() {
        assertEquals(ParseError.BadToggle("maybe"), err("wifi maybe"))
        assertEquals(ParseError.BadToggle("1"), err("dnd 1"))
    }

    @Test
    fun `the thirty day cap is enforced through the parser`() {
        assertEquals(Command.Block("instagram", 30 * d), ok("block instagram 30d"))
        assertTrue(err("block instagram 31d") is ParseError.BadDuration)
    }

    // ------------------------------------------------------------- autocomplete

    @Test
    fun `a hint shows the argument shape and never a value`() {
        // "b" is ambiguous (block, bedtime), so the shortest unique prefix
        // is "bl". An ambiguous prefix offering nothing is the point of the
        // next test.
        assertEquals("block <app> <duration>", CommandParser.hintFor("bl"))
        assertEquals("focus <duration>", CommandParser.hintFor("fo"))
        for (verb in CommandParser.VERBS) {
            val usage = CommandParser.USAGE.getValue(verb)
            assertTrue("$verb usage starts with the verb", usage.startsWith(verb))
            // No concrete argument may appear in a hint: one stray Tab must
            // not be able to arm a real lock.
            assertTrue("$verb usage has no digits", usage.none { it.isDigit() })
        }
    }

    @Test
    fun `an ambiguous or complete prefix offers nothing`() {
        // "s" matches status and sleep; "b" matches block and bedtime.
        assertNull(CommandParser.hintFor("s"))
        assertNull(CommandParser.hintFor("b"))
        assertNull(CommandParser.hintFor(""))
        assertNull(CommandParser.hintFor("block instagram"))
        assertNull(CommandParser.hintFor("zzz"))
    }

    @Test
    fun `completion fills the verb only`() {
        assertEquals("block", CommandParser.completionFor("bl"))
        assertEquals("poweroff", CommandParser.completionFor("p"))
        assertNull(CommandParser.completionFor("s"))
        assertNull(CommandParser.completionFor("block ins"))
    }

    @Test
    fun `every verb has a usage entry and parses its own usage verb`() {
        for (verb in CommandParser.VERBS) {
            assertTrue(verb, CommandParser.USAGE.containsKey(verb))
            // The bare verb either parses or fails for a named reason, never
            // as an unknown command.
            val r = CommandParser.parse(verb)
            if (r is ParseResult.Err) {
                assertTrue(verb, r.error !is ParseError.UnknownCommand)
            }
        }
    }
}
