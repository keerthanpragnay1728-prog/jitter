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
import dev.molasses.core.command.AppTokenResolver
import dev.molasses.core.command.CommandSpec
import dev.molasses.core.command.DispatchResult
import dev.molasses.core.command.EffectSurface
import dev.molasses.core.command.ParseError
import dev.molasses.core.command.ReliefPolicy
import dev.molasses.core.command.Surface
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.core.lock.BedtimeWindow
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRequest
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
    /** Milliseconds left on [pkg]'s lock, or 0 when it is not locked. */
    val lockRemainingMs: (String) -> Long,
    /** True while any lock is armed. The relief policy reads this. */
    val anyLockArmed: () -> Boolean,
    /** The tracked packages, for `$ focus`. */
    val targets: () -> List<String>,
    /** An app token to a package. See [AppTokenResolver]. */
    val resolveApp: (String) -> AppTokenResolver.Result,
    /**
     * Arm or extend a lock, off the main thread.
     *
     * Fire and forget, because the store is authoritative: the extend-only
     * compare happens inside its transform, so this cannot be the thing that
     * decides whether a lock shortens. What the prompt reports is predicted
     * from the collected registry before this is called, and the only way that
     * prediction can be wrong is a second arm landing in between, which can
     * only make the lock longer.
     */
    val armLock: (List<String>, Long, LockReason) -> Unit,
    /** Minutes since local midnight, for `$ bedtime`. */
    val minuteOfDay: () -> Int,
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
    override fun availability(spec: CommandSpec): Availability = when (spec.verb) {
        // Locks are persisted and enforced. Three commands became available
        // in one edit here, which is the whole economy of surfacing.
        "block", "focus", "bedtime" -> Availability.Available

        // Relief, not restriction, and it does not run on LockRegistry. An
        // allowance suspends checkpoints, which means parking the penalty
        // ratchet as well as hiding the gate, and the ratchet lives in
        // FrictionEngine.
        "allow" -> Availability.Unavailable(R.string.cmd_na_no_allowance)

        "log" -> Availability.Unavailable(R.string.cmd_na_no_log)
        "rem" -> Availability.Unavailable(R.string.cmd_na_no_scheduling)
        else -> Availability.Unavailable(R.string.cmd_na_wiring)
    }
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
    override fun allows(): Availability = when {
        // A suspension nothing reads is not relief, it is a confirmation that
        // teaches the user that typing a command makes friction go away.
        !actions.health().acceptingEvents ->
            Availability.Unavailable(R.string.cmd_na_relief_needs_monitor)

        // Locks beat relief, in one direction only. A lock is the strongest
        // commitment this app offers and the only one that passes a
        // confirmation step. Leasing your way out of checkpoints on a
        // different app while a focus session stands would make the lock a
        // suggestion.
        actions.anyLockArmed() ->
            Availability.Unavailable(R.string.cmd_na_relief_while_locked)

        else -> Availability.Available
    }
}

// ----------------------------------------------------------------- dispatch

/**
 * The launcher's dispatcher. Built once and held; surfaces read live state.
 *
 * [showManual] is separate from [actions] because the manual is drawn inline
 * in the prompt's own composable and its visibility is that composable's
 * state. Hoisting it to the Activity would thread a boolean through two
 * layers to reach the place that already owns it.
 */
fun launcherDispatch(
    actions: LauncherActions,
    showManual: () -> Unit,
): CommandDispatch = CommandDispatch(
    registry = launcherRegistry(),
    surfaces = mapOf(
        Surface.STATE to StateSurface,
        Surface.INTENT to IntentSurface(actions),
        Surface.SERVICE to ServiceSurface,
        Surface.SUBSYSTEM to SubsystemSurface,
    ),
    reliefPolicy = MonitorReliefPolicy(actions),
    missingSurfaceKey = R.string.cmd_na_wiring,
    execute = { execute(it, actions, showManual) },
)

