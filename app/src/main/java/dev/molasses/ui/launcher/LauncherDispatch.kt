package dev.molasses.ui.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.annotation.StringRes
import dev.molasses.R
import dev.molasses.core.command.Availability
import dev.molasses.core.command.Command
import dev.molasses.core.command.CommandDispatch
import dev.molasses.core.command.CommandParser
import dev.molasses.core.command.CommandRegistry
import dev.molasses.core.command.CommandRender
import dev.molasses.core.command.CommandSpec
import dev.molasses.core.command.DispatchResult
import dev.molasses.core.command.EffectSurface
import dev.molasses.core.command.ParseError
import dev.molasses.core.command.ReliefPolicy
import dev.molasses.core.command.Surface
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.monitor.ServiceDiagnostics

/*
 * The Android half of command dispatch.
 *
 * ## What changed, and why NotWired had to go
 * There used to be a `CommandOutcome.NotWired` sitting beside `Failed`, and a
 * `when` over every verb deciding which to return. Two things were wrong with
 * it. A `when` over fourteen verbs rots by the fifteenth, and more importantly
 * "not implemented" was a special case next to "no clock app installed" and
 * "service not bound" when all three are the same statement: something this
 * command needs is not here. One outcome, several reasons, and a new reason
 * costs a string.
 *
 * ## The four surfaces
 * Every command names a [Surface], and a surface answers once for everything
 * on it. That is the whole economy of the design: when the lock subsystem
 * lands, [SubsystemSurface] starts returning `Available` and four commands
 * work at once with no per-verb edit.
 *
 * ## Parsing still knows nothing about any of this
 * `$ block instagram 30m` parses whether or not locks exist. Availability is
 * decided here and only here, which is what keeps the parser exhaustively
 * testable and what will let the help page list commands that do not work yet
 * rather than hiding them.
 */

/**
 * Side effects the launcher can actually perform.
 *
 * Passed in rather than reached for, so this file has no Activity reference
 * and the dispatch table can be read in one screen.
 */
class LauncherActions(
    val showLedger: () -> Unit,
    /** @return false when nothing handled the Intent. */
    val startIntent: (Intent) -> Boolean,
    /** Whether anything on this device handles the Intent. */
    val canResolve: (Intent) -> Boolean,
    val health: () -> ServiceHealth = { ServiceDiagnostics.health() },
)

// ----------------------------------------------------------------- surfaces

/** Scrolls the pager. Nothing external can stop it. */
private object StateSurface : EffectSurface {
    override val surface = Surface.STATE
    override fun availability(spec: CommandSpec) = Availability.Available
}

/**
 * Whether an Intent resolves, which is a different question per command: a
 * device with a clock app and no wifi panel is ordinary.
 *
 * The probe carries the action and no extras, because resolution is decided by
 * the action. Building the real Intent here would mean building it twice.
 */
private class IntentSurface(private val actions: LauncherActions) : EffectSurface {
    override val surface = Surface.INTENT

    override fun availability(spec: CommandSpec): Availability {
        val action = probeAction(spec.verb) ?: return Availability.Unavailable(R.string.cmd_na_wiring)
        return if (actions.canResolve(Intent(action))) {
            Availability.Available
        } else {
            Availability.Unavailable(missingHandlerReason(spec.verb))
        }
    }
}

/**
 * Reboot and power off.
 *
 * This deliberately does not consult the binding. Even a bound accessibility
 * service cannot reboot a phone, so reporting "the service is not bound" would
 * tell the user that binding it would help, and they would go and try.
 */
private object ServiceSurface : EffectSurface {
    override val surface = Surface.SERVICE
    override fun availability(spec: CommandSpec) =
        Availability.Unavailable(R.string.cmd_na_privileged)
}

/**
 * The parts of this app that are not built.
 *
 * One surface, three reasons, because the lock subsystem, the log page and
 * reminder scheduling are three separate absences and collapsing them into
 * "not implemented" would tell the user nothing about which.
 */
