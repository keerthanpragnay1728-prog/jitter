package dev.molasses.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.engine.TierPolicy

/**
 * Onboarding as an ordered checklist with live state, because the two
 * permissions that matter cannot be requested from code -- they are
 * Settings-screen toggles -- and a user who bounces off a system screen
 * without completing it must come back to a display that says so rather than
 * to a silently broken app.
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
            Text("Molasses", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your chosen apps are never blocked. The longer you use them in a " +
                    "six-hour cycle, the more the phone feels like it is failing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        item { SectionHeader("Setup") }
        item {
            ChecklistRow(
                index = 1,
                title = "Accessibility service",
                subtitle = "Required. Lets Molasses see which app is in front and when you scroll.",
                satisfied = permissions.accessibility,
                onClick = onOpenAccessibility,
            )
        }
        item {
            ChecklistRow(
                index = 2,
                title = "Usage access",
                subtitle = "Required. Rebuilds your time if Molasses is killed mid-session.",
                satisfied = permissions.usageAccess,
                onClick = onOpenUsageAccess,
            )
        }
        item {
            ChecklistRow(
                index = 3,
                title = "Physical activity",
                subtitle = "Preferred. Uses the step sensor for the movement gate; " +
                    "without it the gate falls back to motion analysis.",
                satisfied = permissions.activityRecognition,
                onClick = onRequestActivityRecognition,
            )
        }
        item {
            ChecklistRow(
                index = 4,
                title = "Notifications",
                subtitle = "Optional. Tells you when a cycle resets or a gate is still owed.",
                satisfied = permissions.notifications,
                onClick = onRequestNotifications,
            )
        }
        item {
            ChecklistRow(
                index = 5,
                title = "Draw over other apps",
                subtitle = "Optional. Only needed for the stall preview below.",
                satisfied = permissions.overlay,
                onClick = onOpenOverlay,
            )
        }

        item { SectionHeader("The ladder") }
        item { LadderCard() }

        item { SectionHeader("Target apps") }
        item {
            Text(
                "Scrolling in these apps accumulates time.",
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
                    "No launchable apps listed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        item { SectionHeader("Cycle reset policy") }
        item {
            Column {
                PolicyRow(
                    selected = policy == CycleResetPolicy.ABSTINENCE_6H,
                    title = "After 6 hours away (default)",
                    subtitle = "The cycle resets only after six continuous hours with no " +
                        "time in any target app.",
                    onSelect = { vm.setResetPolicy(CycleResetPolicy.ABSTINENCE_6H) },
                )
                PolicyRow(
                    selected = policy == CycleResetPolicy.FIXED_WINDOW_6H,
                    title = "Every 6 hours",
                    subtitle = "The cycle resets six hours after it started, whether or not " +
                        "you kept using the apps.",
                    onSelect = { vm.setResetPolicy(CycleResetPolicy.FIXED_WINDOW_6H) },
                )
            }
        }

        item { SectionHeader("Movement gate") }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Alternative challenge", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Replaces the physical gate with a 25-second untimed typing task. " +
                            "For anyone who cannot or should not be made to walk.",
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

        item { SectionHeader("Try it") }
        item {
            Button(onClick = onTestStall, modifier = Modifier.fillMaxWidth()) {
                Text("Test Phantom Stall (1 second)")
            }
        }
        item {
            OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                Text("Debug / ledger")
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
                    tier.index == 0 -> "0-5 min, normal"
                    TierPolicy.isTerminal(tier.index) ->
                        "$minutes min+ (terminal), gate every 5 min, ${tier.stallMs} ms stall"
                    else -> "$minutes min, gate then ${tier.stallMs} ms stall"
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Clearing a gate unlocks the next five minutes. It never resets your " +
                    "accumulated time and never shortens the stall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ChecklistRow(
    index: Int,
    title: String,
    subtitle: String,
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
                if (satisfied) "OK" else "$index.",
                style = MaterialTheme.typography.titleMedium,
                color = if (satisfied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
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
    title: String,
    subtitle: String,
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
