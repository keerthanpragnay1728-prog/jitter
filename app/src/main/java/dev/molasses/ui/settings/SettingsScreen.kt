package dev.molasses.ui.settings

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.FrictionSummary
import dev.molasses.core.friction.HorizonPolicy
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.command.CommandRegistry
import dev.molasses.core.session.TargetScope
import dev.molasses.core.settings.CfgAccordion
import dev.molasses.core.settings.CfgAccordion.Section
import dev.molasses.core.settings.CfgRowKey
import dev.molasses.core.settings.TargetGrouping
import dev.molasses.core.settings.UntrackCoolingOff
import dev.molasses.core.command.CommandRender
import dev.molasses.core.launch.QuickLaunch
import dev.molasses.core.lock.LockLadder
import dev.molasses.core.lock.PrefixLock
import dev.molasses.core.lock.TargetLock
import dev.molasses.core.lock.LockRequest
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.core.diag.ServiceHealthPolicy
import dev.molasses.core.safety.SensitivePackages
import dev.molasses.core.ui.AlphaIndex
import dev.molasses.core.ui.FontScale
import dev.molasses.data.repo.InstalledApp
import kotlinx.coroutines.launch

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
    onOpenDebug: () -> Unit,
    onOpenOnboarding: () -> Unit,
    openSection: CfgAccordion.Section?,
    onSectionOpened: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    LifecycleRefresh { vm.refresh() }

    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val launchable by vm.launchable.collectAsStateWithLifecycle()
    val quickLaunch by vm.quickLaunch.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val trackingNothing by vm.trackingNothing.collectAsStateWithLifecycle()
    val gateMode by vm.gateMode.collectAsStateWithLifecycle()
    val sensitivePrefixes by vm.sensitivePrefixes.collectAsStateWithLifecycle()
    val prefixRefusals by vm.prefixRefusals.collectAsStateWithLifecycle()
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

    // A deep link, from the first-run flow's route to TARGETS. Applied once
    // and handed back, so it is not re-applied over the user's own taps.
    LaunchedEffect(openSection) {
        openSection?.let {
            accordion = CfgAccordion.open(accordion, it)
            onSectionOpened()
        }
    }

    // The scrubber. One app open at a time: eight steps and a confirm button
    // per row, across eighty apps, is a wall.
    val locks by vm.locks.collectAsStateWithLifecycle()
    var scrubbing by rememberSaveable { mutableStateOf<String?>(null) }
    // Saved as the duration the bar points at, not its position on the
    // ladder. Saved state can outlive the process, and a position restored
    // after the rungs change would name a different duration than the one
    // the user moved the bar to.
    var stepMs by rememberSaveable { mutableLongStateOf(LockLadder.MIN_MS) }
    val stepIndex = LockLadder.indexOf(LockLadder.snap(stepMs))
    // The duration awaiting a second, deliberate press. Cleared by anything
    // else the user does, exactly as the command prompt clears its own.
    var awaitingConfirm by rememberSaveable { mutableStateOf<Long?>(null) }
    // The untrack cooling-off in progress, or null. Declared here, above
    // buildCfgRows, because the target rows' toggle writes it from inside
    // that builder and the panel at the bottom of this function reads it.
    // remember and not rememberSaveable, on purpose: a rotation or a process
    // death must forget it, which abandons it with the app still tracked.
    var coolingOff by remember { mutableStateOf<UntrackCoolingOff.State?>(null) }
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

    /**
     * The target list, grouped and ordered.
     *
     * Derived from the filtered list rather than the installed one, which is
     * the only correct order: headers describe what is on screen, and a
     * TRACKED header over a run the search box emptied is exactly the lie
     * every other rule in `TargetGrouping` avoids.
     */
    val groupedTargets = remember(shownApps, targets, horizons) {
        TargetGrouping.rows(
            apps = shownApps.map { TargetGrouping.Entry(it.pkg, it.label) },
            tracked = targets.toSet(),
        ) { pkg ->
            horizons[pkg]?.horizonMs ?: FrictionCurve.DEFAULT_HORIZON_MS
        }
    }

    // What TARGETS says about itself on its own header.
    //
    // NONE rather than a zero, and a count rather than nothing, because the
    // two states this has to tell apart are "five tracked because nobody has
    // said otherwise" and "none tracked because I said so", and a collapsed
    // section that says neither makes the second one invisible. Resolved here
    // because the row builder below runs outside composition.
    //
    // Counted through TargetScope.gateable, like every other count or
    // empty check on the tracked set: a stored package that is not installed
    // has no row here and gates nothing, so it is not in the number.
    val installedPkgs = remember(installed) { TargetScope.installedOrUnknown(installed.map { it.pkg }) }
    val gateableCount = TargetScope.gateable(targets, installedPkgs).size
    val targetsSuffix = if (trackingNothing || gateableCount == 0) {
        stringResource(R.string.settings_targets_none)
    } else {
        gateableCount.toString()
    }

    // The running order: every row in CFG, whichever section it belongs to,
    // in the order it is drawn. Built once per composition so an app's index
    // is its position in a list rather than a sum over which sections
    // happen to be open. See CfgRow.
    val cfgRows = buildCfgRows {
        item(CfgRowKey.chrome("masthead")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                // DEBUG is an instrument, not a setting.
                //
                // It used to live at the bottom of TRY IT, which was one
                // scroll away while every section was expanded and became two
                // taps and a scroll once they collapsed. That is the wrong
                // distance for the screen a beta tester is going to be asked
                // to read down a phone line.
                //
                // Here rather than pinned as a ninth section, because it is
                // not a group of settings and a section header claiming
                // otherwise would be the screen lying about its own shape. The
                // stall preview stays in TRY IT: that one genuinely is a
                // setting you exercise.
                Text(
                    stringResource(R.string.settings_debug_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onOpenDebug)
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
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
            item(CfgRowKey.body(Section.SETUP, "perm-a11y")) {
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
            item(CfgRowKey.body(Section.SETUP, "service-state")) {
                ChecklistRow(
                    index = 2,
                    title = R.string.settings_service_state_title,
                    subtitle = serviceStateBody(diag.health, permissions.accessibility),
                    // The rule the first-run flow uses too. See
                    // ServiceHealthPolicy.working.
                    satisfied = ServiceHealthPolicy.working(diag.health, permissions.accessibility),
                    onClick = onOpenAccessibility,
                )
            }
            item(CfgRowKey.body(Section.SETUP, "perm-usage")) {
                ChecklistRow(
                    index = 3,
                    title = R.string.settings_perm_usage_title,
                    subtitle = R.string.settings_perm_usage_body,
                    satisfied = permissions.usageAccess,
                    onClick = onOpenUsageAccess,
                )
            }
            item(CfgRowKey.body(Section.SETUP, "perm-activity")) {
                ChecklistRow(
                    index = 4,
                    title = R.string.settings_perm_activity_title,
                    subtitle = R.string.settings_perm_activity_body,
                    satisfied = permissions.activityRecognition,
                    onClick = onRequestActivityRecognition,
                )
            }
            // Re-opens the first-run flow on this screen. Never marked done, so
            // it stays tappable: ChecklistRow disables a satisfied row.
            item(CfgRowKey.body(Section.SETUP, "onboarding")) {
                ChecklistRow(
                    index = 5,
                    title = R.string.settings_onboarding_title,
                    subtitle = R.string.settings_onboarding_body,
                    satisfied = false,
                    onClick = onOpenOnboarding,
                )
            }
        }

        section(
            state = accordion,
            section = Section.LADDER,
            title = R.string.settings_section_ladder,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.LADDER) },
        ) {
            item(CfgRowKey.body(Section.LADDER, "rows")) { LadderRows() }
        }

        section(
            state = accordion,
            section = Section.TARGETS,
            title = R.string.settings_section_targets,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.TARGETS) },
            suffix = targetsSuffix,
        ) {
            item(CfgRowKey.body(Section.TARGETS, "hint")) {
                Text(
                    stringResource(R.string.settings_targets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // Once, here, rather than under all nine rows. The consequence has to
            // be stated where the control is, and the control is per app.
            item(CfgRowKey.body(Section.TARGETS, "horizon")) {
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
            item(CfgRowKey.body(Section.TARGETS, "search")) {
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
            item(CfgRowKey.body(Section.TARGETS, "count")) {
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
            items(
                groupedTargets,
                // Headers need keys as much as apps do: without one, a
                // recomposition that changes the grouping can reuse a header
                // slot for a different header.
                //
                // Built by CfgRowKey rather than here. The alphabet rail has
                // to find an app's row by key in order to scroll to it, and a
                // key written in one place and looked up in another is a
                // mismatch nothing reports: indexOfFirst returns -1, the rail
                // declines, and one letter quietly does nothing.
                key = { row -> CfgRowKey.of(row) },
            ) { row ->
                val app = when (row) {
                    is TargetGrouping.Row.App -> row.entry
                    TargetGrouping.Row.TrackedHeader -> {
                        GroupHeader(stringResource(R.string.settings_group_tracked))
                        return@items
                    }
                    TargetGrouping.Row.UntrackedHeader -> {
                        GroupHeader(stringResource(R.string.settings_group_untracked))
                        return@items
                    }
                    is TargetGrouping.Row.HorizonHeader -> {
                        GroupHeader(
                            stringResource(
                                R.string.settings_group_horizon_fmt,
                                CommandRender.duration(row.horizonMs),
                            ),
                        )
                        return@items
                    }
                }
                val remainingMs = vm.lockRemainingMs(app.pkg)
                val locked = remainingMs > 0L
                TargetRow(
                    label = app.label,
                    pkg = app.pkg,
                    tracked = app.pkg in targets,
                    // A locked app cannot be untracked, because unticking it
                    // would take it out of packageNames and stop the lock
                    // being enforced at all. See TargetLock.
                    pinned = TargetLock.isPinned(app.pkg in targets, remainingMs),
                    remainingMs = remainingMs,
                    expanded = scrubbing == app.pkg,
                    stepIndex = stepIndex,
                    awaitingConfirm = if (scrubbing == app.pkg) awaitingConfirm else null,
                    horizon = horizons[app.pkg]
                        ?: HorizonPolicy.State(FrictionCurve.DEFAULT_HORIZON_MS),
                    cycleRemainingMs = diag.cycleRemainingMs.takeIf { it > 0L },
                    horizonAwaitingConfirm =
                        horizonConfirm?.takeIf { it.first == app.pkg }?.second,
                    onToggleTarget = {
                        // ON applies at once. OFF waits out the cooling-off,
                        // and a locked app never starts one (its toggle is
                        // held anyway). See UntrackCoolingOff.
                        if (app.pkg in targets) {
                            coolingOff = UntrackCoolingOff.start(
                                pkg = app.pkg,
                                label = app.label,
                                tracked = true,
                                lockRemainingMs = remainingMs,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                            )
                        } else {
                            vm.toggleTarget(app.pkg)
                        }
                    },
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
                        stepMs = LockLadder.durationAt(
                            LockLadder.STEPS_MS.indexOfFirst { it > remainingMs }.coerceAtLeast(0),
                        )
                        awaitingConfirm = null
                    },
                    onStep = {
                        stepMs = LockLadder.durationAt(it)
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
                item(CfgRowKey.body(Section.TARGETS, "no-match")) {
                    Text(
                        stringResource(R.string.settings_targets_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (installed.isEmpty()) {
                item(CfgRowKey.body(Section.TARGETS, "empty")) {
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
            section = Section.QUICK,
            title = R.string.settings_section_quick,
            onToggle = { accordion = CfgAccordion.toggle(accordion, Section.QUICK) },
        ) {
            item(CfgRowKey.body(Section.QUICK, "editor")) {
                QuickLaunchEditor(
                    selection = quickLaunch,
                    apps = launchable,
                    onAdd = vm::addQuickLaunch,
                    onSwap = vm::swapQuickLaunch,
                    onRemove = vm::removeQuickLaunch,
                )
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
            item(CfgRowKey.body(Section.GATE, "unavoidable")) {
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
            item(CfgRowKey.body(Section.GATE, "mode")) {
                Column {
                    Text(
                        stringResource(R.string.settings_gate_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            R.string.settings_gate_mode_body,
                            (FrictionCurve.DEFAULT_HORIZON_MS / 60_000L).toString(),
                        ),
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
            item(CfgRowKey.body(Section.APPEARANCE, "font-scale")) {
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
            item(CfgRowKey.body(Section.SAFETY, "body")) {
                Text(
                    stringResource(R.string.settings_safety_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            item(CfgRowKey.body(Section.SAFETY, "pause")) {
                PauseControl(
                    remainingMs = pauseRemainingMs,
                    onPause = { vm.setPaused(true) },
                    onResume = { vm.setPaused(false) },
                )
            }
            item(CfgRowKey.body(Section.SAFETY, "disable")) {
                DisableControl(onDisable = { vm.requestDisable() })
            }
            item(CfgRowKey.body(Section.SAFETY, "prefixes")) {
                SensitivePrefixEditor(
                    userPrefixes = sensitivePrefixes,
                    refusals = prefixRefusals,
                    lockRemainingMs = vm::lockRemainingMs,
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
            item(CfgRowKey.body(Section.TRY, "stall")) {
                Button(
                    onClick = { vm.requestStallPreview() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_test_stall))
                }
            }
            item(CfgRowKey.body(Section.TRY, "debug")) {
                OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_debug))
                }
            }
        }
    }

    // The letters the rail offers, and the apps it indexes.
    //
    // Both derived from the grouped rows rather than the installed list, for
    // the same reason the group headers are: the rail describes what is on
    // screen. A letter left standing for an app the search box filtered away
    // is a letter that scrolls nowhere, which teaches the user that the rail
    // does not work.
    val railApps = remember(groupedTargets) { TargetGrouping.appsOf(groupedTargets) }
    val railLabels = remember(railApps) { railApps.map { it.entry.label } }
    val letters = remember(railLabels) { AlphaIndex.lettersOf(railLabels) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 48.dp,
                bottom = 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cfgRows, key = { it.key }) { it.content() }
        }

        // Only while the list it indexes is on screen. A rail down the side
        // of eight collapsed headers is a control for a list that is not
        // there.
        //
        // And only when there is more than one letter: a rail with a single
        // entry is a button that scrolls to where the list already is.
        if (CfgAccordion.isOpen(accordion, Section.TARGETS) && letters.size > 1) {
            AlphaRail(
                letters = letters,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { letter ->
                // The three lines the flattening was for. Find the first app
                // under the letter, build the key the way the renderer built
                // it, and take its position in the very list that was handed
                // to items().
                val pkg = AlphaIndex.firstIndexOf(railLabels, letter)
                    ?.let { railApps[it].entry.pkg }
                    ?: return@AlphaRail
                val key = CfgRowKey.app(pkg)
                val index = cfgRows.indexOfFirst { it.key == key }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
        }

        // Last, so it covers the list and the rail.
        coolingOff?.let { state ->
            val lastTarget = TargetScope.isLastGateable(state.pkg, targets, installedPkgs)
            // Once per panel, and again only if the verdict changes, so the
            // log shows what the panel was drawn from.
            LaunchedEffect(state, lastTarget) {
                Log.i(
                    TARGETS_TAG,
                    "untrack panel for ${state.pkg}: tracked=$targets " +
                        "installedLoaded=${installedPkgs != null} " +
                        "installedTracked=${TargetScope.gateable(targets, installedPkgs).size} " +
                        "isLast=$lastTarget",
                )
            }
            UntrackCoolingOffPanel(
                state = state,
                // From the live sets, so it stays true to the store if they
                // change while the countdown runs. See TargetScope.gateable.
                lastTarget = lastTarget,
                onConfirmRemove = {
                    coolingOff = null
                    vm.untrackTarget(state.pkg)
                },
                onKeepTracking = { coolingOff = null },
                onLeave = { how -> coolingOff = UntrackCoolingOff.onLeave(coolingOff, how) },
            )
        }
    }
}

/**
 * The A-Z strip down the right margin.
 *
 * ## Tap and drag, because they are different gestures for the same thing
 * A tap is how someone who knows the app's name gets to it. A drag is how
 * someone who does not scrubs for it, and it is the gesture that makes a rail
 * feel like a rail rather than twenty six small buttons. `AlphaIndex.letterAt`
 * maps the finger's position to a letter, and returns null once the finger
 * leaves the strip vertically, which is what stops a slide off the top from
 * dragging the list to A.
 *
 * ## Why the height is measured rather than computed
 * The strip is as tall as its letters, which depends on the type scale, which
 * the user can change. Dividing by a measured height keeps the drag honest at
 * every font size; dividing by an assumed one would put the finger a letter
 * or two off at the extremes, which is exactly where a rail is used.
 *
 * Deliberately quiet: outline colour, the smallest label style, no background
 * and no selection highlight. It sits over a list it must not compete with.
 */
@Composable
private fun AlphaRail(
    letters: List<Char>,
    modifier: Modifier = Modifier,
    onLetter: (Char) -> Unit,
) {
    var railHeight by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .width(28.dp)
            .onSizeChanged { railHeight = it.height }
            .pointerInput(letters, railHeight) {
                detectVerticalDragGestures { change, _ ->
                    if (railHeight > 0) {
                        AlphaIndex
                            .letterAt(change.position.y / railHeight, letters)
                            ?.let(onLetter)
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        for (letter in letters) {
            Text(
                letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clickable { onLetter(letter) }
                    .padding(vertical = 1.dp, horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun LadderRows() {
    // The real model, not a ladder. Every figure is computed from
    // FrictionCurve at the default horizon, so this text moves when the
    // curve does. The fixed 5/10/15/20 ladder it replaces had stopped
    // describing the app long before it was taken down.
    val summary = remember { FrictionSummary.of(FrictionCurve.DEFAULT_HORIZON_MS) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        for (line in listOf(
            stringResource(R.string.settings_friction_horizon, summary.horizonMinutes.toString()),
            stringResource(
                R.string.settings_friction_onset,
                summary.onsetMinutes.toString(),
                summary.onsetPercent.toString(),
            ),
            stringResource(
                R.string.settings_friction_ramp,
                summary.firstStallMs.toString(),
                summary.firstProbabilityPercent.toString(),
            ),
            stringResource(
                R.string.settings_friction_ceiling,
                summary.horizonMinutes.toString(),
                summary.ceilingStallMs.toString(),
                summary.ceilingProbabilityPercent.toString(),
            ),
            stringResource(R.string.settings_friction_lease),
        )) {
            Text(line, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.settings_friction_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * One row of CFG, with the identity the rail looks it up by.
 *
 * ## Why the screen is a list of these and not a tree of `item` calls
 * The alphabet rail scrolls to an app by absolute index, and `LazyColumn`
 * counts indices across every `item` and `items` in the whole column. With
 * nine sections each emitting their own, an app's index was a function of
 * which sections happened to be open, computable only by re-running the same
 * conditionals the renderer ran, in the same order, somewhere else. That is
 * two descriptions of one layout, and the second one is wrong the first time
 * a row moves.
 *
 * Flattening makes the index what it should have been all along: a position
 * in a list. `indexOfFirst { it.key == ... }` is the whole lookup, and it
 * cannot disagree with the renderer because it is reading what the renderer
 * was handed.
 *
 * ## The content is a lambda, and that is not a model holding a view
 * Nothing here decides what a row looks like. [CfgRows] collects the same
 * composable bodies the sections already had, unchanged, and the single
 * `items` call invokes them. The list is a running order, not a view model.
 */
private class CfgRow(val key: String, val content: @Composable () -> Unit)

/**
 * Collects [CfgRow]s with the same shape `LazyListScope` offered.
 *
 * `item` and `items` keep their names and their argument order deliberately,
 * so the section bodies moved here are the ones that were already written:
 * the only edit any of them needed was a key, which they should have carried
 * anyway. A `return@items` inside a target row still means what it meant,
 * because the label is the function name and the function is still `items`.
 *
 * ## What this costs, stated rather than reassured about
 * Recycling is unchanged. Every row is still its own row, so `LazyColumn`
 * still composes only what is visible, which is the property the old
 * `LazyListScope` version existed to protect: wrapping eighty target rows in
 * one `item` would give it up, and nothing here does that.
 *
 * What is new is the list itself. It is rebuilt on every recomposition of the
 * screen, which allocates one small object per row in it, offscreen ones
 * included, so roughly ninety with TARGETS open. Each holds a key and an
 * uninvoked lambda; none of them compose anything until `items` reaches them.
 * That is a real cost rather than a free one, and it is accepted because an
 * app's index has to come from somewhere, and the alternative was computing
 * it twice in two places.
 *
 * Nothing here is remembered, deliberately. A row that cached its content
 * would keep yesterday's state when the list under it changed, which is the
 * failure the keys exist to prevent, arriving by a different route.
 */
private class CfgRows {
    val rows = mutableListOf<CfgRow>()

    fun item(key: String, content: @Composable () -> Unit) {
        rows += CfgRow(key, content)
    }

    fun <T> items(list: List<T>, key: (T) -> String, content: @Composable (T) -> Unit) {
        for (element in list) {
            rows += CfgRow(key(element)) { content(element) }
        }
    }
}

private fun buildCfgRows(build: CfgRows.() -> Unit): List<CfgRow> =
    CfgRows().apply(build).rows

/**
 * A section header, and its body when the section is open.
 *
 * Unchanged in behaviour from the `LazyListScope` version it replaces: the
 * body is still simply not emitted when the section is closed, rather than
 * emitted and hidden, so a closed section still costs exactly one row. What
 * changed is only where the rows go.
 */
private fun CfgRows.section(
    state: CfgAccordion.State,
    section: Section,
    @StringRes title: Int,
    onToggle: () -> Unit,
    /**
     * A short count or state, shown after the label and visible while the
     * section is closed. Resolved by the caller, because this builder runs
     * outside composition and `stringResource` cannot be called here.
     */
    suffix: String? = null,
    body: CfgRows.() -> Unit,
) {
    val open = CfgAccordion.isOpen(state, section)
    item(CfgRowKey.section(section)) { SectionHeader(title, suffix, open, onToggle) }
    if (open) body()
}

/**
 * A group break inside the target list. Dimmer and quieter than a section
 * header, because it divides rows rather than topics, and it carries no
 * chevron because there is nothing to collapse.
 *
 * It appears only when it separates something. See `TargetGrouping`: on a
 * fresh install, with nothing tracked, there are no group headers at all.
 */
@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * A section break: a label, a chevron, and space.
 *
 * ## The rule that used to be here
 * There was a hairline under every header, carrying the structure the removed
 * card borders used to. That reasoning was sound while every section was
 * expanded, because a rule between two long blocks of prose is a guide. It
 * stopped being sound the moment sections could collapse: eight closed
 * headers, each with a line under it, read as eight boxes, which is exactly
 * the thing the zero-border rule exists to prevent. The borders came back
 * wearing a different weight.
 *
 * Spacing carries it now. The twenty-dp lead above each header is more
 * separation than a one-dp line was, and it costs nothing that looks like a
 * container.
 *
 * ## The chevron
 * Text, right aligned on the label's own line. An icon asset would be the
 * only thing on this screen not made of type, and it would need a tint that
 * `check-colors.sh` cannot see into.
 *
 * The whole row is the tap target, not the chevron. A one-character hit area
 * at the far edge of the screen is a target nobody reaches on the first try,
 * and the label is what the user is looking at when they decide to open it.
 */
@Composable
private fun SectionHeader(
    @StringRes text: Int,
    suffix: String?,
    open: Boolean,
    onToggle: () -> Unit,
) {
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
        )
        // The section saying what is in it, in the one place that is legible
        // while the section is shut. A user who turned every target off will
        // leave TARGETS collapsed, and anything written inside it would be
        // invisible in exactly that state.
        if (suffix != null) {
            Text(
                stringResource(R.string.settings_section_suffix_fmt, suffix.uppercase()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            CfgAccordion.chevron(open),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(8.dp))
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
                    stringResource(R.string.setup_mark_done)
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
 * The console's quick-launch rows: up to [QuickLaunch.MAX_SLOTS], any
 * launchable app or one of the built-in rows, in the order added.
 *
 * Search, not a list of every app. The candidates appear only for a typed
 * query, a screen of matches at most, because a device has hundreds of
 * launchable apps and a scroll through all of them to find one is the
 * interface this app exists to replace. The built-ins that are not already
 * rows are always offered, since there are only five.
 *
 * An app that has been uninstalled is not shown as a row here either. It is
 * pruned from the stored list by the next edit. See [QuickLaunch].
 *
 * Full, the picker is not offered for an add, which the store would refuse,
 * but each row carries [swap]: tap it and the same picker opens to replace
 * that row in its slot ([QuickLaunch.swapped]). So the picker is never hidden
 * without a route to it.
 */
@Composable
private fun QuickLaunchEditor(
    selection: QuickLaunch.Selection,
    apps: List<InstalledApp>,
    onAdd: (QuickLaunch.Entry) -> Unit,
    onSwap: (old: QuickLaunch.Entry, new: QuickLaunch.Entry) -> Unit,
    onRemove: (QuickLaunch.Entry) -> Unit,
) {
    val labels = remember(apps) { apps.associate { it.pkg to it.label } }
    val rows = QuickLaunch.visible(selection) { it in labels }
    val full = rows.size >= QuickLaunch.MAX_SLOTS
    var query by rememberSaveable { mutableStateOf("") }
    // The row being replaced, by token so it survives rotation. Only a row
    // that is still drawn counts; anything else reads as no swap in hand.
    var swappingToken by rememberSaveable { mutableStateOf<String?>(null) }
    val swapping = swappingToken?.let(QuickLaunch::decode)?.takeIf { it in rows }

    // Every pick goes through here: a swap when one is in hand, else an add.
    fun pick(entry: QuickLaunch.Entry) {
        val old = swapping
        if (old != null) onSwap(old, entry) else onAdd(entry)
        swappingToken = null
        query = ""
    }

    @Composable
    fun labelFor(entry: QuickLaunch.Entry): String = when (entry) {
        is QuickLaunch.Entry.App -> labels[entry.pkg] ?: entry.pkg
        is QuickLaunch.Entry.Row -> stringResource(entry.builtIn.labelRes())
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_quick_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_quick_count_fmt, rows.size.toString(), QuickLaunch.MAX_SLOTS.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.settings_quick_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        rows.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(
                    labelFor(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                // Full, the picker is reached through a swap on each row,
                // so it is never hidden without a route to it.
                if (full) {
                    Text(
                        stringResource(
                            if (swapping == entry) R.string.settings_quick_swap_cancel else R.string.settings_quick_swap,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { swappingToken = if (swapping == entry) null else entry.token }
                            .padding(end = 12.dp),
                    )
                }
                Text(
                    stringResource(R.string.settings_quick_remove),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable { onRemove(entry) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (full && swapping == null) {
            Text(
                stringResource(R.string.settings_quick_full),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            if (swapping != null) {
                Text(
                    stringResource(R.string.settings_quick_swapping_fmt, labelFor(swapping)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val builtIns = QuickLaunch.BuiltIn.entries.map { QuickLaunch.Entry.Row(it) }.filter { it !in rows }
            builtIns.forEach { entry ->
                Text(
                    labelFor(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { pick(entry) }.padding(vertical = 6.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_quick_search)) },
            )
            val q = query.trim()
            if (q.isNotEmpty()) {
                val matches = apps.asSequence()
                    .filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
                    .map { QuickLaunch.Entry.App(it.pkg) }
                    .filter { it !in rows }
                    .take(QUICK_LAUNCH_MATCHES)
                    .toList()
                if (matches.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_targets_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                matches.forEach { entry ->
                    Text(
                        labelFor(entry),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pick(entry) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** One screen of search matches. More than this is a list to scroll, not a search. */
private const val QUICK_LAUNCH_MATCHES = 12

/** `adb logcat -s Molasses.Targets`: every count and last check on the tracked set. */
private const val TARGETS_TAG = "Molasses.Targets"

/**
 * The bracketed lowercase label a built-in row has on the console, reused
 * here so the picker names each row the way the console shows it.
 */
@StringRes
fun QuickLaunch.BuiltIn.labelRes(): Int = when (this) {
    QuickLaunch.BuiltIn.PHONE -> R.string.launcher_fav_phone
    QuickLaunch.BuiltIn.MESSAGES -> R.string.launcher_fav_messages
    QuickLaunch.BuiltIn.CALENDAR -> R.string.launcher_fav_calendar
    QuickLaunch.BuiltIn.CALCULATOR -> R.string.launcher_fav_calculator
    QuickLaunch.BuiltIn.CLOCK -> R.string.launcher_fav_clock
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
    refusals: List<PrefixLock.Refusal>,
    lockRemainingMs: (String) -> Long,
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
        // What the last save dropped, in the locked target row's own style:
        // the package dim, the standing lock's remainder beside it. A prefix
        // that vanished from the field with nothing said would read as a
        // broken save. See PrefixLock for why it was dropped.
        if (refusals.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_safety_prefix_refused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            refusals.forEach { refusal ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.settings_safety_prefix_refused_row,
                            refusal.prefix,
                            refusal.lockedPkg,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    val remainingMs = lockRemainingMs(refusal.lockedPkg)
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
            }
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
private fun serviceStateBody(health: ServiceHealth, enabled: Boolean): Int = when {
    // The diagnostics outlive an unbind, so a service switched off can still
    // read HEALTHY for a while. Say what the row's tick is actually about.
    !enabled && health != ServiceHealth.NEVER_CONNECTED -> R.string.settings_service_switched_off
    else -> serviceHealthBody(health)
}

private fun serviceHealthBody(health: ServiceHealth): Int = when (health) {
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
 *
 * ## Two tap targets, on purpose
 * The row opens the lock scrubber. The toggle at its end tracks the app. They
 * are separate because they are separate: with one hit area, every attempt to
 * start tracking an app would also unroll a duration slider underneath it,
 * and arming a lock on an app you were only trying to tick is the one mistake
 * on this screen that cannot be undone.
 */
@Composable
private fun TargetRow(
    label: String,
    pkg: String,
    tracked: Boolean,
    /** Tracked with a lock standing, so the toggle is held. See [TargetLock]. */
    pinned: Boolean,
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
            // The label alone. The package used to sit under it on every one
            // of eighty rows, which turned a list of apps a user recognises
            // into a column of reverse-DNS they have to read past. It is not
            // deleted, it has moved to the expanded row, because two apps can
            // share a label and then it is the only thing that tells them
            // apart. Needed rarely, so shown rarely.
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            if (remainingMs > 0L) {
                Text(
                    stringResource(
                        R.string.settings_lock_remaining_fmt,
                        CommandRender.duration(remainingMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // A text toggle rather than a Checkbox, and its own tap target
            // rather than the row's. The row opens the lock scrubber, so a
            // single hit area would mean every attempt to track an app also
            // unrolled a duration slider underneath it.
            //
            // It reads its own state rather than showing an action: [ON]
            // means this app is tracked, not "press to turn on". That is the
            // ambiguity a checkbox does not have and a button does, and the
            // reason the colour carries it too.
            Text(
                stringResource(
                    if (tracked) R.string.settings_target_on else R.string.settings_target_off,
                ),
                style = MaterialTheme.typography.bodyLarge,
                // Three states in two colours, and the third one is why the
                // remaining time to the left of this is load bearing rather
                // than decorative: primary is tracked, outline is not, and
                // secondary is tracked and held. A held toggle that looked
                // identical to a live one would be a control that ignores
                // taps, which reads as a broken screen.
                color = when {
                    pinned -> MaterialTheme.colorScheme.secondary
                    tracked -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                modifier = Modifier
                    .then(
                        if (pinned) Modifier else Modifier.clickable(onClick = onToggleTarget),
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
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
                // Where the package went. Rendered raw rather than through a
                // resource, because a package name is data in the same way
                // the label is: there is nothing here to translate and a
                // format string wrapping a bare %1$s would be a resource that
                // says nothing.
                Text(
                    pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // Why the toggle above is dim and does nothing. The colour
                // says the state and this says the reason, and the row's own
                // tap is what opens it, so a user who pressed the toggle and
                // got nothing finds the answer with the gesture they were
                // already going to try.
                if (pinned) {
                    Text(
                        stringResource(R.string.settings_target_pinned),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
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
