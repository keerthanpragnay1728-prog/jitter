package dev.molasses.core.command

import dev.molasses.core.lock.LockReason

/**
 * A parsed REPL line. Parsing only: nothing here dispatches, touches Android,
 * or knows what an installed package is.
 *
 * The split matters because half these commands are irreversible by design. A
 * grammar that is a pure function from text to a sealed type can be exhausted
 * by tests, which is the only way to be confident that `$ block instagram`
 * with a missing duration fails loudly rather than arming something.
 *
 * App names are carried as the raw token the user typed. Resolving a token to
 * a package needs the installed-app list, which is an Android concern; the
 * caller does that and reports its own failure.
 */
sealed interface Command {

    /** `$ block <app> <duration>` */
    data class Block(val appToken: String, val durationMs: Long) : Command

    /** `$ focus <duration>`. Locks every tracked target and silences notifications. */
    data class Focus(val durationMs: Long) : Command


    /** `$ bedtime` / `$ sleep`. Locks until the configured wake time. */
    data object Bedtime : Command

    /** `$ status`. Switch to the ledger page. */
    data object Status : Command

    /**
     * `$ help` / `$ ?`. Show the manual.
     *
     * A command rather than a key the prompt intercepts, so it appears in the
     * manual it opens. A discovery feature that cannot itself be discovered is
     * the same problem one level up.
     */
    data object Help : Command


    /** `$ alarm <time>` */
    data class Alarm(val minuteOfDay: Int) : Command

    /** `$ timer <duration>` */
    data class Timer(val durationMs: Long) : Command


    /** `$ reboot`, `$ poweroff`. Confirmation is the caller's problem. */
    data object Reboot : Command
    data object PowerOff : Command

    /** `$ wifi [on|off]`, `$ dnd [on|off]`. Null toggles. */
    data class Wifi(val enable: Boolean?) : Command
    data class Dnd(val enable: Boolean?) : Command

    /**
     * `$ calc <expression>`, `$ conv <amount> <from> <to>`,
     * `$ days until|since|between <date>`.
     *
     * ## Why these three carry raw text
     * Every other command has an argument list this grammar can check. These
     * three have an argument that is itself a language, with its own parser
     * and its own four or six named refusals, and re-checking the shape here
     * would be a second grammar that drifts from the first. So the line is
     * taken whole and handed on.
     *
     * The text is the tokens rejoined with single spaces, not the raw line,
     * so `render(parse(x))` comes back byte for byte and the history list
     * holds one spelling per command like everything else.
     *
     * ## They are reachable by verb only
     * Bare arithmetic is never evaluated and app search is untouched: typing
     * `2+3` filters apps, as it always did. A utility runs when its verb is
     * typed or when a manual row puts that verb in the prompt. That is what
     * settles the ambiguity between a calculation and an app called 25, and
     * it is not negotiable.
     */
    data class Calc(val expression: String) : Command

    /** `$ conv <amount> <from> <to>`. See [Calc] for why the text is raw. */
    data class Conv(val query: String) : Command

    /** `$ days until|since|between <date>`. See [Calc]. */
    data class Days(val query: String) : Command

    /**
     * `$ rem <time> <text>` or `$ rem <duration> <text>`. The text is the
     * remainder of the line exactly as typed, internal spacing included. See
     * `ReminderBook` for the queue and CLAUDE.md for why this command is an
     * exception to the console's boundary.
     */
    data class Rem(val whenSpec: dev.molasses.core.remind.ReminderBook.When, val text: String) : Command

    /**
     * `$ rem` with nothing after it: the pending reminders, soonest first,
     * at most `ReminderBook.LIST_MAX`. Pending only. A fired reminder already
     * waits on the console until dismissed, and a list of past ones is state
     * the user comes back to read, which the console's boundary excludes.
     */
    data object RemList : Command
}

/** Why a line did not parse. Every one of these is shown to the user verbatim. */
sealed interface ParseError {
    data object Empty : ParseError
    data class UnknownCommand(val verb: String) : ParseError
    data class MissingArgument(val verb: String, val expected: String) : ParseError
    data class TooManyArguments(val verb: String) : ParseError
    data class BadDuration(val token: String, val kind: dev.molasses.core.time.DurationParser.Kind) :
        ParseError
    data class BadTime(val token: String, val kind: dev.molasses.core.time.TimeParser.Kind) :
        ParseError
    data class BadToggle(val token: String) : ParseError

    /** `$ rem`'s first argument is neither a time of day nor a duration. */
    data class BadWhen(val token: String) : ParseError
}

/** Result of parsing one line. */
sealed interface ParseResult {
    data class Ok(val command: Command) : ParseResult
    data class Err(val error: ParseError) : ParseResult
}

/** The lock reason a command implies, for the ledger. */
fun Command.lockReason(): LockReason? = when (this) {
    is Command.Block -> LockReason.BLOCK
    is Command.Focus -> LockReason.FOCUS
    Command.Bedtime -> LockReason.BEDTIME
    else -> null
}
