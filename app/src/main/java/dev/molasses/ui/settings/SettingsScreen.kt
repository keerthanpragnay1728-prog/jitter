package dev.molasses.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.HorizonPolicy
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.command.CommandRegistry
import dev.molasses.core.settings.CfgAccordion
import dev.molasses.core.settings.CfgAccordion.Section
import dev.molasses.core.command.CommandRender
import dev.molasses.core.lock.LockLadder
import dev.molasses.core.lock.LockRequest
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.ui.theme.PhosphorDivider
import dev.molasses.core.safety.SensitivePackages
import dev.molasses.core.ui.FontScale
import dev.molasses.engine.TierPolicy

/**
 * Onboarding as an ordered checklist with live state, because the two
 * permissions that matter cannot be requested from code. They are
 * Settings-screen toggles, and a user who bounces off a system screen without
 * completing it must come back to a display that says so rather than to a
 * silently broken app.
 *
 * All copy comes from `res/values/strings.xml`.
 */
@Composable
fun SettingsScreen(
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenDebug: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    LifecycleRefresh { vm.refresh() }

    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val policy by vm.resetPolicy.collectAsStateWithLifecycle()
    val gateMode by vm.gateMode.collectAsStateWithLifecycle()
    val sensitivePrefixes by vm.sensitivePrefixes.collectAsStateWithLifecycle()
    val pauseRemainingMs by vm.pauseRemainingMs.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val diag by vm.engineDiagnostics.collectAsStateWithLifecycle()
    val horizons by vm.horizons.collectAsStateWithLifecycle()

    /**
     * The package and value of a widen that has been echoed once.
     *
     * Hoisted rather than held per row, for the same reason the lock's
     * confirmation is: a LazyColumn recycles rows, and a confirmation state
     * that rode on a recycled row could be inherited by a different app.
     * Only one can be outstanding, which is also the honest model: confirming
     * is about the press you just made.
     */
    var horizonConfirm by remember { mutableStateOf<Pair<String, Long>?>(null) }

    var appFilter by rememberSaveable { mutableStateOf("") }

    /**
     * Which section is expanded.
     *
     * `remember` and deliberately not `rememberSaveable`. CFG opens the same
     * way every time: setup expanded, everything else closed. Which drawer
     * someone had open while changing a setting is a reading position rather
     * than a preference, and it is the one thing on this screen nobody asked
     * to have remembered. `CfgAccordionTest` pins what `initial` contains, so
     * a later persistence has to disagree with a test.
     */
    var accordion by remember { mutableStateOf(CfgAccordion.initial()) }

    // The scrubber. One app open at a time: eight steps and a confirm button
    // per row, across eighty apps, is a wall.
    val locks by vm.locks.collectAsStateWithLifecycle()
    var scrubbing by rememberSaveable { mutableStateOf<String?>(null) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    // The duration awaiting a second, deliberate press. Cleared by anything
    // else the user does, exactly as the command prompt clears its own.
    var awaitingConfirm by rememberSaveable { mutableStateOf<Long?>(null) }
    // Selected targets always stay visible, even when they do not match the
    // filter. Otherwise typing a name silently hides what is already ticked
    // and the list reads as though the selection was lost.
    val shownApps = remember(appFilter, installed, targets) {
        val q = appFilter.trim()
        if (q.isEmpty()) {
            installed
        } else {
            installed.filter {
                it.pkg in targets ||
                    it.label.contains(q, ignoreCase = true) ||
                    it.pkg.contains(q, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        section(
            state = accordion,
            section = Section.SETUP,
            title = R.string.settings_section_setup,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.SETUP) },
        ) {
            item {
                ChecklistRow(
                    index = 1,
                    title = R.string.settings_perm_a11y_title,
                    subtitle = R.string.settings_perm_a11y_body,
                    satisfied = permissions.accessibility,
                    onClick = onOpenAccessibility,
                )
            }
            // Distinct from the checklist row above, and deliberately so. That
            // row reads Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, which is a
            // user preference string: it says the box is ticked, not that the
            // process is alive. A service that was revoked, crashed, or is stuck
            // before it accepts events reads as fully granted there while
            // producing no friction at all.
            item {
                ChecklistRow(
                    index = 2,
                    title = R.string.settings_service_state_title,
                    subtitle = serviceStateBody(diag.health),
                    satisfied = diag.health.acceptingEvents,
                    onClick = onOpenAccessibility,
                )
            }
            item {
                ChecklistRow(
                    index = 3,
                    title = R.string.settings_perm_usage_title,
                    subtitle = R.string.settings_perm_usage_body,
                    satisfied = permissions.usageAccess,
                    onClick = onOpenUsageAccess,
                )
            }
            item {
                ChecklistRow(
                    index = 4,
                    title = R.string.settings_perm_activity_title,
                    subtitle = R.string.settings_perm_activity_body,
                    satisfied = permissions.activityRecognition,
                    onClick = onRequestActivityRecognition,
                )
            }
            item {
                ChecklistRow(
                    index = 5,
                    title = R.string.settings_perm_notifications_title,
                    subtitle = R.string.settings_perm_notifications_body,
                    satisfied = permissions.notifications,
                    onClick = onRequestNotifications,
                )
            }
        }

        section(
            state = accordion,
            section = Section.LADDER,
            title = R.string.settings_section_ladder,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.LADDER) },
        ) {
            item { LadderRows() }
        }

        section(
            state = accordion,
            section = Section.TARGETS,
            title = R.string.settings_section_targets,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.TARGETS) },
        ) {
            item {
                Text(
                    stringResource(R.string.settings_targets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // Once, here, rather than under all nine rows. The consequence has to
            // be stated where the control is, and the control is per app.
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_horizon_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_horizon_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            item {
                // Eighty plus packages is not a list, it is a haystack. Filters
                // on label and package name both: the label is what a user knows
                // and the package name is what a target actually is, and the two
                // often share no words at all.
                OutlinedTextField(
                    value = appFilter,
                    onValueChange = { appFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_targets_search)) },
                    singleLine = true,
                )
            }
            item {
                Text(
                    stringResource(
                        R.string.settings_targets_count_fmt,
                        shownApps.size.toString(),
                        installed.size.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            items(shownApps, key = { it.pkg }) { app ->
                val remainingMs = vm.lockRemainingMs(app.pkg)
                val locked = remainingMs > 0L
                TargetRow(
                    label = app.label,
                    pkg = app.pkg,
                    tracked = app.pkg in targets,
                    remainingMs = remainingMs,
                    expanded = scrubbing == app.pkg,
                    stepIndex = stepIndex,
                    awaitingConfirm = if (scrubbing == app.pkg) awaitingConfirm else null,
                    horizon = horizons[app.pkg]
                        ?: HorizonPolicy.State(FrictionCurve.DEFAULT_HORIZON_MS),
                    cycleRemainingMs = diag.cycleRemainingMs.takeIf { it > 0L },
                    horizonAwaitingConfirm =
                        horizonConfirm?.takeIf { it.first == app.pkg }?.second,
                    onToggleTarget = { vm.toggleTarget(app.pkg) },
                    onHorizon = { requested ->
                        // Nothing commits until HorizonPolicy says so, and the
                        // screen asks the same function the engine will. A first
                        // press on a widen writes nothing at all: the state is
                        // unchanged, so the next press computes the identical
                        // request and finds it already echoed, which is what
                        // makes "press again" literally the same button.
                        val current = horizons[app.pkg]
                            ?: HorizonPolicy.State(FrictionCurve.DEFAULT_HORIZON_MS)
                        val confirmed = horizonConfirm == app.pkg to requested
                        when (HorizonPolicy.evaluate(current, requested, confirmed)) {
                            is HorizonPolicy.Verdict.Confirm ->
                                horizonConfirm = app.pkg to requested
                            is HorizonPolicy.Verdict.Apply -> {
                                horizonConfirm = null
                                vm.setAppHorizon(app.pkg, requested)
                            }
                            HorizonPolicy.Verdict.None -> horizonConfirm = null
                        }
                    },
                    onExpand = {
                        scrubbing = if (scrubbing == app.pkg) null else app.pkg
                        // A fresh row starts one step above whatever already
                        // stands, because the only thing the scrubber can do to an
                        // existing lock is lengthen it.
                        stepIndex = LockLadder.STEPS_MS.indexOfFirst { it > remainingMs }
                            .coerceAtLeast(0)
                        awaitingConfirm = null
                    },
                    onStep = {
                        stepIndex = it
                        // Moving the bar cancels a pending confirmation. Leaving
                        // it armed would mean the second press arms a duration the
                        // user was not shown.
                        awaitingConfirm = null
                    },
                    onArm = {
                        val chosen = LockLadder.durationAt(stepIndex)
                        // The same evaluation the typed path runs. A second way to
                        // arm a lock that skipped this would make the confirmation
                        // step decorative.
                        when (
                            val verdict = LockRequest.evaluate(
                                durationMs = chosen,
                                standingMs = vm.lockRemainingMs(app.pkg),
                                confirmAboveMs = CommandRegistry.CONFIRM_ABOVE_MS,
                                confirmed = awaitingConfirm == chosen,
                            )
                        ) {
                            is LockRequest.Verdict.Confirm -> awaitingConfirm = verdict.durationMs
                            is LockRequest.Verdict.Arm -> {
                                vm.armLock(app.pkg, verdict.durationMs)
                                awaitingConfirm = null
                                scrubbing = null
                            }
                            // Nothing to do and nothing to say beyond the row,
                            // which already shows the standing remainder.
                            is LockRequest.Verdict.TooShort, LockRequest.Verdict.Invalid ->
                                awaitingConfirm = null
                        }
                    },
                    dim = locked,
                )
            }
            if (installed.isNotEmpty() && shownApps.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_targets_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (installed.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_targets_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        section(
            state = accordion,
            section = Section.POLICY,
            title = R.string.settings_section_policy,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.POLICY) },
        ) {
            item {
                Column {
                    PolicyRow(
                        selected = policy == CycleResetPolicy.ABSTINENCE_6H,
                        title = R.string.settings_policy_abstinence_title,
                        subtitle = R.string.settings_policy_abstinence_body,
                        onSelect = { vm.setResetPolicy(CycleResetPolicy.ABSTINENCE_6H) },
                    )
                    PolicyRow(
                        selected = policy == CycleResetPolicy.FIXED_WINDOW_6H,
                        title = R.string.settings_policy_fixed_title,
                        subtitle = R.string.settings_policy_fixed_body,
                        onSelect = { vm.setResetPolicy(CycleResetPolicy.FIXED_WINDOW_6H) },
                    )
                }
            }
        }

        section(
            state = accordion,
            section = Section.GATE,
            title = R.string.settings_section_gate,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.GATE) },
        ) {
            // Condition of the precedence flip, and of the move to the launch:
            // the promise changed twice, so the copy changed with it. Nobody
            // should discover what a lease does and does not buy by being caught
            // out by it.
            item {
                Column {
                    Text(
                        stringResource(R.string.settings_gate_unavoidable_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_gate_unavoidable_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            // Three choices rather than a switch, and that shape is the point.
            // The walking gate became optional and defaults off; collapsing this
            // to on/off would have taken the typing task away with it, and the
            // typing task is the accessibility requirement, not the preference.
            item {
                Column {
                    Text(
                        stringResource(R.string.settings_gate_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_gate_mode_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    GatePolicy.GateMode.entries.forEach { mode ->
                        PolicyRow(
                            selected = gateMode == mode,
                            title = gateModeLabel(mode),
                            subtitle = gateModeBody(mode),
                            onSelect = { vm.setGateMode(mode) },
                        )
                    }
                }
            }
        }

        section(
            state = accordion,
            section = Section.APPEARANCE,
            title = R.string.settings_section_appearance,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.APPEARANCE) },
        ) {
            item {
                Column {
                    Text(
                        stringResource(R.string.settings_font_scale_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_font_scale_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (scale in FontScale.entries) {
                        PolicyRow(
                            selected = fontScale == scale,
                            title = fontScaleLabel(scale),
                            subtitle = R.string.settings_font_scale_body,
                            onSelect = { vm.setFontScale(scale) },
                        )
                    }
                }
            }
        }

        section(
            state = accordion,
            section = Section.SAFETY,
            title = R.string.settings_section_safety,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.SAFETY) },
        ) {
            item {
                Text(
                    stringResource(R.string.settings_safety_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            item {
                PauseControl(
                    remainingMs = pauseRemainingMs,
                    onPause = { vm.setPaused(true) },
                    onResume = { vm.setPaused(false) },
                )
            }
            item { DisableControl(onDisable = { vm.requestDisable() }) }
            item {
                SensitivePrefixEditor(
                    userPrefixes = sensitivePrefixes,
                    onChange = { vm.setSensitivePrefixes(it) },
                )
            }
        }

        section(
            state = accordion,
            section = Section.TRY,
            title = R.string.settings_section_try,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.TRY) },
        ) {
            item {
                Button(
                    onClick = { vm.requestStallPreview() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_test_stall))
                }
            }
            item {
                OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_debug))
                }
            }
        }
    }
}

@Composable
private fun LadderRows() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column {
            TierPolicy.ladder.forEach { tier ->
                val minutes = tier.entryAtMs / 60_000
                val label = when {
                    tier.index == 0 -> stringResource(R.string.settings_ladder_normal)
                    TierPolicy.isTerminal(tier.index) -> stringResource(
                        R.string.settings_ladder_terminal, minutes, tier.stallMs,
                    )
                    else -> stringResource(R.string.settings_ladder_tier, minutes, tier.stallMs)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.settings_ladder_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * One collapsible section: its header, and its body when it is open.
 *
 * A `LazyListScope` extension rather than a composable, because the body has
 * to stay made of `item` and `items` calls. Wrapping eighty target rows in a
 * single `item` would build the whole list on every recomposition and give up
 * the recycling that makes the list usable at all.
 *
 * The body is simply not emitted when the section is closed, rather than
 * emitted and hidden. A closed section costs one row.
 */
private fun LazyListScope.section(
    state: CfgAccordion.State,
    section: Section,
    @StringRes title: Int,
    onToggle: () -> Unit,
    body: LazyListScope.() -> Unit,
) {
    val open = CfgAccordion.isOpen(state, section)
    item { SectionHeader(title, open, onToggle) }
    if (open) body()
}

/**
 * A section break: a label over a hairline rule, like a guide in a code
 * editor. The rule carries the structure that the removed card borders used
 * to, at a fraction of the visual weight.
 *
 * The chevron is text, right aligned on the label's own line. An icon asset
 * would be the only thing on this screen not made of type, and it would need
 * a tint that `check-colors.sh` cannot see into.
 *
 * The whole row is the tap target, not the chevron. A one-character hit area
 * at the far edge of the screen is a target nobody reaches on the first try,
 * and the label is what the user is looking at when they decide to open it.
 */
@Composable
private fun SectionHeader(@StringRes text: Int, open: Boolean, onToggle: () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            CfgAccordion.chevron(open),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(
        Modifier.padding(top = 4.dp, bottom = 8.dp),
        thickness = 1.dp,
        color = PhosphorDivider,
    )
}

@Composable
private fun ChecklistRow(
    index: Int,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    satisfied: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !satisfied, onClick = onClick),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (satisfied) {
                    stringResource(R.string.settings_checklist_done)
                } else {
                    stringResource(R.string.settings_checklist_step, index)
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (satisfied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun PolicyRow(
    selected: Boolean,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * "Pause friction (15m)" and its countdown.
 *
 * ## Why this is not the same button as the one below it
 * The two solve different problems and the labels say so. This one suspends
 * what Jitter *does*: stalls, gates and every overlay. That is enough for any
 * app that objects to being drawn over, which is most of them, and it costs
 * nothing to use because it ends by itself.
 *
 * It cannot help with Paytm, which blocks on an enabled accessibility service
 * being present at all. Nothing short of disabling the service changes that
 * reading, so the button below exists and is irreversible.
 *
 * Restoring this one after that one shipped was the point: a user at a till
 * wants the fifteen minutes, and should not pay a trip into Android Settings
 * and back for a problem that expires on its own.
 *
 * ## Why the countdown is digits
 * The situation it exists for is standing at a till with a card reader
 * waiting, and a number answers "can I pay yet" in one glance. A progress bar
 * does not.
 *
 * Recomposes from the store rather than from a timer, so the digits step when
 * the state changes rather than once a second. A per-second recomposition of
 * a settings screen is not worth the wakeups, and the user is looking at the
 * payment app, not at this.
 *
 * ## Why the remainder is measured on elapsedRealtime
 * A pause is relief, and relief measured on a wall clock is held open forever
 * by winding the clock back. `PauseWindow` drops the wall clock entirely and
 * expires across a reboot. See CLAUDE.md, "Which clock a deadline is measured
 * on".
 */
@Composable
private fun PauseControl(
    remainingMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val active = remainingMs > 0
    Column(Modifier.fillMaxWidth()) {
        if (active) {
            val totalSeconds = remainingMs / 1000
            Text(
                stringResource(
                    R.string.settings_pause_active,
                    (totalSeconds / 60).toString(),
                    (totalSeconds % 60).toString().padStart(2, '0'),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_pause_resume))
            }
        } else {
            Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_pause_start))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_pause_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * The one way out of Jitter.
 *
 * ## Why the fifteen minute pause above is not enough
 * The pause suppresses overlays. That is enough for GPay and PhonePe, which
 * refuse to run under a window drawn over them, and both complete payments
 * normally with Jitter enabled. It is the button to reach for first, because
 * it ends by itself.
 *
 * Paytm is a different check and the pause cannot reach it. It calls
 * `getEnabledAccessibilityServiceList()` and blocks on the *presence* of any
 * enabled service that is not on its allowlist. It never asks what the
 * service observes, so scoping `packageNames`, holding
 * `canRetrieveWindowContent` false, dropping `flagRetrieveInteractiveWindows`
 * and refusing every overlay are all invisible to it. The only thing that
 * changes what Paytm sees is the service not being enabled.
 *
 * ## Why it has no timer and nothing re-arms it
 * `disableSelf()` cannot be reversed from code, by platform guarantee. That
 * is the mechanism rather than a limitation: a control that could quietly
 * switch friction back on would be a control the user could not trust at a
 * till, and a control that could switch it back on *for* them would be the
 * bypass every other defence here exists to prevent, inverted.
 *
 * So the path back is the permission checklist at the top of this screen,
 * which will read "Not running" the moment this fires.
 */
@Composable
private fun DisableControl(onDisable: () -> Unit) {
    var armed by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_disable_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (armed) onDisable() else armed = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (armed) R.string.settings_disable_confirm
                    else R.string.settings_disable_start,
                ),
            )
        }
        if (armed) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { armed = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_disable_cancel))
            }
        }
    }
}

/**
 * The user's additions to the never-draw-over set.
 *
 * One package prefix per line. The shipped defaults are shown read-only above
 * the field and cannot be removed: letting someone delete `com.phonepe` from
 * this list is not a preference, it is a way to lose money.
 */
@Composable
private fun SensitivePrefixEditor(
    userPrefixes: List<String>,
    onChange: (List<String>) -> Unit,
) {
    // Local draft so a partly typed line is not written to the store on every
    // keystroke, which would also re-resolve the set inside the service.
    var draft by rememberSaveable(userPrefixes) { mutableStateOf(userPrefixes.joinToString("\n")) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_safety_defaults_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            SensitivePackages.DEFAULT_PREFIXES.sorted().joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_safety_extra_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.settings_safety_extra_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_safety_extra_label)) },
            minLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onChange(draft.lines()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_safety_extra_save))
        }
    }
}