private object SubsystemSurface : EffectSurface {
    override val surface = Surface.SUBSYSTEM
    override fun availability(spec: CommandSpec) = Availability.Unavailable(
        when (spec.verb) {
            "log" -> R.string.cmd_na_no_log
            "rem" -> R.string.cmd_na_no_scheduling
            // block, focus, allow, bedtime. One registry, one absence.
            else -> R.string.cmd_na_no_lock
        },
    )
}

/**
 * Relief needs something to grant relief from.
 *
 * `$ allow` suspends checkpoints, and a suspension nothing reads is not
 * relief, it is a confirmation that teaches the user that typing `$ allow`
 * makes friction go away. So the monitor has to be alive before any relief is
 * granted, and the check sits at dispatch rather than in a handler so the next
 * relief command inherits it.
 */
private class MonitorReliefPolicy(private val actions: LauncherActions) : ReliefPolicy {
    override fun allows(): Availability =
        if (actions.health().acceptingEvents) Availability.Available
        else Availability.Unavailable(R.string.cmd_na_relief_needs_monitor)
}

// ----------------------------------------------------------------- dispatch

/** The launcher's dispatcher. Built once and held; surfaces read live state. */
fun launcherDispatch(actions: LauncherActions): CommandDispatch = CommandDispatch(
    registry = launcherRegistry(),
    surfaces = mapOf(
        Surface.STATE to StateSurface,
        Surface.INTENT to IntentSurface(actions),
        Surface.SERVICE to ServiceSurface,
        Surface.SUBSYSTEM to SubsystemSurface,
    ),
    reliefPolicy = MonitorReliefPolicy(actions),
    missingSurfaceKey = R.string.cmd_na_wiring,
    execute = { execute(it, actions) },
)

/** Resource ids for the registry, which is Android-free and cannot see `R`. */
fun launcherRegistry(): CommandRegistry = CommandRegistry(
    CommandRegistry.Keys(
        blockUsage = R.string.cmd_usage_block, blockDesc = R.string.cmd_desc_block,
        focusUsage = R.string.cmd_usage_focus, focusDesc = R.string.cmd_desc_focus,
        allowUsage = R.string.cmd_usage_allow, allowDesc = R.string.cmd_desc_allow,
        bedtimeUsage = R.string.cmd_usage_bedtime, bedtimeDesc = R.string.cmd_desc_bedtime,
        statusUsage = R.string.cmd_usage_status, statusDesc = R.string.cmd_desc_status,
        logUsage = R.string.cmd_usage_log, logDesc = R.string.cmd_desc_log,
        alarmUsage = R.string.cmd_usage_alarm, alarmDesc = R.string.cmd_desc_alarm,
        timerUsage = R.string.cmd_usage_timer, timerDesc = R.string.cmd_desc_timer,
        remUsage = R.string.cmd_usage_rem, remDesc = R.string.cmd_desc_rem,
        rebootUsage = R.string.cmd_usage_reboot, rebootDesc = R.string.cmd_desc_reboot,
        poweroffUsage = R.string.cmd_usage_poweroff, poweroffDesc = R.string.cmd_desc_poweroff,
        wifiUsage = R.string.cmd_usage_wifi, wifiDesc = R.string.cmd_desc_wifi,
        dndUsage = R.string.cmd_usage_dnd, dndDesc = R.string.cmd_desc_dnd,
    ),
)

/**
 * Runs a command that has cleared every gate.
 *
 * Exhaustive rather than an `else`, so a command whose surface starts
 * reporting `Available` without an implementation here fails the build rather
 * than silently confirming and doing nothing. The last branch is unreachable
 * while those surfaces refuse everything, and says so as a wiring bug rather
 * than as a user-facing condition.
 */
