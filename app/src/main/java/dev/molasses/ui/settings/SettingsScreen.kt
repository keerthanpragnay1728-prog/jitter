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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.model.CycleResetPolicy
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
    onOpenOverlay: () -> Unit,
    onTestStall: () -> Unit,
    onOpenDebug: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    LifecycleRefresh { vm.refresh() }

    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val policy by vm.resetPolicy.collectAsStateWithLifecycle()
    val altChallenge by vm.alternativeChallenge.collectAsStateWithLifecycle()

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
        item {
            ChecklistRow(
                index = 2,
                title = R.string.settings_perm_usage_title,
                subtitle = R.string.settings_perm_usage_body,
                satisfied = permissions.usageAccess,
                onClick = onOpenUsageAccess,
            )
        }
        item {
            ChecklistRow(
                index = 3,
                title = R.string.settings_perm_activity_title,
                subtitle = R.string.settings_perm_activity_body,
                satisfied = permissions.activityRecognition,
                onClick = onRequestActivityRecognition,
            )
        }
        item {
            ChecklistRow(
                index = 4,
                title = R.string.settings_perm_notifications_title,
                subtitle = R.string.settings_perm_notifications_body,
                satisfied = permissions.notifications,
                onClick = onRequestNotifications,
            )
        }
        item {
            ChecklistRow(
                index = 5,
                title = R.string.settings_perm_overlay_title,
                subtitle = R.string.settings_perm_overlay_body,
                satisfied = permissions.overlay,
                onClick = onOpenOverlay,
            )
        }

        item { SectionHeader(R.string.settings_section_ladder) }
        item { LadderCard() }

        item { SectionHeader(R.string.settings_section_targets) }
        item {
            Text(
                stringResource(R.string.settings_targets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        items(installed, key = { it.pkg }) { app ->
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

        item { SectionHeader(R.string.settings_section_try) }
        item {
            Button(onClick = onTestStall, modifier = Modifier.fillMaxWidth()) {
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
private fun LadderCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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

@Composable
private fun SectionHeader(@StringRes text: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
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
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !satisfied, onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp),
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
