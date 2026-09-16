package dev.molasses.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.debug.DebugSurface
import dev.molasses.engine.TierPolicy
import dev.molasses.sensing.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen that answers the feasibility question.
 *
 * Measured stall latency is the whole point: the illusion only works if the
 * blackout starts within a frame or two of the flick, and no amount of
 * reasoning about `updateViewLayout` substitutes for the distribution of
 * requested-versus-actual armed durations off a real device.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    LifecycleRefresh { vm.refresh() }

    val diag by vm.engineDiagnostics.collectAsStateWithLifecycle()
    val ladder by vm.ladder.collectAsStateWithLifecycle()
    val gateOutcomes by vm.gateOutcomes.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()

    val dateFormat = stringResource(R.string.debug_date_format)
    val fmt = remember(dateFormat) { SimpleDateFormat(dateFormat, Locale.US) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) }
            }
            Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.headlineSmall)
        }

        // ------------------------------------------------------- service
        // First on the screen on purpose. Everything below is meaningless if
        // the service is not actually accepting events, and "Granted" in the
        // permission checklist does not answer that: it reads a user
        // preference string, not a live process.
        item { Text(stringResource(R.string.debug_section_service), style = MaterialTheme.typography.titleSmall) }

        if (diag.stuckStarting) {
            item { Warning(stringResource(R.string.debug_warn_stuck)) }
        }
        diag.startupNote?.let { note -> item { Warning(note) } }
        if (diag.health == ServiceHealth.NEVER_CONNECTED) {
            item { Warning(stringResource(R.string.debug_never_connected)) }
        }
        if (diag.usedTargetFallback) {
            item { Warning(stringResource(R.string.debug_warn_target_fallback)) }
        }

        item {
            Column {
                // Bound and enabled are two different questions and are shown
                // as two rows for exactly that reason.
                MonoRow(R.string.debug_field_bound, diag.health.name)
                MonoRow(R.string.debug_field_enabled, diag.accessibilityEnabled.toString())
                MonoRow(
                    R.string.debug_field_heartbeat,
                    diag.heartbeatAgeMs
                        ?.let { stringResource(R.string.debug_ms_ago, it.toString()) }
                        ?: stringResource(R.string.debug_none),
                )
                MonoRow(
                    R.string.debug_field_scope,
                    diag.appliedPackageNames.joinToString(" ")
                        .ifEmpty { stringResource(R.string.debug_none) },
                )
                MonoRow(
                    R.string.debug_field_open_session,
                    diag.openSessionPkg ?: stringResource(R.string.debug_none),
                )
                MonoRow(
                    R.string.debug_field_cycle_anchor,
                    if (diag.cycleAnchorWallMs == 0L) {
                        stringResource(R.string.debug_none)
                    } else {
                        fmt.format(Date(diag.cycleAnchorWallMs))
                    },
                )
                MonoRow(R.string.debug_field_resets_in, formatDuration(diag.cycleRemainingMs))
                MonoRow(R.string.debug_field_policy, diag.resetPolicy.name)
            }
        }

        // ------------------------------------------------------- events
        item { Text(stringResource(R.string.debug_section_events), style = MaterialTheme.typography.titleSmall) }
        item {
            Column {
                Mono(stringResource(R.string.debug_events_header))
                if (diag.routes.isEmpty()) {
                    Text(
                        stringResource(R.string.debug_events_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                // Everything arriving and nothing routed is the empty-target
                // signature, and it is worth naming rather than leaving the
                // reader to spot a column of zeroes.
                val anyRouted = diag.routes.any { it.second.routed > 0 }
                if (diag.routes.isNotEmpty() && !anyRouted) {
                    Warning(stringResource(R.string.debug_warn_all_ignored))
                }
                for ((pkg, t) in diag.routes) {
                    MonoRow(
                        pkg,
                        stringResource(
                            R.string.debug_events_row,
                            t.scrolled.toString(),
                            (t.windowState + t.windowsChanged).toString(),
                            t.routed.toString(),
                            t.ignored.toString(),
                        ),
                    )
                }
                if (diag.overflowedPackages > 0) {
                    Mono(
                        stringResource(
                            R.string.debug_events_overflow,
                            diag.overflowedPackages.toString(),
                        ),
                    )
                }
            }
        }

        item { Header(R.string.debug_section_latency) }
        item {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.debug_latency_legend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Mono(stringResource(R.string.debug_latency_dump_intro))
                    Mono(stringResource(R.string.debug_dumpsys_cmd))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.debug_latency_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        item { Header(R.string.debug_section_stall_duration) }
        item {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (latency.isEmpty()) {
                        Text(
                            stringResource(R.string.debug_stall_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val overshoots = latency.map { it.overshootMs }.sorted()
                        fun pct(p: Double): Long =
                            overshoots[((overshoots.size - 1) * p).toInt().coerceIn(overshoots.indices)]
                        MonoRow(R.string.debug_field_samples, latency.size.toString())
                        Mono(
                            stringResource(
                                R.string.debug_stall_overshoot,
                                overshoots.first(), pct(0.5), pct(0.9), pct(0.99), overshoots.last(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.debug_overshoot_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        latency.take(20).forEach {
                            Mono(
                                stringResource(
                                    R.string.debug_stall_row,
                                    it.requestedMs, it.actualMs, it.overshootMs, it.release,
                                ),
                            )
                        }
                    }
                }
            }
        }

        item { Header(R.string.debug_section_calibration) }
        item {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    vm.thresholdSets.forEach { t ->
                        val warn = t.calibration == Thresholds.Calibration.UNCALIBRATED
                        Text(
                            if (warn) {
                                stringResource(R.string.debug_calibration_uncalibrated, t.id)
                            } else {
                                stringResource(
                                    R.string.debug_calibration_status, t.id, t.calibration,
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (warn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (warn) {
                            Text(
                                stringResource(R.string.debug_uncalibrated_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Mono(
                            stringResource(
                                R.string.debug_thresholds_band,
                                t.minRms, t.minPeaks, t.minHz, t.maxHz,
                            ),
                        )
                        Mono(
                            stringResource(
                                R.string.debug_thresholds_cv,
                                t.minCv, t.maxCv,
                                t.regularityWindowMs / 1000, t.minCvIntervals,
                            ),
                        )
                        Mono(
                            stringResource(
                                R.string.debug_thresholds_shape,
                                t.minVerticalShare, t.maxPeakMagnitude, t.minTiltDegrees,
                            ),
                        )
                        Mono(
                            stringResource(
                                R.string.debug_thresholds_window, t.windowMs, t.refractoryMs,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.debug_gate_outcomes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (gateOutcomes.isEmpty()) {
                        Mono(stringResource(R.string.debug_none_recorded))
                    } else {
                        gateOutcomes.forEach {
                            Mono(
                                stringResource(
                                    R.string.debug_gate_outcome_row, it.path, it.type, it.count,
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (DebugSurface.ENABLED) {
            item { Header(R.string.debug_section_state_editor) }
            item { StateEditor(targets = ladder.map { it.pkg }, onApply = vm::setAppStateForDebug) }
        }

        item { Header(R.string.debug_section_appstate) }
        if (ladder.isEmpty()) {
            item { Mono(stringResource(R.string.debug_empty)) }
        }
        items(ladder, key = { it.pkg }) { row ->
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.pkg, style = MaterialTheme.typography.bodyLarge)
                    MonoRow(
                        R.string.debug_field_accumulated,
                        stringResource(R.string.debug_value_seconds, row.accumulatedMs / 1000),
                    )
                    MonoRow(
                        R.string.debug_field_tier_index,
                        if (TierPolicy.isTerminal(row.tierIndex)) {
                            stringResource(R.string.debug_tier_terminal, row.tierIndex)
                        } else {
                            row.tierIndex.toString()
                        },
                    )
                    MonoRow(
                        R.string.debug_field_stall,
                        stringResource(
                            R.string.debug_value_ms, TierPolicy.stallMsFor(row.tierIndex),
                        ),
                    )
                    // True time and effective time as two numbers, never one.
                    // A user looking at their own figures should be able to
                    // see what they actually spent and what it is costing.
                    MonoRow(R.string.debug_field_penalty, formatDuration(row.penaltyMs))
                    MonoRow(
                        R.string.debug_field_effective,
                        formatDuration(row.accumulatedMs + row.penaltyMs),
                    )
                    MonoRow(R.string.debug_field_leases_taken, row.leasesTaken.toString())
                    MonoRow(
                        R.string.debug_field_lease_until,
                        stringResource(
                            R.string.debug_value_seconds,
                            row.leaseUntilAccumulatedMs / 1000,
                        ),
                    )
                }
            }
        }

        item { Header(R.string.debug_section_ledger) }
        items(ledger, key = { it.id }) { row ->
            Mono(
                stringResource(
                    R.string.debug_ledger_row,
                    fmt.format(Date(row.wallMs)), row.bootId, row.type,
                    row.pkg.substringAfterLast('.'), row.meta ?: "",
                ),
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.clearLedger() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_clear_ledger))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.resetAllState() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_reset_all))
            }
        }
    }
}

/**
 * Debug builds only. Sets accumulated time and tier index for one package so
 * the stall tiers can be exercised without walking off a gate first. More
 * useful in practice than the gate bypass, because it reaches the tier under
 * test directly.
 */
@Composable
private fun StateEditor(targets: List<String>, onApply: (String, Long, Int) -> Unit) {
    var pkg by remember(targets) { mutableStateOf(targets.firstOrNull().orEmpty()) }
    var minutes by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.debug_state_editor_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                singleLine = true,
                label = { Text(stringResource(R.string.debug_state_editor_pkg)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.debug_state_editor_minutes)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tier,
                onValueChange = { tier = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.debug_state_editor_tier)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val ms = (minutes.toLongOrNull() ?: 0L) * 60_000
                    val t = tier.toIntOrNull() ?: TierPolicy.indexFor(ms)
                    if (pkg.isNotBlank()) onApply(pkg, ms, t)
                },
                enabled = pkg.isNotBlank() && (minutes.isNotBlank() || tier.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_state_editor_apply))
            }
        }
    }
}

@Composable
private fun Header(@StringRes text: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A label and a value in a fixed-width dump.
 *
 * The padding is applied here rather than baked into the string resource
 * because aapt collapses runs of whitespace inside a resource unless the whole
 * value is wrapped in quotes. Alignment is presentation anyway.
 */
@Composable
private fun MonoRow(@StringRes label: Int, value: String) {
    Mono(stringResource(label).padEnd(LABEL_WIDTH) + value)
}

private const val LABEL_WIDTH = 14

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * A field row whose label is dynamic, such as a package name.
 *
 * The @StringRes overload stays the default for fixed labels; this one exists
 * only because the event table's labels are the packages themselves.
 */
@Composable
private fun MonoRow(label: String, value: String) {
    Mono(label.padEnd(LABEL_WIDTH) + value)
}

/** Something is wrong and the reader should not have to infer it. */
@Composable
private fun Warning(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Coarse on purpose: this is read at a glance, not measured. */
private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
