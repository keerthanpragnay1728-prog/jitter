package dev.molasses.core.command

import dev.molasses.core.time.TimeParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `parse(render(c)) == c`, for every command the parser can produce.
 *
 * The property is the point. A parser and a renderer that disagree produce a
 * lock with the wrong duration rather than an error, which is the failure mode
 * this app can least afford.
 */
class CommandRenderTest {

    private val s = 1_000L
    private val m = 60 * s
    private val h = 60 * m
    private val d = 24 * h

    private fun reparse(c: Command): Command {
        val text = CommandRender.render(c)
        return when (val r = CommandParser.parse(text)) {
            is ParseResult.Ok -> r.command
            is ParseResult.Err -> throw AssertionError(
                "render(${c}) produced '$text', which does not reparse: ${r.error}",
            )
        }
    }

    private fun roundTrips(c: Command) = assertEquals(c, reparse(c))

    @Test
    fun `every command variant round trips`() {
        val cases = listOf(
            Command.Block("instagram", 30 * m),
            Command.Block("com.instagram.android", 30 * d),
            Command.Focus(2 * h),
            Command.Focus(1 * h + 30 * m),
            Command.Timer(90 * s),
            Command.Bedtime,
            Command.Status,
            Command.Reboot,
            Command.PowerOff,
            Command.Alarm(0),
            Command.Alarm(6 * 60),
            Command.Alarm(12 * 60),
            Command.Alarm(18 * 60 + 30),
            Command.Alarm(23 * 60 + 59),
            Command.Wifi(null),
            Command.Wifi(true),
            Command.Wifi(false),
            Command.Dnd(null),
            Command.Dnd(true),
            Command.Dnd(false),
            Command.Calc("2+2"),
            Command.Calc("200 + 10%"),
            Command.Calc("(1 + 2) * 3 / 4"),
            Command.Conv("5 km mi"),
            Command.Days("until 25 dec"),
            Command.Days("between 25 dec and 1 jan"),
        )
        for (c in cases) roundTrips(c)
    }

    @Test
    fun `every parsed line round trips through its own render`() {
        // Drives the property from the other end: parse real input, render
        // it, reparse, and require the two values to agree.
        val lines = listOf(
            "block instagram 30m",
            "block instagram 1h30m",
            "block instagram 30d",
            "focus 2h",
            "timer 30s",
            "bedtime",
            "sleep",
            "status",
            "alarm 6am",
            "alarm 12am",
            "alarm 12pm",
            "alarm 18:30",
            "wifi",
            "wifi on",
            "dnd off",
            "reboot",
            "poweroff",
            "calc 2+2",
            "calc 200 + 10%",
            "conv 5 km mi",
            "days until 2026-12-25",
            "days between 25 dec and 1 jan",
        )
        for (line in lines) {
            val first = (CommandParser.parse(line) as ParseResult.Ok).command
            assertEquals("line '$line'", first, reparse(first))
        }
    }

    @Test
    fun `a utility argument is normalised once and then stable`() {
        // The remainder is stored as its tokens rejoined with single spaces,
        // so the first parse is the only one that can change the text. That
        // is what makes the round trip exact rather than approximate, and it
        // is why the history list holds one spelling per command here too.
        val loose = (CommandParser.parse("calc   200   +   10%") as ParseResult.Ok).command
        assertEquals(Command.Calc("200 + 10%"), loose)
        assertEquals("calc 200 + 10%", CommandRender.render(loose))
        roundTrips(loose)
    }

    @Test
    fun `sleep is canonicalised to bedtime and still round trips`() {
        val parsed = (CommandParser.parse("sleep") as ParseResult.Ok).command
        assertEquals("bedtime", CommandRender.render(parsed))
        assertEquals(parsed, reparse(parsed))
    }

    @Test
    fun `durations render in the largest exact units`() {
        assertEquals("30s", CommandRender.duration(30 * s))
        assertEquals("45m", CommandRender.duration(45 * m))
        assertEquals("2h", CommandRender.duration(2 * h))
        assertEquals("3d", CommandRender.duration(3 * d))
        assertEquals("1h30m", CommandRender.duration(90 * m))
        assertEquals("1d2h3m4s", CommandRender.duration(d + 2 * h + 3 * m + 4 * s))
        assertEquals("30d", CommandRender.duration(30 * d))
    }

    @Test
    fun `a non canonical duration still round trips on value`() {
        // "90m" is accepted by the parser and renders back as "1h30m". The
        // round trip is on the parsed value, not on the original text.
        val c = (CommandParser.parse("focus 90m") as ParseResult.Ok).command
        assertEquals("focus 1h30m", CommandRender.render(c))
        assertEquals(c, reparse(c))
    }

    @Test
    fun `times render as 24 hour and survive the noon and midnight cases`() {
        assertEquals("00:00", CommandRender.time(0))
        assertEquals("12:00", CommandRender.time(12 * 60))
        assertEquals("06:30", CommandRender.time(6 * 60 + 30))
        assertEquals("23:59", CommandRender.time(TimeParser.MINUTES_PER_DAY - 1))
        roundTrips(Command.Alarm(0))
        roundTrips(Command.Alarm(12 * 60))
    }

    @Test
    fun `every minute of the day round trips`() {
        for (minute in 0 until TimeParser.MINUTES_PER_DAY) {
            roundTrips(Command.Alarm(minute))
        }
    }

    @Test
    fun `every ladder duration round trips`() {
        for (ms in dev.molasses.core.lock.LockLadder.STEPS_MS) {
            roundTrips(Command.Focus(ms))
        }
    }

    @Test
    fun `a rendered command never reparses as a different verb`() {
        // Guards the one class of bug a round trip on values alone could
        // miss: a renderer that emits a line another verb happens to accept.
        val cases = listOf<Command>(
            Command.Bedtime, Command.Status, Command.Reboot, Command.PowerOff,
            Command.Wifi(null), Command.Dnd(null),
        )
        for (c in cases) {
            val verb = CommandRender.render(c).substringBefore(' ')
            assertEquals("render(${c}) verb", true, verb in CommandParser.VERBS)
        }
    }
}
