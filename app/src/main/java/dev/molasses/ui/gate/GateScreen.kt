package dev.molasses.ui.gate

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.R
import dev.molasses.core.model.GateProgress
import dev.molasses.core.safety.HomeFirst
import dev.molasses.core.safety.OverlayExit
import dev.molasses.debug.DebugSurface
import dev.molasses.debug.DebugSurface.debugBypassGesture
import dev.molasses.core.friction.HorizonReading
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-bleed movement gate. No dismiss button, by design.
 *
 * The live [GateProgress] ring and the failing-check string are not polish.
 * An unlock condition the user cannot see is indistinguishable from a broken
 * app, and "walk until something happens" with no feedback is what makes
 * people uninstall rather than comply.
 *
 * All copy comes from `res/values/strings.xml`. See the header of that file.
 */
@Composable
fun GateScreen(
    /** This app's time against its horizon, read live from the engine. */
    reading: HorizonReading,
    pkg: String,
    progressFlow: StateFlow<GateProgress>,
    alternativeChallenge: Boolean,
    challengePhrase: String,
    onChallengeAnswer: (String) -> Unit,
    /**
     * [ ARCHITECT'S SPACE ]: leave the gate and stay on the launcher. Shown
     * from the first frame, because this gate runs over the launcher after
     * sending its app home and pressing home no longer leaves it. See
     * `OverlayExit`.
     */
    onExit: () -> Unit,
    /**
     * Debug builds only. Wired to a long press on the progress ring, and a
     * no-op in release because [DebugSurface.debugBypassGesture] is a no-op
     * there.
     */
    onDebugBypass: () -> Unit = {},
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    var whyExpanded by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        label = "gateProgress",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Near-opaque rather than fully opaque: the user should be able to
            // tell which app they are being held out of.
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(horizonLabel(reading.terminal), reading.horizonMinutes.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.gate_minutes_used,
                    reading.usedMinutes.toString(),
                    reading.horizonMinutes.toString(),
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            // True time and the penalty stay two numbers, as on the ledger.
            if (reading.penaltyMinutes > 0L) {
                Text(
                    text = stringResource(R.string.gate_penalty, reading.penaltyMinutes.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))

            if (alternativeChallenge) {
                AlternativeChallenge(
                    phrase = challengePhrase,
                    onSubmit = onChallengeAnswer,
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.debugBypassGesture(onDebugBypass),
                ) {
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(140.dp),
                        strokeWidth = 6.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                    )
                    Text(
                        text = stringResource(
                            R.string.gate_progress_percent,
                            (animated * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    // The events count is only consumed by the NEED_MORE_STEPS
                    // string. String.format ignores surplus arguments, so
                    // passing it unconditionally is safe.
                    text = stringResource(reasonRes(progress.reason), progress.events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (progress.reason.isDisqualifying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    textAlign = TextAlign.Center,
                )
                pathRes(progress.path)?.let { res ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            // Always there, whatever the progress or the challenge: a gate
            // can always be left. Asked of OverlayExit so this screen and the
            // invariant's test cannot disagree.
            if (OverlayExit.shown(HomeFirst.Overlay.WALK_GATE, remainingMs = 0L) != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.lock_exit),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onExit() }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                )
            }

            if (DebugSurface.ENABLED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.gate_debug_bypass_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Spacer(Modifier.height(40.dp))
            TextButton(onClick = { whyExpanded = !whyExpanded }) {
                Text(stringResource(R.string.gate_why))
            }
            AnimatedVisibility(visible = whyExpanded) {
                Text(
                    text = whyBody(reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AlternativeChallenge(phrase: String, onSubmit: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.gate_alt_type_this),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = phrase,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            keyboardActions = KeyboardActions(onDone = { onSubmit(typed) }),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(typed) }, enabled = typed.isNotBlank()) {
            Text(stringResource(R.string.gate_alt_continue))
        }
    }
}

/**
 * Which check is currently blocking, as a resource id rather than a string, so
 * the mapping stays a pure function and the lookup happens at the call site.
 */
@StringRes
private fun reasonRes(reason: GateProgress.Reason): Int = when (reason) {
    GateProgress.Reason.WAITING_TO_START -> R.string.gate_reason_waiting
    GateProgress.Reason.NEED_MORE_STEPS -> R.string.gate_reason_need_more_steps
    GateProgress.Reason.CADENCE_TOO_SLOW -> R.string.gate_reason_too_slow
    GateProgress.Reason.CADENCE_TOO_FAST -> R.string.gate_reason_too_fast
    GateProgress.Reason.CADENCE_IRREGULAR -> R.string.gate_reason_irregular
    GateProgress.Reason.TOO_REGULAR -> R.string.gate_reason_too_regular
    GateProgress.Reason.NOT_ENOUGH_MOTION -> R.string.gate_reason_not_enough_motion
    GateProgress.Reason.TOO_VIOLENT -> R.string.gate_reason_too_violent
    GateProgress.Reason.MOTION_NOT_VERTICAL -> R.string.gate_reason_not_vertical
    GateProgress.Reason.PHONE_STATIONARY -> R.string.gate_reason_stationary
    GateProgress.Reason.SUSTAINING -> R.string.gate_reason_sustaining
    GateProgress.Reason.PASSED -> R.string.gate_reason_passed
}

/** Null for [GateProgress.Path.NONE], which renders nothing at all. */
@StringRes
private fun pathRes(path: GateProgress.Path): Int? = when (path) {
    GateProgress.Path.STEP_DETECTOR -> R.string.gate_path_step
    GateProgress.Path.IMU_FUSED -> R.string.gate_path_fused
    GateProgress.Path.IMU_IIR -> R.string.gate_path_imu
    GateProgress.Path.ALTERNATIVE_CHALLENGE -> R.string.gate_path_alt
    GateProgress.Path.NONE -> null
}

/** Past the horizon or not. The same [HorizonReading.terminal] the engine gates on. */
@StringRes
private fun horizonLabel(terminal: Boolean): Int =
    if (terminal) R.string.gate_past_horizon else R.string.gate_within_horizon

@StringRes
private fun whyStanding(terminal: Boolean): Int =
    if (terminal) R.string.gate_why_terminal else R.string.gate_why_within

@Composable
private fun whyBody(reading: HorizonReading): String = buildString {
    append(
        stringResource(
            R.string.gate_why_intro,
            reading.usedMinutes.toString(),
            reading.horizonMinutes.toString(),
        ),
    )
    append(" ")
    append(stringResource(whyStanding(reading.terminal)))
    append("\n\n")
    append(stringResource(R.string.gate_why_leaving))
}