/**
 * State to copy, as a plain function resolved at the call site. Keeps the
 * mapping pure and the strings in the resource file, per CLAUDE.md.
 */
@StringRes
private fun gateModeLabel(mode: GatePolicy.GateMode): Int = when (mode) {
    GatePolicy.GateMode.COUNTDOWN -> R.string.settings_gate_mode_countdown
    GatePolicy.GateMode.WALK -> R.string.settings_gate_mode_walk
    GatePolicy.GateMode.TYPING_ONLY -> R.string.settings_gate_mode_type
}

@StringRes
private fun gateModeBody(mode: GatePolicy.GateMode): Int = when (mode) {
    GatePolicy.GateMode.COUNTDOWN -> R.string.settings_gate_mode_countdown_body
    GatePolicy.GateMode.WALK -> R.string.settings_gate_mode_walk_body
    GatePolicy.GateMode.TYPING_ONLY -> R.string.settings_gate_mode_type_body
}

/**
 * Font scale to its label.
 *
 * A plain function returning a @StringRes rather than a `when` inside the
 * composable, per the repo rule: the mapping stays pure and the resource is
 * resolved at the call site.
 */
@StringRes
private fun fontScaleLabel(scale: FontScale): Int = when (scale) {
    FontScale.VERY_SMALL -> R.string.settings_font_very_small
    FontScale.SMALL -> R.string.settings_font_small
    FontScale.MEDIUM -> R.string.settings_font_medium
    FontScale.LARGE -> R.string.settings_font_large
    FontScale.VERY_LARGE -> R.string.settings_font_very_large
}

