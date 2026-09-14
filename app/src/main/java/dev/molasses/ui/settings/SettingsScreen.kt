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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.model.CycleResetPolicy
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
    val altChallenge by vm.alternativeChallenge.collectAsStateWithLifecycle()
    val sensitivePrefixes by vm.sensitivePrefixes.collectAsStateWithLifecycle()
    val pauseRemainingMs by vm.pauseRemainingMs.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val diag by vm.engineDiagnostics.collectAsStateWithLifecycle()

    var appFilter by rememberSaveable { mutableStateOf("") }
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

        item { SectionHeader(R.string.settings_section_setup) }
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
        item { SectionHeader(R.string.settings_section_ladder) }
        item { LadderRows() }

        item { SectionHeader(R.string.settings_section_targets) }
        item {
            Text(
                stringResource(R.string.settings_targets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.toggleTarget(app.pkg) },
            ) {
                Checkbox(
                    checked = app.pkg in targets,
                    onCheckedChange = { vm.toggleTarget(app.pkg) },
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        app.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
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

        item { SectionHeader(R.string.settings_section_policy) }
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

        item { SectionHeader(R.string.settings_section_gate) }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_alt_challenge_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_alt_challenge_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Switch(
                    checked = altChallenge,
                    onCheckedChange = { vm.setAlternativeChallenge(it) },
                )
            }
        }

        item { SectionHeader(R.string.settings_section_appearance) }
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

        item { SectionHeader(R.string.settings_section_safety) }
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
        item {
            SensitivePrefixEditor(
                userPrefixes = sensitivePrefixes,
                onChange = { vm.setSensitivePrefixes(it) },
            )
        }

        item { SectionHeader(R.string.settings_section_try) }
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
 * A section break: a label over a hairline rule, like a guide in a code
 * editor. The rule carries the structure that the removed card borders used
 * to, at a fraction of the visual weight.
 */
@Composable
private fun SectionHeader(@StringRes text: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
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
 * "Pause Jitter (15m)" and its countdown.
 *
 * The countdown is minutes and seconds rather than a progress bar because the
 * situation it exists for is standing at a till with a card reader waiting,
 * and a number answers "can I pay yet" in one glance.
 *
 * Recomposes from the store rather than from a timer, so the digits step when
 * the state changes rather than once a second. A per-second recomposition of
 * a settings screen is not worth the wakeups, and the user is looking at the
 * payment app, not at this.
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
