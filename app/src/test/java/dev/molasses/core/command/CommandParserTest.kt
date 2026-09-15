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

    // ------------------------------------------------------------- the ghost

    @Test
    fun `the ghost is the remainder of a unique verb`() {
        assertEquals("ock", CommandParser.ghostFor("bl"))
        assertEquals("elp", CommandParser.ghostFor("h"))
    }

    @Test
    fun `an empty prompt ghosts nothing, the placeholder has that space`() {
        assertNull(CommandParser.ghostFor(""))
        assertNull(CommandParser.ghostFor("   "))
    }

    @Test
    fun `an ambiguous prefix ghosts nothing`() {
        assertNull(CommandParser.ghostFor("b"))
        assertNull(CommandParser.ghostFor("s"))
    }

    @Test
    fun `a complete verb ghosts nothing`() {
        assertNull(CommandParser.ghostFor("block"))
        assertNull(CommandParser.ghostFor("help"))
    }

    @Test
    fun `the ghost never offers a concrete value`() {
        // The one rule: no completion can put a value the user did not choose
        // one keystroke from being armed. Shapes, never values, at every
        // position and for every verb.
        val lines = CommandParser.VERBS.flatMap {
            listOf(it, "$it ", "$it x", "$it x ", "$it x y", it.dropLast(1))
        }
        for (line in lines) {
            val ghost = CommandParser.ghostFor(line) ?: continue
            assertTrue("'$line' ghosted a digit: $ghost", ghost.none { c -> c.isDigit() })
        }
    }

    // ------------------------------------------------- the argument position

    @Test
    fun `a complete verb ghosts the arguments it owes`() {
        assertEquals("<app> <duration>", CommandParser.ghostFor("block "))
        assertEquals("<duration>", CommandParser.ghostFor("focus "))
        assertEquals("[app]", CommandParser.ghostFor("log "))
        assertEquals("[on|off]", CommandParser.ghostFor("wifi "))
    }

    @Test
    fun `a partial app name is never rewritten`() {
        // The defect this prevents: "insta" expanding to "instagram" would
        // replace characters the user typed, and the ghost is an overlay with
        // the typed prefix drawn transparent. Anything that does not purely
        // extend the line renders as garbage.
        val ghost = CommandParser.ghostFor("block insta")
        assertEquals(" <duration>", ghost)
        // The proof of the property rather than of the string: the typed text
        // plus the ghost still starts with exactly what was typed.
        assertTrue(("block insta" + ghost).startsWith("block insta"))
    }

    @Test
    fun `a started argument is spoken for, a trailing space is not`() {
        assertEquals(" <duration>", CommandParser.ghostFor("block insta"))
        assertEquals("<duration>", CommandParser.ghostFor("block insta "))
    }

    @Test
    fun `a fully typed command ghosts nothing`() {
        assertNull(CommandParser.ghostFor("block insta 30m"))
        assertNull(CommandParser.ghostFor("block insta 30m "))
        assertNull(CommandParser.ghostFor("focus 30m"))
    }

    @Test
    fun `a multi-word free-text argument does not re-ghost`() {
        // rem is the one verb with a free-text tail, so every token past the
        // second is part of the reminder and nothing further is owed.
        // A complete verb with no space yet ghosts nothing: the hint line
        // below the prompt already shows the whole shape at that point, and
        // two renderings of the same thing on one screen is one too many.
        assertNull(CommandParser.ghostFor("rem"))
        assertEquals("<duration> <text>", CommandParser.ghostFor("rem "))
        assertEquals(" <text>", CommandParser.ghostFor("rem 10m"))
        assertEquals("<text>", CommandParser.ghostFor("rem 10m "))
        assertNull(CommandParser.ghostFor("rem 10m call"))
        assertNull(CommandParser.ghostFor("rem 10m call mum"))
        assertNull(CommandParser.ghostFor("rem 10m call mum about the thing"))
    }

    @Test
    fun `an incomplete verb followed by a space ghosts nothing`() {
        // Offering arguments for a command the user has not finished naming
        // would be guessing at which one they meant.
        assertNull(CommandParser.ghostFor("blo insta"))
        assertNull(CommandParser.ghostFor("b "))
    }

    @Test
    fun `a verb with no arguments ghosts nothing after a space`() {
        assertNull(CommandParser.ghostFor("status "))
        assertNull(CommandParser.ghostFor("bedtime "))
        assertNull(CommandParser.ghostFor("help "))
    }

    @Test
    fun `the argument ghost always extends the typed line`() {
        // The rendering contract, swept. The overlay draws the typed prefix
        // transparent and the ghost after it, so a ghost that did anything
        // but append would render as garbage.
        val lines = listOf(
            "block", "block ", "block i", "block insta", "block insta ",
            "rem", "rem 1", "rem 10m", "rem 10m c", "focus", "focus ",
            "log", "log ", "wifi", "wifi o", "alarm", "alarm ",
        )
        for (line in lines) {
            val ghost = CommandParser.ghostFor(line) ?: continue
            assertTrue("'$line' + '$ghost' must extend", (line + ghost).startsWith(line))
        }
    }

    @Test
    fun `argument shapes come from the usage strings and nowhere else`() {
        // A second list of hints would be a second thing to keep in step with
        // the grammar. Every shape a ghost can emit is a token of USAGE.
        val shapes = CommandParser.USAGE.values.flatMap { it.split(" ") }.toSet()
        for (verb in CommandParser.VERBS) {
            val ghost = CommandParser.ghostFor("$verb ") ?: continue
            for (token in ghost.trim().split(" ")) {
                assertTrue("'$token' is not a USAGE token", token in shapes)
            }
        }
    }

    @Test
    fun `mixed case ghosts nothing, because it would render wrong`() {
        // The ghost is drawn by overlaying the typed characters exactly, so
        // "BL" plus "ock" would read as BLock.
        assertNull(CommandParser.ghostFor("BL"))
        assertNull(CommandParser.ghostFor("Bl"))
    }

    @Test
    fun `the prompt dollar is not part of the prefix`() {
        assertEquals("ock", CommandParser.ghostFor("\$bl"))
        assertEquals("ock", CommandParser.ghostFor("\$ bl"))
    }

    @Test
    fun `typing the ghost out matches what the parser accepts`() {
        // If these two disagree the ghost is a lie: it completes to something
        // that then fails to parse as a verb.
        for (verb in CommandParser.VERBS) {
            val prefix = verb.take(1)
            val ghost = CommandParser.ghostFor(prefix) ?: continue
            assertEquals(verb, prefix + ghost)
            assertTrue(verb, CommandParser.VERBS.contains(prefix + ghost))
        }
    }

    // ------------------------------------------------------------------ help

    @Test
    fun `help and its question mark alias parse to the same command`() {
        assertEquals(ParseResult.Ok(Command.Help), CommandParser.parse("help"))
        assertEquals(ParseResult.Ok(Command.Help), CommandParser.parse("?"))
        assertEquals(ParseResult.Ok(Command.Help), CommandParser.parse("\$ ?"))
    }

    @Test
    fun `help takes no arguments`() {
        val r = CommandParser.parse("help block")
        assertTrue(r.toString(), (r as ParseResult.Err).error is ParseError.TooManyArguments)
    }

    // ------------------------------------------------- space completion

    @Test
    fun `a space after a unique prefix completes the verb`() {
        assertEquals("block ", CommandParser.completeOnSpace("bl", "bl "))
        assertEquals("help ", CommandParser.completeOnSpace("h", "h "))
    }

    @Test
    fun `a space after an ambiguous prefix is just a space`() {
        assertEquals("b ", CommandParser.completeOnSpace("b", "b "))
        assertEquals("s ", CommandParser.completeOnSpace("s", "s "))
    }

    @Test
    fun `a space inside an argument never rewrites the line`() {
        // The failure this prevents: typing a reminder and having the second
        // word silently replaced by a verb.
        assertEquals("rem 10m call ", CommandParser.completeOnSpace("rem 10m call", "rem 10m call "))
        assertEquals("block ig ", CommandParser.completeOnSpace("block ig", "block ig "))
    }

    @Test
    fun `a space at position four or later never completes`() {
        // The free-text tail is where this bites: every word of a reminder is
        // followed by a space, and any one of them silently becoming a verb
        // would rewrite the message the user is composing.
        val line = "rem 10m call mum about the thing"
        var built = ""
        for (word in line.split(" ")) {
            val before = if (built.isEmpty()) word else "$built $word"
            val after = "$before "
            assertEquals("completing after '$before'", after, CommandParser.completeOnSpace(before, after))
            built = before
        }
    }

    @Test
    fun `an ordinary keystroke passes through untouched`() {
        assertEquals("blo", CommandParser.completeOnSpace("bl", "blo"))
        assertEquals("b", CommandParser.completeOnSpace("bl", "b"))
        assertEquals("", CommandParser.completeOnSpace("bl", ""))
    }

    @Test
    fun `completing an already complete verb only adds the space`() {
        assertEquals("block ", CommandParser.completeOnSpace("block", "block "))
    }

    @Test
    fun `the prompt dollar survives completion`() {
        assertEquals("\$ block ", CommandParser.completeOnSpace("\$ bl", "\$ bl "))
    }

    @Test
    fun `what the space completes to always parses as a verb`() {
        for (verb in CommandParser.VERBS) {
            val prefix = verb.take(1)
            if (CommandParser.completionFor(prefix) == null) continue
            val completed = CommandParser.completeOnSpace(prefix, "$prefix ")
            assertTrue(completed, CommandParser.VERBS.contains(completed.trim()))
        }
    }
}
