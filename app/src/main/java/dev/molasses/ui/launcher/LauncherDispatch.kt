package dev.molasses.ui.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
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
import dev.molasses.core.remind.Reminder
import dev.molasses.core.remind.ReminderArming
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.session.TargetScope
import dev.molasses.core.util.Calc
import dev.molasses.core.util.Convert
import dev.molasses.core.util.DateMath
import dev.molasses.core.util.Decimal
import dev.molasses.monitor.ServiceDiagnostics
import dev.molasses.ui.lock.lockOpensAtText
import java.time.LocalDate

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
/** What `$ rem` did, for the prompt to report. */
sealed interface RemindOutcome {
    /** Saved in the store, and armed however [armed] says, which may be not at all. */
    data class Saved(val dueWallMs: Long, val armed: ReminderArming.Armed) : RemindOutcome

    /** Twenty are pending. Nothing was added. */
    data object Full : RemindOutcome

    /** A date and time that is not in the future. Nothing was added. */
    data object Past : RemindOutcome
}

/**
 * One acknowledgement per arming outcome, so the prompt never says INEXACT
 * when nothing was scheduled. See [ReminderArming].
 */
@StringRes
fun remindAckKey(armed: ReminderArming.Armed): Int = when (armed) {
    ReminderArming.Armed.EXACT -> R.string.cmd_ans_rem_exact
    ReminderArming.Armed.INEXACT -> R.string.cmd_ans_rem_inexact
    ReminderArming.Armed.NOT_ARMED -> R.string.cmd_ans_rem_not_armed
}

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
    /**
     * The tracked packages, for `$ focus` and `$ bedtime`.
     *
     * **Resolved, never the raw stored list.** `$ focus` and `$ bedtime`
     * refuse when this is empty, and the stored list is empty on every device
     * between first launch and the first edit, so the raw list made both
     * commands report that the device has no targets while the service was
     * gating five apps. See CLAUDE.md, "The stored target list is not the
     * tracked set".
     */
    val targets: () -> List<String>,
    /**
     * The installed packages, or null before they are known. Counts and
     * empty checks on [targets] go through `TargetScope.gateable` with this.
     */
    val installedPackages: () -> Set<String>?,
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
    /** Minutes since local midnight, for `$ bedtime` and `$ rem`. */
    val minuteOfDay: () -> Int,
    /**
     * Set a reminder, and call back once with what happened: added and
     * scheduled, exact or not, or refused because twenty are pending. The
     * callback comes after the store write and the scheduling, on the main
     * thread.
     */
    val remind: (ReminderBook.When, String, (RemindOutcome) -> Unit) -> Unit,
    /** Every reminder not yet dismissed, as the console last collected it. For a bare `$ rem`. */
    val pendingReminders: () -> List<Reminder>,
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
        // Reminders are scheduled and held; see ReminderAlarms.
        "rem" -> Availability.Available

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
    /**
     * Resolves the one piece of copy a handler assembles rather than names.
     *
     * The percent note is several `literal = value` pairs on one line, so its
     * template is applied more than once and cannot be a single key handed
     * to the prompt. Everything else on this path stays a resource id that
     * the prompt resolves at the call site.
     */
    context: Context,
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
    execute = { execute(it, context, actions, showManual) },
)

