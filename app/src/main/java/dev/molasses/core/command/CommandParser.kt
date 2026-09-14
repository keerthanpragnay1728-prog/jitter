package dev.molasses.core.command

import dev.molasses.core.time.DurationParser
import dev.molasses.core.time.TimeParser

/**
 * The REPL grammar, as a pure function from a line to a [ParseResult].
 *
 * ## Arity is checked, always
 * Every verb rejects surplus arguments rather than ignoring them. A line like
 * `block instagram 30m 2h` is a user who is unsure what the syntax is, and
 * silently arming a 30 minute lock teaches them the wrong thing at the cost of
 * a lock they cannot undo. Failing is cheap; a wrong lock is not.
 *
 * ## No argument is ever guessed
 * A missing duration is [ParseError.MissingArgument], never a default. The
 * autocomplete hint shows the *shape* of a command (`block <app> <duration>`)
 * for the same reason: one stray Tab must not be able to arm anything.
 *
 * Pure; no Android imports. Unit-tested in `CommandParserTest`.
 */
object CommandParser {

    /** Every verb, including aliases. For autocomplete and for the help line. */
    val VERBS: List<String> = listOf(
        "block", "focus", "allow", "bedtime", "sleep", "status", "log",
        "alarm", "timer", "rem", "reboot", "poweroff", "wifi", "dnd",
    )

    /**
     * Argument shape per verb, shown as an inline hint. Never filled in with
     * concrete values: see the class doc.
     */
    val USAGE: Map<String, String> = mapOf(
        "block" to "block <app> <duration>",
        "focus" to "focus <duration>",
        "allow" to "allow <app> <duration>",
        "bedtime" to "bedtime",
        "sleep" to "sleep",
        "status" to "status",
        "log" to "log [app]",
        "alarm" to "alarm <time>",
        "timer" to "timer <duration>",
        "rem" to "rem <duration> <text>",
        "reboot" to "reboot",
        "poweroff" to "poweroff",
        "wifi" to "wifi [on|off]",
        "dnd" to "dnd [on|off]",
    )

    fun parse(line: String): ParseResult {
        // A leading "$" is part of the prompt, not the command, but users
        // retype what they see. Accept and drop it.
        val text = line.trim().removePrefix("$").trim()
        if (text.isEmpty()) return err(ParseError.Empty)

        val tokens = text.split(Regex("\\s+"))
        val verb = tokens[0].lowercase()
        val args = tokens.drop(1)

        return when (verb) {
            "block" -> appAndDuration(verb, args) { app, ms -> Command.Block(app, ms) }
            "allow" -> appAndDuration(verb, args) { app, ms -> Command.Allow(app, ms) }

            "focus" -> durationOnly(verb, args) { Command.Focus(it) }
            "timer" -> durationOnly(verb, args) { Command.Timer(it) }

            "bedtime", "sleep" -> noArgs(verb, args, Command.Bedtime)
            "status" -> noArgs(verb, args, Command.Status)
            "reboot" -> noArgs(verb, args, Command.Reboot)
            "poweroff" -> noArgs(verb, args, Command.PowerOff)

            "log" -> when (args.size) {
                0 -> ok(Command.Log(null))
                1 -> ok(Command.Log(args[0]))
                else -> err(ParseError.TooManyArguments(verb))
            }

            "alarm" -> when (args.size) {
                0 -> err(ParseError.MissingArgument(verb, "time"))
                1 -> when (val t = TimeParser.parse(args[0])) {
                    is TimeParser.Result.Ok -> ok(Command.Alarm(t.minuteOfDay))
                    is TimeParser.Result.Err -> err(ParseError.BadTime(args[0], t.kind))
                }
                else -> err(ParseError.TooManyArguments(verb))
            }

            // The only verb with a free-text tail, so it is the only one that
            // does not reject surplus tokens: everything after the duration is
            // the reminder itself.
            "rem" -> when {
                args.isEmpty() -> err(ParseError.MissingArgument(verb, "duration"))
                args.size == 1 -> err(ParseError.MissingArgument(verb, "text"))
                else -> when (val d = DurationParser.parse(args[0])) {
                    is DurationParser.Result.Ok ->
                        ok(Command.Remind(d.ms, args.drop(1).joinToString(" ")))
                    is DurationParser.Result.Err -> err(ParseError.BadDuration(args[0], d.kind))
                }
            }

            "wifi" -> toggle(verb, args) { Command.Wifi(it) }
            "dnd" -> toggle(verb, args) { Command.Dnd(it) }

            else -> err(ParseError.UnknownCommand(verb))
        }
    }

    // ------------------------------------------------------------- shapes

    private inline fun appAndDuration(
        verb: String,
        args: List<String>,
        build: (String, Long) -> Command,
    ): ParseResult = when {
        args.isEmpty() -> err(ParseError.MissingArgument(verb, "app"))
        args.size == 1 -> err(ParseError.MissingArgument(verb, "duration"))
        args.size > 2 -> err(ParseError.TooManyArguments(verb))
        else -> when (val d = DurationParser.parse(args[1])) {
            is DurationParser.Result.Ok -> ok(build(args[0], d.ms))
            is DurationParser.Result.Err -> err(ParseError.BadDuration(args[1], d.kind))
        }
    }

    private inline fun durationOnly(
        verb: String,
        args: List<String>,
        build: (Long) -> Command,
    ): ParseResult = when {
        args.isEmpty() -> err(ParseError.MissingArgument(verb, "duration"))
        args.size > 1 -> err(ParseError.TooManyArguments(verb))
        else -> when (val d = DurationParser.parse(args[0])) {
            is DurationParser.Result.Ok -> ok(build(d.ms))
            is DurationParser.Result.Err -> err(ParseError.BadDuration(args[0], d.kind))
        }
    }

    private fun noArgs(verb: String, args: List<String>, command: Command): ParseResult =
        if (args.isEmpty()) ok(command) else err(ParseError.TooManyArguments(verb))

    private inline fun toggle(
        verb: String,
        args: List<String>,
        build: (Boolean?) -> Command,
    ): ParseResult = when {
        args.isEmpty() -> ok(build(null))
        args.size > 1 -> err(ParseError.TooManyArguments(verb))
        else -> when (args[0].lowercase()) {
            "on" -> ok(build(true))
            "off" -> ok(build(false))
            else -> err(ParseError.BadToggle(args[0]))
        }
    }

    /**
     * Inline autocomplete for a partly typed line.
     *
     * Returns the usage shape of the single matching verb, or null when the
     * prefix is ambiguous or already complete. Never returns a filled-in
     * argument: completing `b` to `block instagram 30m` would mean one Tab
     * can arm a real lock nobody asked for.
     */
    fun hintFor(partial: String): String? {
        val text = partial.trim().removePrefix("$").trim().lowercase()
        if (text.isEmpty() || text.contains(' ')) return null
        val matches = VERBS.filter { it.startsWith(text) }
        return if (matches.size == 1) USAGE[matches[0]] else null
    }

    /** The command a Tab or Space completion should insert, or null. */
    fun completionFor(partial: String): String? {
        val text = partial.trim().removePrefix("$").trim().lowercase()
        if (text.isEmpty() || text.contains(' ')) return null
        val matches = VERBS.filter { it.startsWith(text) }
        return matches.singleOrNull()
    }

    private fun ok(c: Command): ParseResult = ParseResult.Ok(c)
    private fun err(e: ParseError): ParseResult = ParseResult.Err(e)
}
