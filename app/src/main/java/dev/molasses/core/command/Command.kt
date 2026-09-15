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

    /**
     * `$ allow <app> <duration>`. Suspends checkpoint gates only. The stall
     * curve stays armed and time keeps accumulating, so this is a lease on
     * the tolls, not on the friction.
     */
    data class Allow(val appToken: String, val durationMs: Long) : Command

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

    /** `$ log [app]`. Filter the system log, or clear the filter. */
    data class Log(val appToken: String?) : Command

    /** `$ alarm <time>` */
    data class Alarm(val minuteOfDay: Int) : Command

    /** `$ timer <duration>` */
    data class Timer(val durationMs: Long) : Command

    /** `$ rem <duration> <text>` */
    data class Remind(val durationMs: Long, val text: String) : Command

    /** `$ reboot`, `$ poweroff`. Confirmation is the caller's problem. */
    data object Reboot : Command
    data object PowerOff : Command

    /** `$ wifi [on|off]`, `$ dnd [on|off]`. Null toggles. */
    data class Wifi(val enable: Boolean?) : Command
    data class Dnd(val enable: Boolean?) : Command
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
