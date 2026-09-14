package dev.molasses.ui.launcher

import androidx.annotation.StringRes
import dev.molasses.R
import dev.molasses.core.command.Command
import dev.molasses.core.command.CommandRender
import dev.molasses.core.command.ParseError

/**
 * What happened to a command line.
 *
 * Three outcomes, not two, and the third one is the point. A command that
 * parsed perfectly well but cannot run yet is neither a success nor a syntax
 * error, and collapsing it into either would lie: showing a confirmation for
 * `$ block instagram 30m` when no lock was armed is the worst possible
 * outcome, because the user would stop checking.
 */
sealed interface CommandOutcome {

    /** It ran. Bit shows the shared CONFIRM sequence. */
    data class Executed(@StringRes val messageRes: Int, val arg: String? = null) : CommandOutcome

    /**
     * It parsed, and nothing happened. Bit shows FAILED, deliberately: the
     * dry face is the honest one when no state changed.
     */
    data class NotWired(@StringRes val messageRes: Int, val arg: String? = null) : CommandOutcome

    /** It did not parse. Bit shows FAILED with the grammar hint. */
    data class Failed(@StringRes val messageRes: Int, val arg: String? = null) : CommandOutcome

    /** Not a command at all. The caller falls back to filtering apps. */
    data object NotACommand : CommandOutcome
}

/**
 * Side effects the launcher can actually perform. Passed in so this file has
 * no Android imports beyond the annotation, and so the outcome table can be
 * read in one screen without chasing call sites.
 */
class LauncherActions(
    val showLedger: () -> Unit,
    val openWifiPanel: () -> Boolean,
    val openDndSettings: () -> Boolean,
)

/**
 * Routes a parsed [Command] to an action.
 *
 * ## It does not parse
 * Parsing lives in `core/command/CommandParser`, is exhaustively tested there,
 * and is not duplicated here. This file only decides what a already-parsed
 * command does.
 *
 * ## Most commands are honestly not wired yet
 * `$ block`, `$ focus`, `$ allow` and `$ bedtime` all need `LockRegistry`
 * persisted and enforced by the accessibility service, which does not exist.
 * `$ alarm`, `$ timer` and `$ rem` need AlarmManager scheduling. `$ log` needs
 * a system log page. `$ reboot` and `$ poweroff` need system permissions a
 * sideloaded app cannot hold at all, so those two will never be wired and say
 * so in a distinct message.
 *
 * Each returns [CommandOutcome.NotWired] naming what is missing. The grammar
 * is real and tested; the actions arrive behind it.
 */
object CommandDispatcher {

    fun dispatch(command: Command, actions: LauncherActions): CommandOutcome = when (command) {
        // ------------------------------------------------- actually wired
        Command.Status -> {
            actions.showLedger()
            CommandOutcome.Executed(R.string.cmd_ack_status)
        }

        is Command.Wifi -> when (command.enable) {
            // An app has not been able to toggle wifi since API 29. Opening
            // the panel is the whole of what is possible, so a bare "$ wifi"
            // does exactly what it says and "$ wifi on" does not.
            null ->
                if (actions.openWifiPanel()) CommandOutcome.Executed(R.string.cmd_ack_wifi_panel)
                else CommandOutcome.NotWired(R.string.cmd_err_no_panel)
            else -> CommandOutcome.NotWired(R.string.cmd_err_wifi_toggle)
        }

        is Command.Dnd -> when (command.enable) {
            // Same shape: setting DND needs ACCESS_NOTIFICATION_POLICY, which
            // this app deliberately does not request.
            null ->
                if (actions.openDndSettings()) CommandOutcome.Executed(R.string.cmd_ack_dnd_panel)
                else CommandOutcome.NotWired(R.string.cmd_err_no_panel)
            else -> CommandOutcome.NotWired(R.string.cmd_err_dnd_toggle)
        }

        // ------------------------------------------- parsed, not yet wired
        is Command.Block ->
            CommandOutcome.NotWired(R.string.cmd_err_no_lock, CommandRender.render(command))
        is Command.Allow ->
            CommandOutcome.NotWired(R.string.cmd_err_no_lock, CommandRender.render(command))
        is Command.Focus ->
            CommandOutcome.NotWired(R.string.cmd_err_no_lock, CommandRender.render(command))
        Command.Bedtime ->
            CommandOutcome.NotWired(R.string.cmd_err_no_lock, CommandRender.render(command))

        is Command.Alarm ->
            CommandOutcome.NotWired(R.string.cmd_err_no_alarm, CommandRender.render(command))
        is Command.Timer ->
            CommandOutcome.NotWired(R.string.cmd_err_no_alarm, CommandRender.render(command))
        is Command.Remind ->
            CommandOutcome.NotWired(R.string.cmd_err_no_alarm, CommandRender.render(command))

        is Command.Log ->
            CommandOutcome.NotWired(R.string.cmd_err_no_log)

        // These two are not "not yet". A sideloaded app cannot reboot or
        // power off a device at all, so the message says never rather than
        // implying it is coming.
        Command.Reboot, Command.PowerOff ->
            CommandOutcome.NotWired(R.string.cmd_err_privileged)
    }
}

/**
 * A parse failure, as something to show the user.
 *
 * Every branch names the offending token back, because the single most common
 * failure is a duration the user thought was valid. "block <app> <duration>"
 * on its own does not tell someone that `30mins` is wrong; "30mins" does.
 */
@StringRes
fun ParseError.messageRes(): Int = when (this) {
    ParseError.Empty -> R.string.cmd_err_empty
    is ParseError.UnknownCommand -> R.string.cmd_err_unknown
    is ParseError.MissingArgument -> R.string.cmd_err_missing
    is ParseError.TooManyArguments -> R.string.cmd_err_surplus
    is ParseError.BadDuration -> R.string.cmd_err_duration
    is ParseError.BadTime -> R.string.cmd_err_time
    is ParseError.BadToggle -> R.string.cmd_err_toggle
}

/** The token or usage hint to interpolate into [messageRes]. */
fun ParseError.argument(): String? = when (this) {
    ParseError.Empty -> null
    is ParseError.UnknownCommand -> verb
    is ParseError.MissingArgument ->
        dev.molasses.core.command.CommandParser.USAGE[verb] ?: verb
    is ParseError.TooManyArguments ->
        dev.molasses.core.command.CommandParser.USAGE[verb] ?: verb
    is ParseError.BadDuration -> token
    is ParseError.BadTime -> token
    is ParseError.BadToggle -> token
}

/**
 * The outcome as a line for Bit to say.
 *
 * Resolved at the call site rather than stored, per the repo rule that copy
 * lives in resources and state maps to a @StringRes in a plain function.
 * Surplus arguments are ignored by String.format, so passing one
 * unconditionally to a family where only some strings take it is safe.
 */
fun CommandOutcome.message(context: android.content.Context): String = when (this) {
    is CommandOutcome.Executed -> context.getString(messageRes, arg ?: "")
    is CommandOutcome.NotWired -> context.getString(messageRes, arg ?: "")
    is CommandOutcome.Failed -> context.getString(messageRes, arg ?: "")
    CommandOutcome.NotACommand -> ""
}