/** Service health to its one-line explanation. Pure mapping, resolved at the call site. */
@StringRes
private fun serviceStateBody(health: ServiceHealth): Int = when (health) {
    ServiceHealth.HEALTHY -> R.string.settings_service_healthy
    ServiceHealth.CONNECTING -> R.string.settings_service_connecting
    ServiceHealth.STALE -> R.string.settings_service_stale
    ServiceHealth.NEVER_CONNECTED -> R.string.settings_service_never
}

/**
 * `horizon  18m  [ - ]  [ + ]`, with the derived onset under it.
 *
 * ## Why the onset is shown
 * Because it is the consequence, and the horizon is only the input. "Sixty
 * minutes" does not tell anyone that nothing at all happens for the first
 * twenty four, and that is the number people will actually feel. Showing the
 * derivation also makes the one rule visible rather than magic.
 *
 * ## Why steps and not a slider
 * A slider invites tuning a number nobody can feel the difference in. The
 * useful question is what kind of session this app is for, and that has about
 * nine answers. The lock scrubber below is a slider because its steps are
 * geometric and read as a scale; these are close together and read as a list.
 *
 * ## Why the pending line names the time
 * A widen that says only "next cycle" is a promise with no deadline attached,
 * and the whole point of the delay is that it is bounded and visible. The
 * remaining time is null when no cycle is anchored, which is a different
 * thing from a cycle with none left, so that case drops the parenthetical
 * rather than printing a zero.
 */