/** Resource ids for the registry, which is Android-free and cannot see `R`. */
fun launcherRegistry(): CommandRegistry = CommandRegistry(
    CommandRegistry.Keys(
        blockUsage = R.string.cmd_usage_block, blockDesc = R.string.cmd_desc_block,
        focusUsage = R.string.cmd_usage_focus, focusDesc = R.string.cmd_desc_focus,
        bedtimeUsage = R.string.cmd_usage_bedtime, bedtimeDesc = R.string.cmd_desc_bedtime,
        statusUsage = R.string.cmd_usage_status, statusDesc = R.string.cmd_desc_status,
        helpUsage = R.string.cmd_usage_help, helpDesc = R.string.cmd_desc_help,
        alarmUsage = R.string.cmd_usage_alarm, alarmDesc = R.string.cmd_desc_alarm,
        timerUsage = R.string.cmd_usage_timer, timerDesc = R.string.cmd_desc_timer,
        rebootUsage = R.string.cmd_usage_reboot, rebootDesc = R.string.cmd_desc_reboot,
        poweroffUsage = R.string.cmd_usage_poweroff, poweroffDesc = R.string.cmd_desc_poweroff,
        wifiUsage = R.string.cmd_usage_wifi, wifiDesc = R.string.cmd_desc_wifi,
        dndUsage = R.string.cmd_usage_dnd, dndDesc = R.string.cmd_desc_dnd,
        calcUsage = R.string.cmd_usage_calc, calcDesc = R.string.cmd_desc_calc,
        convUsage = R.string.cmd_usage_conv, convDesc = R.string.cmd_desc_conv,
        daysUsage = R.string.cmd_usage_days, daysDesc = R.string.cmd_desc_days,
        remUsage = R.string.cmd_usage_rem, remDesc = R.string.cmd_desc_rem,
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
    context: Context,
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

    // An app has not been able to toggle wifi since API 29, so the panel is
    // the whole of what is possible either way. Both forms open it; only the
    // acknowledgement differs.
    //
    // "$ wifi on" used to be refused outright, which was the tidy answer and
    // the wrong one. The user typed it meaning "I want wifi on", the panel is
    // the nearest thing the platform permits and it is one tap from done, and
    // a refusal bought nothing except making them type "$ wifi" afterwards.
    //
    // It is still not ignored. Silently dropping an argument someone typed is
    // its own fault and a worse one, because the next thing they learn is that
    // this prompt does not read what they write. So the modifier is answered
    // rather than dropped, by a different acknowledgement.
    //
    // That acknowledgement used to carry the reason as well and was two
    // sentences long, which is too much for a terminal line that appears for
    // two seconds beside a face. It now says only that the toggle did not
    // happen, and the reason moved to the manual entry, where there is room
    // and where someone asking "why" is already looking. The reasons stay
    // distinct there: wifi is a platform limit binding every app, DND is a
    // permission this app chose not to request, and claiming the first for
    // the second is a lie the user cannot check.
    is Command.Wifi ->
        if (actions.startIntent(Intent(wifiPanelAction()))) {
            DispatchResult.Confirmed(
                if (command.enable == null) R.string.cmd_ack_wifi_panel
                else R.string.cmd_ack_wifi_panel_no_toggle,
            )
        } else {
            DispatchResult.Unavailable(R.string.cmd_na_no_panel)
        }

    // Same shape, different reason, and the difference is kept. It lives in
    // cmd_desc_dnd now rather than in the ack. See the wifi branch above.
    is Command.Dnd ->
        if (actions.startIntent(Intent(DND_ACTION))) {
            DispatchResult.Confirmed(
                if (command.enable == null) R.string.cmd_ack_dnd_panel
                else R.string.cmd_ack_dnd_panel_no_toggle,
            )
        } else {
            DispatchResult.Unavailable(R.string.cmd_na_no_panel)
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

    // Counted and empty-checked through TargetScope.gateable, like CFG's
    // TARGETS header: a stored package that is not installed gates nothing
    // and is not in the number. The lock still covers the whole tracked set,
    // so reinstalling an app mid-focus is not a way out.
    is Command.Focus -> {
        val targets = actions.targets()
        val gateable = TargetScope.gateable(targets, actions.installedPackages())
        Log.i(TARGETS_TAG, "focus: tracked=${targets.size} gateable=${gateable.size}")
        if (gateable.isEmpty()) {
            DispatchResult.Failed(R.string.cmd_err_no_targets)
        } else {
            actions.armLock(targets, command.durationMs, LockReason.FOCUS)
            DispatchResult.Confirmed(
                R.string.cmd_ack_focus,
                listOf(CommandRender.duration(command.durationMs), gateable.size.toString()),
            )
        }
    }

    Command.Bedtime -> {
        val targets = actions.targets()
        val gateable = TargetScope.gateable(targets, actions.installedPackages())
        Log.i(TARGETS_TAG, "bedtime: tracked=${targets.size} gateable=${gateable.size}")
        val durationMs = BedtimeWindow.durationMs(actions.minuteOfDay())
        if (gateable.isEmpty()) {
            DispatchResult.Failed(R.string.cmd_err_no_targets)
        } else {
            actions.armLock(targets, durationMs, LockReason.BEDTIME)
            DispatchResult.Confirmed(
                R.string.cmd_ack_bedtime,
                listOf(CommandRender.duration(durationMs)),
            )
        }
    }

    Command.Reboot, Command.PowerOff ->
        DispatchResult.Unavailable(R.string.cmd_na_wiring)

    // The three utilities. STATE: each is a pure function of the text typed,
    // so nothing here can be unavailable and the only failure is the user's
    // input, which each utility's own parser names.
    is Command.Calc -> when (val result = Calc.evaluate(command.expression)) {
        is Calc.Result.Value -> {
            // The note is present exactly when the expression contained a
            // percent, because that is the only place this grammar turns
            // what was typed into something else. See Calc's class doc.
            val notes = Calc.percentNotes(command.expression)
            DispatchResult.Answered(
                ackKey = R.string.cmd_ans_calc,
                args = listOf(Decimal.format(result.value)),
                noteKey = if (notes.isEmpty()) null else R.string.cmd_ans_percent,
                noteArgs = if (notes.isEmpty()) emptyList()
                else listOf(percentNoteText(context, notes)),
            )
        }
        is Calc.Result.Failed -> DispatchResult.Failed(result.error.messageRes())
    }

    is Command.Conv -> when (val result = Convert.parse(command.query)) {
        // No note. Nothing in a conversion is ambiguous or substituted: the
        // user named both units and the answer names the one it is in. A
        // second line echoing "5 km" back would be the default echo this
        // deliberately does not do.
        is Convert.Result.Value -> DispatchResult.Answered(
            ackKey = R.string.cmd_ans_conv,
            args = listOf(Decimal.format(result.value), result.unit.token),
        )
        is Convert.Result.Failed -> DispatchResult.Failed(result.error.messageRes())
    }

    // Not a utility: it leaves state behind, which is why it is the one
    // deliberate exception to the console's boundary. See CLAUDE.md.
    //
    // Deferred, because what it reports is known only after the store has
    // taken it and the alarm is set. Answered rather than Confirmed so the
    // acknowledgement is held until the next keystroke, the way calc's is;
    // a reaction faded before it could be read.
    is Command.Rem -> DispatchResult.Deferred { deliver ->
        actions.remind(command.whenSpec, command.text) { outcome ->
            deliver(
                when (outcome) {
                    is RemindOutcome.Saved -> DispatchResult.Answered(
                        ackKey = remindAckKey(outcome.armed),
                        // An acknowledgement, not content: it goes by
                        // itself once read. See ReadingWindow.
                        readingWindow = true,
                        args = listOf(lockOpensAtText(context, outcome.dueWallMs)),
                    )
                    RemindOutcome.Full -> DispatchResult.Failed(
                        R.string.cmd_err_rem_full,
                        listOf(ReminderBook.MAX.toString()),
                    )
                    RemindOutcome.Past -> DispatchResult.Failed(R.string.cmd_err_rem_past)
                },
            )
        }
    }

    // A bare rem. Held like the set acknowledgement, and pending only: a
    // fired reminder is already on the console until dismissed.
    Command.RemList -> {
        val all = actions.pendingReminders()
        val pending = ReminderBook.pending(all)
        Log.i(REMINDER_TAG, "rem list: read ${all.size} reminders, ${all.count { !it.fired }} unfired, showing ${pending.size}")
        if (pending.isEmpty()) {
            DispatchResult.Answered(R.string.cmd_ans_rem_none)
        } else {
            DispatchResult.Answered(
                ackKey = R.string.cmd_ans_rem_list,
                args = listOf(
                    pending.joinToString("\n") {
                        context.getString(R.string.cmd_ans_rem_row, lockOpensAtText(context, it.due.wallMs), it.text)
                    },
                ),
            )
        }
    }

    is Command.Days -> when (val result = DateMath.parse(command.query, LocalDate.now())) {
        is DateMath.Result.Span -> DispatchResult.Answered(
            ackKey = daysAnswerRes(result.days),
            args = listOf(result.days.toString()),
            // Only when a date had to be resolved. An ISO date is already
            // what it says; a bare "25 dec" had a year chosen for it, and a
            // span that reaches forward had two.
            noteKey = if (result.resolved) R.string.cmd_ans_span else null,
            noteArgs = if (result.resolved) listOf(result.from.toString(), result.to.toString())
            else emptyList(),
        )
        is DateMath.Result.Failed -> DispatchResult.Failed(result.error.messageRes())
    }
}

/**
 * The percent notes as one line.
 *
 * Joined here rather than in `core`, because the separator between two of
 * them and the equals sign inside one are both things on screen, and things
 * on screen come out of resources.
 */
private fun percentNoteText(context: Context, notes: List<Calc.PercentNote>): String =
    notes.joinToString(NOTE_SEPARATOR) {
        context.getString(R.string.cmd_ans_pair, it.literal, it.value)
    }

/** Two spaces. Wide enough to read as a break, narrow enough to stay one line. */
private const val NOTE_SEPARATOR = "  "

/**
 * Singular or plural, picked by a plain function and resolved at the call
 * site, which is how this app maps state to copy.
 *
 * Negative counts take the plural, because `days until` on a date already
 * past answers a negative number and "-1 day" reads worse than "-1 days"
 * does badly.
 */
@StringRes
private fun daysAnswerRes(days: Long): Int =
    if (days == 1L) R.string.cmd_ans_days_one else R.string.cmd_ans_days

@StringRes
private fun Calc.Error.messageRes(): Int = when (this) {
    // Not reachable from the prompt: CommandParser refuses an empty
    // remainder before this is called. Present because the enum is public and
    // this map is total, not because a path leads here.
    Calc.Error.EMPTY -> R.string.cmd_err_calc_empty
    Calc.Error.UNKNOWN_CHARACTER -> R.string.cmd_err_calc_chars
    Calc.Error.UNBALANCED -> R.string.cmd_err_calc_brackets
    Calc.Error.MISSING_OPERAND -> R.string.cmd_err_calc_operand
    Calc.Error.DIVIDE_BY_ZERO -> R.string.cmd_err_calc_zero
}

@StringRes
private fun Convert.Error.messageRes(): Int = when (this) {
    Convert.Error.UNKNOWN_UNIT -> R.string.cmd_err_conv_unit
    Convert.Error.DIMENSION_MISMATCH -> R.string.cmd_err_conv_dimension
    Convert.Error.NOT_A_NUMBER -> R.string.cmd_err_conv_amount
}

@StringRes
private fun DateMath.Error.messageRes(): Int = when (this) {
    // Same as Calc.Error.EMPTY above: the parser gets there first.
    DateMath.Error.EMPTY -> R.string.cmd_err_days_empty
    DateMath.Error.UNKNOWN_VERB -> R.string.cmd_err_days_verb
    DateMath.Error.MISSING_DATE -> R.string.cmd_err_days_date
    DateMath.Error.MISSING_RANGE -> R.string.cmd_err_days_range
    DateMath.Error.NUMERIC_DATE -> R.string.cmd_err_days_numeric
    DateMath.Error.YEAR_NEEDS_ISO -> R.string.cmd_err_days_year
    DateMath.Error.UNREADABLE_DATE -> R.string.cmd_err_days_unreadable
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
    is ParseError.BadWhen -> R.string.cmd_err_when
    // The same copy days gives for the same date, so a refusal reads the
    // same in both commands.
    is ParseError.RemDate -> error.messageRes()
    is ParseError.RemNeedsTime -> R.string.cmd_err_rem_needs_time
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
    is ParseError.BadWhen -> token
    is ParseError.RemDate -> token
    is ParseError.RemNeedsTime -> date
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
    // Two lines when there is a note, one when there is not. The speech row
    // draws whatever it is handed and has no fixed height, so a second line
    // grows upward inside Bit's own box rather than moving the app list.
    is DispatchResult.Answered -> {
        val answer = context.getString(ackKey, *formatArgs(args))
        val key = noteKey
        if (key == null) answer
        else answer + "\n" + context.getString(key, *formatArgs(noteArgs))
    }
    is DispatchResult.Failed -> context.getString(reasonKey, *formatArgs(args))
    is DispatchResult.Unavailable -> context.getString(reasonKey, *formatArgs(args))
    is DispatchResult.NeedsConfirmation -> context.getString(R.string.cmd_confirm_line, echo)
    DispatchResult.NotACommand -> ""
    // Never shown: the console waits for what it delivers instead.
    is DispatchResult.Deferred -> ""
}

private fun formatArgs(args: List<String>): Array<Any> = (args + "").toTypedArray()

/** `adb logcat -s Molasses.Targets`. Shared with CFG. */
private const val TARGETS_TAG = "Molasses.Targets"

/** `adb logcat -s Molasses.Reminder`. Shared with the scheduler and the chime. */
private const val REMINDER_TAG = "Molasses.Reminder"
