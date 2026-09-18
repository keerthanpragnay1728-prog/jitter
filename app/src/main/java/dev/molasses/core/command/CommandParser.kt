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
        "block", "focus", "bedtime", "sleep", "status",
        "alarm", "timer", "reboot", "poweroff", "wifi", "dnd",
        "help", "?",
    )

    /**
     * Argument shape per verb, shown as an inline hint. Never filled in with
     * concrete values: see the class doc.
     */
    val USAGE: Map<String, String> = mapOf(
        "block" to "block <app> <duration>",
        "focus" to "focus <duration>",
        "bedtime" to "bedtime",
        "sleep" to "sleep",
        "status" to "status",
        "alarm" to "alarm <time>",
        "timer" to "timer <duration>",
        "reboot" to "reboot",
        "poweroff" to "poweroff",
        "wifi" to "wifi [on|off]",
        "dnd" to "dnd [on|off]",
        "help" to "help",
        "?" to "?",
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

            "focus" -> durationOnly(verb, args) { Command.Focus(it) }
            "timer" -> durationOnly(verb, args) { Command.Timer(it) }

            "bedtime", "sleep" -> noArgs(verb, args, Command.Bedtime)
            "status" -> noArgs(verb, args, Command.Status)
            "help", "?" -> noArgs(verb, args, Command.Help)
            "reboot" -> noArgs(verb, args, Command.Reboot)
            "poweroff" -> noArgs(verb, args, Command.PowerOff)

            "alarm" -> when (args.size) {
                0 -> err(ParseError.MissingArgument(verb, "time"))
                1 -> when (val t = TimeParser.parse(args[0])) {
                    is TimeParser.Result.Ok -> ok(Command.Alarm(t.minuteOfDay))
                    is TimeParser.Result.Err -> err(ParseError.BadTime(args[0], t.kind))
                }
                else -> err(ParseError.TooManyArguments(verb))
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

    /**
     * The text the prompt should hold after a keystroke, applying space
     * completion.
     *
     * A soft keyboard has no Tab, and Space is the key a terminal user
     * reaches for anyway. So typing a space after a unique verb prefix
     * completes it: `bl` then Space becomes `block `.
     *
     * Only the verb, and only when the space is the very next character after
     * the prefix. Anything else is returned untouched, so a space inside an
     * app name or a reminder never rewrites what the user typed.
     *
     * @param before the text as it was.
     * @param after the text the field is proposing.
     */
    fun completeOnSpace(before: String, after: String): String {
        if (after != "$before ") return after
        val completion = completionFor(before) ?: return after
        val typed = before.removePrefix("$").trimStart()
        if (completion == typed) return after
        return before.dropLast(typed.length) + completion + " "
    }

    /**
     * The dim remainder to draw after what the user has typed, or null.
     *
     * The verb only. Ghosting an argument would put a concrete value on
     * screen one keystroke from being accepted, and the one rule this grammar
     * has is that no completion can ever offer a value.
     *
     * Returns null for anything but a plain lower-case prefix typed at the
     * start of the line. The ghost is drawn by overlaying the typed text
     * exactly, so it is only correct when the completion extends the typed
     * characters rather than changing them: `BL` completes to `block`, and
     * drawing "ock" after "BL" would read as `BLock`.
     */
    fun ghostFor(partial: String): String? {
        val typed = partial.removePrefix("$").trimStart()
        if (typed.isEmpty()) return null
        return if (typed.any { it.isWhitespace() }) argumentGhost(typed) else verbGhost(typed)
    }

    /**
     * The remainder of a unique verb prefix.
     *
     * Null for anything but a plain lower-case prefix. This branch *replaces*
     * characters conceptually, and the ghost is drawn by overlaying the typed
     * text exactly, so it is only correct when the completion extends what
     * was typed: `BL` completes to `block`, and drawing "ock" after "BL"
     * would read as `BLock`.
     */
    private fun verbGhost(typed: String): String? {
        if (typed != typed.lowercase()) return null
        val completion = completionFor(typed) ?: return null
        return completion.removePrefix(typed).ifEmpty { null }
    }

    /**
     * The argument shapes still owed, after a complete verb.
     *
     * ## It never expands what is being typed
     * `block insta` ghosts ` <duration>`, never `gram <duration>`. Expanding
     * a partial argument would have to replace characters the user has
     * already typed, and the ghost is an overlay with the typed prefix drawn
     * transparent: anything that does not purely extend the line renders as
     * garbage. That constraint is also the safe one, because a completion
     * that can rewrite an argument is a completion that can put a value the
     * user did not choose one keystroke from being armed.
     *
     * ## The shapes come from the registry and nowhere else
     * [USAGE] is the single source. A second list of argument hints would be
     * a second thing to keep in step with the grammar, and the usage strings
     * are already required by test to contain no digits, so a ghost
     * structurally cannot propose a concrete value.
     */
    private fun argumentGhost(typed: String): String? {
        val verb = typed.substringBefore(' ').lowercase()
        // A complete, known verb only. A prefix followed by a space is not
        // one, and offering arguments for a command the user has not finished
        // naming would be guessing at which command they meant.
        val usage = USAGE[verb] ?: return null

        val shapes = usage.split(' ').drop(1)
        if (shapes.isEmpty()) return null

        val rest = typed.substringAfter(' ')
        // A trailing space means the next argument has not been started; any
        // other text means one is part typed and this one is spoken for.
        val started = rest.split(Regex("\\s+")).count { it.isNotEmpty() }
        val owed = shapes.drop(started)
        if (owed.isEmpty()) return null

        // Prefixed with a space only when the caret is against a token, so
        // the ghost always reads as the next word rather than joining the one
        // being typed.
        val prefix = if (typed.endsWith(' ')) "" else " "
        return prefix + owed.joinToString(" ")
    }

    private fun ok(c: Command): ParseResult = ParseResult.Ok(c)
    private fun err(e: ParseError): ParseResult = ParseResult.Err(e)
}
