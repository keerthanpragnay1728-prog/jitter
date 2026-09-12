package dev.molasses.ui.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.engine.TierPolicy
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

    val ladder by vm.ladder.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text("< Back") }
            }
            Text("Debug", style = MaterialTheme.typography.headlineSmall)
        }

        item { Header("Stall latency (requested vs actual)") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (latency.isEmpty()) {
                        Text(
                            "No STALL_ARMED rows yet. Scroll past five minutes in a target " +
                                "app, clear the gate, then come back.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val overshoots = latency.map { it.overshootMs }.sorted()
                        fun pct(p: Double): Long =
                            overshoots[((overshoots.size - 1) * p).toInt().coerceIn(overshoots.indices)]
                        Mono("samples      ${latency.size}")
                        Mono("overshoot ms min=${overshoots.first()} p50=${pct(0.5)} " +
                            "p90=${pct(0.9)} p99=${pct(0.99)} max=${overshoots.last()}")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Overshoot is actual armed duration minus requested. It bundles " +
                                "the coroutine wake-up, the updateViewLayout round trip, and " +
                                "any panic release. Negative values are early releases.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        latency.take(20).forEach {
                            Mono(
                                "req=${it.requestedMs}ms act=${it.actualMs}ms " +
                                    "d=${it.overshootMs}ms (${it.release})",
                            )
                        }
                    }
                }
            }
        }

        item { Header("AppState per package") }
        if (ladder.isEmpty()) {
            item { Mono("(empty)") }
        }
        items(ladder, key = { it.pkg }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.pkg, style = MaterialTheme.typography.bodyLarge)
                    Mono("accumulated   ${row.accumulatedMs / 1000}s")
                    Mono("tierIndex     ${row.tierIndex}${if (TierPolicy.isTerminal(row.tierIndex)) " (terminal)" else ""}")
                    Mono("stall         ${TierPolicy.stallMsFor(row.tierIndex)}ms")
                    Mono("gatesCleared  ${row.gatesCleared}")
                    Mono("unlockedUntil ${row.tierUnlockedUntilMs / 1000}s")
                }
            }
        }

        item { Header("Ledger (newest first)") }
        items(ledger, key = { it.id }) { row ->
            Mono(
                "${fmt.format(Date(row.wallMs))} b${row.bootId} ${row.type} " +
                    "${row.pkg.substringAfterLast('.')} ${row.meta ?: ""}",
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.clearLedger() }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear ledger")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.resetAllState() }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all state (ladder + ledger)")
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