@Composable
private fun HorizonControl(
    state: HorizonPolicy.State,
    cycleRemainingMs: Long?,
    /** The widen this row has echoed and is waiting on, or null. */
    awaitingConfirm: Long?,
    onHorizon: (Long) -> Unit,
) {
    // Stepping walks from whatever is on screen. After widening to sixty,
    // minus has to come back down from sixty rather than from the eighteen
    // still in force, or the button would appear not to work.
    val target = if (state.hasPending) state.pendingHorizonMs else state.horizonMs

    Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.settings_horizon_fmt,
                    CommandRender.duration(state.horizonMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onHorizon(HorizonPolicy.narrower(target)) }) {
                Text(stringResource(R.string.settings_horizon_narrow))
            }
            TextButton(onClick = { onHorizon(HorizonPolicy.wider(target)) }) {
                Text(stringResource(R.string.settings_horizon_widen))
            }
        }
        Text(
            stringResource(
                R.string.settings_horizon_onset_fmt,
                CommandRender.duration(FrictionCurve.onsetMs(state.horizonMs)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        // The echo sits above the pending line rather than replacing it,
        // because both can be true: a widen already waiting for the rollover
        // and a further one being asked for now.
        if (awaitingConfirm != null) {
            Text(
                stringResource(
                    R.string.settings_horizon_confirm_fmt,
                    CommandRender.duration(state.horizonMs),
                    CommandRender.duration(awaitingConfirm),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.hasPending) {
            Text(
                if (cycleRemainingMs == null) {
                    stringResource(
                        R.string.settings_horizon_pending_unknown_fmt,
                        CommandRender.duration(state.pendingHorizonMs),
                    )
                } else {
                    stringResource(
                        R.string.settings_horizon_pending_fmt,
                        CommandRender.duration(state.pendingHorizonMs),
                        CommandRender.duration(cycleRemainingMs),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * One app in the target list, with its lock scrubber.
 *
 * ## Why the scrubber is here and not on its own screen
 * A lock is a thing you do to an app, and the list of apps is where you are
 * already looking. A separate screen would mean picking the app twice.
 *
 * ## Why a locked row is dim rather than hidden or disabled
 * Hidden would lose the remaining time, which is the only number that matters
 * once a lock is running. Disabled would be a lie: a standing lock can still
 * be lengthened, and the scrubber is how.
 *
 * There is no unlock control, at any depth. That is the point of the feature.
 */
@Composable
private fun TargetRow(
    label: String,
    pkg: String,
    tracked: Boolean,
    remainingMs: Long,
    expanded: Boolean,
    stepIndex: Int,
    /** The duration awaiting a second press, or null. */
    awaitingConfirm: Long?,
    dim: Boolean,
    horizon: HorizonPolicy.State,
    /** Until the cycle resets, or null when none is anchored. */
    cycleRemainingMs: Long?,
    /** The widen this row has echoed and is waiting on, or null. */
    horizonAwaitingConfirm: Long?,
    onToggleTarget: () -> Unit,
    onExpand: () -> Unit,
    onStep: (Int) -> Unit,
    onArm: () -> Unit,
    onHorizon: (Long) -> Unit,
) {
    val labelColor =
        if (dim) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
        ) {
            Checkbox(checked = tracked, onCheckedChange = { onToggleTarget() })
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
                Text(
                    pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (remainingMs > 0L) {
                Text(
                    stringResource(
                        R.string.settings_lock_remaining_fmt,
                        CommandRender.duration(remainingMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        // Only once an app is tracked. An untracked app has no curve, so a
        // horizon for it would be a setting with nothing on the other end.
        if (tracked) {
            HorizonControl(
                state = horizon,
                cycleRemainingMs = cycleRemainingMs,
                awaitingConfirm = horizonAwaitingConfirm,
                onHorizon = onHorizon,
            )
        }

        // if/else rather than an early return: Column is an inline function,
        // so a bare return here would be a non-local return out of the
        // composable from inside its own content lambda.
        if (expanded) {
            val chosen = LockLadder.durationAt(stepIndex)
            Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                Text(
                    stringResource(
                        R.string.settings_lock_scrub_fmt,
                        CommandRender.duration(chosen),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // A discrete slider rather than eight chips: the steps are
                // geometric, so the bar reads as a scale where a row of equal
                // sized buttons would read as a menu of equivalent options.
                Slider(
                    value = stepIndex.toFloat(),
                    onValueChange = { onStep(it.toInt().coerceIn(LockLadder.STEPS_MS.indices)) },
                    valueRange = 0f..(LockLadder.STEPS_MS.size - 1).toFloat(),
                    steps = LockLadder.STEPS_MS.size - 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_lock_no_unlock),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(
                    onClick = onArm,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        if (awaitingConfirm == chosen) {
                            stringResource(
                                R.string.settings_lock_confirm_fmt,
                                CommandRender.duration(chosen),
                            )
                        } else {
                            stringResource(R.string.settings_lock_arm)
                        },
                    )
                }
            }
        }
    }
}