/** Resource ids for the registry, which is Android-free and cannot see `R`. */
fun launcherRegistry(): CommandRegistry = CommandRegistry(
    CommandRegistry.Keys(
        blockUsage = R.string.cmd_usage_block, blockDesc = R.string.cmd_desc_block,
        focusUsage = R.string.cmd_usage_focus, focusDesc = R.string.cmd_desc_focus,
        allowUsage = R.string.cmd_usage_allow, allowDesc = R.string.cmd_desc_allow,
        bedtimeUsage = R.string.cmd_usage_bedtime, bedtimeDesc = R.string.cmd_desc_bedtime,
        statusUsage = R.string.cmd_usage_status, statusDesc = R.string.cmd_desc_status,
        helpUsage = R.string.cmd_usage_help, helpDesc = R.string.cmd_desc_help,
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
private fun execute(
    command: Command,
    actions: LauncherActions,
    showManual: () -> Unit,
): DispatchResult = when (command) {
    Command.Status -> {
        actions.showLedger()
        DispatchResult.Confirmed(R.string.cmd_ack_status)
    }

    Command.Help -> {
        showManual()
        DispatchResult.Confirmed(R.string.cmd_ack_help)
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

    is Command.Block -> when (val resolved = actions.resolveApp(command.appToken)) {
        is AppTokenResolver.Result.One ->
            armOne(actions, resolved.pkg, command.durationMs, LockReason.BLOCK)
        AppTokenResolver.Result.None ->
            DispatchResult.Failed(R.string.cmd_err_unknown_app, listOf(command.appToken))
        is AppTokenResolver.Result.Ambiguous ->
            DispatchResult.Failed(
                R.string.cmd_err_ambiguous_app,
                listOf(command.appToken, resolved.packages.size.toString()),
            )
    }

    is Command.Focus -> {
        val targets = actions.targets()
        if (targets.isEmpty()) {
            DispatchResult.Failed(R.string.cmd_err_no_targets)
        } else {
            actions.armLock(targets, command.durationMs, LockReason.FOCUS)
            DispatchResult.Confirmed(
                R.string.cmd_ack_focus,
                listOf(CommandRender.duration(command.durationMs), targets.size.toString()),
            )
        }
    }

    Command.Bedtime -> {
        val targets = actions.targets()
        val durationMs = BedtimeWindow.durationMs(actions.minuteOfDay())
        if (targets.isEmpty()) {
            DispatchResult.Failed(R.string.cmd_err_no_targets)
        } else {
            actions.armLock(targets, durationMs, LockReason.BEDTIME)
            DispatchResult.Confirmed(
                R.string.cmd_ack_bedtime,
                listOf(CommandRender.duration(durationMs)),
            )
        }
    }

    is Command.Allow, is Command.Log, is Command.Remind,
    Command.Reboot, Command.PowerOff ->
        DispatchResult.Unavailable(R.string.cmd_na_wiring)
}

/**
 * Arm one lock, reporting what the registry will do rather than what was
 * asked for.
 *
 * The extend-only refusal is a [DispatchResult.Failed] rather than an
 * [DispatchResult.Unavailable] because the user can fix it by typing a longer
 * duration, which is the only thing that distinguishes the two outcomes.
 * Reporting a plain confirmation here would be the worst answer available:
 * `$ block instagram 1m` against a thirty day lock changes nothing, and
 * saying ACK would teach the user that it had.
 */
private fun armOne(
    actions: LauncherActions,
    pkg: String,
    durationMs: Long,
    reason: LockReason,
): DispatchResult {
    // Evaluated through LockRequest rather than inline, because the settings
    // scrubber arms locks too and the two must not be able to disagree about
    // what a duration means. The confirmation threshold is already handled by
    // the dispatcher for this path, so it passes confirmed.
    val verdict = LockRequest.evaluate(
        durationMs = durationMs,
        standingMs = actions.lockRemainingMs(pkg),
        confirmAboveMs = CommandRegistry.CONFIRM_ABOVE_MS,
        confirmed = true,
    )
    return when (verdict) {
        is LockRequest.Verdict.TooShort -> DispatchResult.Failed(
            R.string.cmd_err_lock_not_shortened,
            listOf(CommandRender.duration(verdict.standingMs)),
        )
        // Unreachable: the parser rejects a zero duration, so this is the
        // branch that would fire if it ever stopped.
        LockRequest.Verdict.Invalid -> DispatchResult.Failed(
            R.string.cmd_err_duration,
            listOf(CommandRender.duration(durationMs)),
        )
        is LockRequest.Verdict.Confirm, is LockRequest.Verdict.Arm -> {
            actions.armLock(listOf(pkg), durationMs, reason)
            DispatchResult.Confirmed(
                R.string.cmd_ack_lock,
                listOf(CommandRender.duration(durationMs)),
            )
        }
    }
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