private fun execute(command: Command, actions: LauncherActions): DispatchResult = when (command) {
    Command.Status -> {
        actions.showLedger()
        DispatchResult.Confirmed(R.string.cmd_ack_status)
    }

    is Command.Alarm -> {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.minuteOfDay / 60)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minuteOfDay % 60)
            // The clock app's own UI opens with the fields filled. Skipping it
            // would set an alarm the user never saw, which is the wrong
            // default for a command typed in one line.
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (actions.startIntent(intent)) {
            DispatchResult.Confirmed(
                R.string.cmd_ack_alarm,
                listOf(CommandRender.time(command.minuteOfDay)),
            )
        } else {
            DispatchResult.Unavailable(R.string.cmd_na_no_clock_app)
        }
    }

    is Command.Timer -> {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, (command.durationMs / 1000L).toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (actions.startIntent(intent)) {
            DispatchResult.Confirmed(
                R.string.cmd_ack_timer,
                listOf(CommandRender.duration(command.durationMs)),
            )
        } else {
            DispatchResult.Unavailable(R.string.cmd_na_no_clock_app)
        }
    }

    is Command.Wifi -> when (command.enable) {
        // An app has not been able to toggle wifi since API 29. Opening the
        // panel is the whole of what is possible, so a bare "$ wifi" does
        // exactly what it says and "$ wifi on" does not.
        null ->
            if (actions.startIntent(Intent(wifiPanelAction()))) {
                DispatchResult.Confirmed(R.string.cmd_ack_wifi_panel)
            } else {
                DispatchResult.Unavailable(R.string.cmd_na_no_panel)
            }
        else -> DispatchResult.Unavailable(R.string.cmd_na_wifi_toggle)
    }

    is Command.Dnd -> when (command.enable) {
        // Same shape: setting DND needs ACCESS_NOTIFICATION_POLICY, which this
        // app deliberately does not request.
        null ->
            if (actions.startIntent(Intent(DND_ACTION))) {
                DispatchResult.Confirmed(R.string.cmd_ack_dnd_panel)
            } else {
                DispatchResult.Unavailable(R.string.cmd_na_no_panel)
            }
        else -> DispatchResult.Unavailable(R.string.cmd_na_dnd_toggle)
    }

    is Command.Block, is Command.Focus, is Command.Allow, Command.Bedtime,
    is Command.Log, is Command.Remind, Command.Reboot, Command.PowerOff ->
        DispatchResult.Unavailable(R.string.cmd_na_wiring)
}

// -------------------------------------------------------------------- intents

private val DND_ACTION = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS

private fun wifiPanelAction(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
    else Settings.ACTION_WIFI_SETTINGS

/** The action an INTENT command resolves against, or null if it is not one. */
private fun probeAction(verb: String): String? = when (verb) {
    "alarm" -> AlarmClock.ACTION_SET_ALARM
    "timer" -> AlarmClock.ACTION_SET_TIMER
    "wifi" -> wifiPanelAction()
    "dnd" -> DND_ACTION
    else -> null
}

@StringRes
private fun missingHandlerReason(verb: String): Int = when (verb) {
    "alarm", "timer" -> R.string.cmd_na_no_clock_app
    else -> R.string.cmd_na_no_panel
}

// ---------------------------------------------------------------- rendering

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
    is ParseError.MissingArgument -> CommandParser.USAGE[verb] ?: verb
    is ParseError.TooManyArguments -> CommandParser.USAGE[verb] ?: verb
    is ParseError.BadDuration -> token
    is ParseError.BadTime -> token
    is ParseError.BadToggle -> token
}

/** A parse failure as a dispatch result, so the prompt has one result type. */
fun ParseError.asFailure(): DispatchResult =
    DispatchResult.Failed(messageRes(), listOfNotNull(argument()))

/**
 * The result as a line for Bit to say.
 *
 * Resolved at the call site rather than stored, per the repo rule that copy
 * lives in resources and state maps to a @StringRes in a plain function.
 *
 * One empty argument is always appended. Surplus arguments are ignored by
 * `String.format`, so this is safe for a string that takes none and it stops
 * a string that takes one from throwing when a caller supplied none.
 */
fun DispatchResult.message(context: Context): String = when (this) {
    is DispatchResult.Confirmed -> context.getString(ackKey, *formatArgs(args))
    is DispatchResult.Failed -> context.getString(reasonKey, *formatArgs(args))
    is DispatchResult.Unavailable -> context.getString(reasonKey, *formatArgs(args))
    is DispatchResult.NeedsConfirmation -> context.getString(R.string.cmd_confirm_line, echo)
    DispatchResult.NotACommand -> ""
}

private fun formatArgs(args: List<String>): Array<Any> = (args + "").toTypedArray()
