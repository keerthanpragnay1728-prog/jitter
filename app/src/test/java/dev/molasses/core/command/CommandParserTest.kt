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
    fun `log, rem and allow are not verbs any more`() {
        // Both parsed and then reported "not wired yet" from the subsystem
        // surface, permanently. A terminal that accepts a command it can
        // never run teaches the user to distrust the ones it can. They now
        // fail at the parser like any other word, which is what they are.
        for (word in listOf("log", "rem", "allow", "log instagram", "allow yt 10m")) {
            val parsed = CommandParser.parse(word)
            assertTrue(
                "'$word' still parses: $parsed",
                parsed is ParseResult.Err &&
                    parsed.error is ParseError.UnknownCommand,
            )
        }
        for (verb in listOf("log", "rem", "allow")) {
            assertTrue(verb, verb !in CommandParser.VERBS)
            assertTrue(verb, verb !in CommandParser.USAGE)
        }
    }

    @Test
    fun `alarm takes a time`() {
        assertEquals(Command.Alarm(6 * 60), ok("alarm 6am"))
        assertEquals(Command.Alarm(18 * 60 + 30), ok("alarm 18:30"))
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
        assertEquals(ParseError.MissingArgument("focus", "duration"), err("focus"))
        assertEquals(ParseError.MissingArgument("timer", "duration"), err("timer"))
        assertEquals(ParseError.MissingArgument("alarm", "time"), err("alarm"))
    }

    @Test
    fun `surplus arguments are rejected rather than ignored`() {
        assertEquals(ParseError.TooManyArguments("block"), err("block instagram 30m 2h"))
        assertEquals(ParseError.TooManyArguments("focus"), err("focus 2h 3h"))
        assertEquals(ParseError.TooManyArguments("bedtime"), err("bedtime now"))
        assertEquals(ParseError.TooManyArguments("status"), err("status all"))
        assertEquals(ParseError.TooManyArguments("reboot"), err("reboot now"))
        assertEquals(ParseError.TooManyArguments("poweroff"), err("poweroff now"))
        assertEquals(ParseError.TooManyArguments("block"), err("block ig 10m 20m"))
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
            err("focus -5m"),
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
    fun `no verb has a free-text tail any more`() {
        // "rem" was the only one, and it is gone. The test that pinned its
        // re-ghosting behaviour went with it rather than being kept against
        // a verb that does not have the property: a test whose subject has
        // been deleted is a test that asserts nothing while looking like
        // coverage.
        //
        // This is what replaces it. Every usage shape is a fixed arity, so
        // argumentGhost can keep counting tokens; the moment one of them
        // grows a tail, this fails and the re-ghosting rules need writing
        // again.
        for ((verb, usage) in CommandParser.USAGE) {
            val shapes = usage.split(' ').drop(1)
            assertEquals(
                "$verb has a repeated or open-ended argument: $usage",
                shapes.size,
                shapes.distinct().size,
            )
        }
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
            "focus", "focus ", "block", "block ig", "block ig ",
            "wifi", "wifi o", "alarm", "alarm ",
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
    // ------------------------------------------------- the three utilities

    @Test
    fun `a utility takes the whole remainder of the line`() {
        assertEquals(Command.Calc("2 + 3 * 4"), ok("calc 2 + 3 * 4"))
        assertEquals(Command.Conv("5 km mi"), ok("conv 5 km mi"))
        assertEquals(Command.Days("between 25 dec and 1 jan"), ok("days between 25 dec and 1 jan"))
    }

    @Test
    fun `a utility does not count its arguments`() {
        // The one shape that does not check arity, because the argument is a
        // language rather than a list. Four tokens after "conv" is a mistake
        // the converter names precisely; refusing it here would name it
        // vaguely and first.
        assertEquals(Command.Conv("5 km mi please"), ok("conv 5 km mi please"))
    }

    @Test
    fun `a utility still refuses an empty remainder`() {
        assertEquals(ParseError.MissingArgument("calc", "expression"), err("calc"))
        assertEquals(ParseError.MissingArgument("conv", "amount"), err("conv"))
        assertEquals(ParseError.MissingArgument("days", "date"), err("days"))
    }

    @Test
    fun `the remainder keeps its case and loses its extra spaces`() {
        // The verb is lowercased because it is the grammar; the argument is
        // not, because DEC is a month and an expression could one day carry
        // something case bearing.
        assertEquals(Command.Days("until 25 DEC"), ok("DAYS until 25   DEC"))
    }

    @Test
    fun `bare arithmetic is not a command`() {
        // The whole of the disambiguation. Typing 2+3 filters apps, exactly
        // as it did before the calculator existed, and a utility runs only
        // when its verb is typed.
        assertEquals(ParseError.UnknownCommand("2+3"), err("2+3"))
        assertEquals(ParseError.UnknownCommand("5"), err("5"))
        assertEquals(ParseError.UnknownCommand("25/12/2026"), err("25/12/2026"))
    }

}
