package dev.molasses.ui.gate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.molasses.core.model.GateProgress
import dev.molasses.engine.TierPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-bleed movement gate. No dismiss button, by design.
 *
 * The live [GateProgress] ring and the failing-check string are not polish:
 * an unlock condition the user cannot see is indistinguishable from a broken
 * app, and "walk until something happens" with no feedback is what makes
 * people uninstall rather than comply.
 */
@Composable
fun GateScreen(
    tier: Int,
    pkg: String,
    progressFlow: StateFlow<GateProgress>,
    alternativeChallenge: Boolean,
    challengePhrase: String,
    onChallengeAnswer: (String) -> Unit,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    var whyExpanded by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        label = "gateProgress",
    )

    val minutes = (TierPolicy.entryAtMs(tier) / 60_000).toInt()

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
                text = if (TierPolicy.isTerminal(tier)) "Tier $tier — terminal" else "Tier $tier",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$minutes minutes used",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            if (alternativeChallenge) {
                AlternativeChallenge(
                    phrase = challengePhrase,
                    onSubmit = onChallengeAnswer,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(140.dp),
                        strokeWidth = 6.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                    )
                    Text(
                        text = "${(animated * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = reasonText(progress),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (progress.reason.isDisqualifying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pathText(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Spacer(Modifier.height(40.dp))
            TextButton(onClick = { whyExpanded = !whyExpanded }) {
                Text("Why am I seeing this?")
            }
            AnimatedVisibility(visible = whyExpanded) {
                Text(
                    text = whyBody(minutes, tier),
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
            text = "Type this phrase",
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
            Text("Continue")
        }
    }
}

private fun reasonText(p: GateProgress): String = when (p.reason) {
    GateProgress.Reason.WAITING_TO_START -> "Stand up and start walking."
    GateProgress.Reason.NEED_MORE_STEPS -> "Keep walking — ${p.events} steps counted."
    GateProgress.Reason.CADENCE_TOO_SLOW -> "A little faster."
    GateProgress.Reason.CADENCE_TOO_FAST -> "Slower — that is faster than walking."
    GateProgress.Reason.CADENCE_IRREGULAR -> "Keep an even pace."
    GateProgress.Reason.NOT_ENOUGH_MOTION -> "Not enough movement yet."
    GateProgress.Reason.TOO_VIOLENT -> "Too much. Walk, do not shake."
    GateProgress.Reason.MOTION_NOT_VERTICAL -> "That is side-to-side, not walking."
    GateProgress.Reason.PHONE_STATIONARY -> "The phone is not moving with you."
    GateProgress.Reason.SUSTAINING -> "Good — keep going."
    GateProgress.Reason.PASSED -> "Done."
}

private fun pathText(p: GateProgress): String = when (p.path) {
    GateProgress.Path.STEP_DETECTOR -> "step sensor"
    GateProgress.Path.IMU_CADENCE -> "motion analysis"
    GateProgress.Path.ALTERNATIVE_CHALLENGE -> "alternative challenge"
    GateProgress.Path.NONE -> ""
}

private fun whyBody(minutes: Int, tier: Int): String = buildString {
    append("You have used this app for $minutes minutes in the current cycle. ")
    append("Clearing this gate unlocks the next five minutes. ")
    append("It does not reset your accumulated time, and it does not reduce the ")
    append("delay on scrolling — within a cycle the delay only ever grows. ")
    if (TierPolicy.isTerminal(tier)) {
        append("You are past twenty minutes, so this gate will return every five minutes ")
        append("until the cycle resets.")
    } else {
        append("The next gate is at ${(TierPolicy.entryAtMs(tier + 1) / 60_000)} minutes.")
    }
    append("\n\nPressing HOME or RECENTS will leave this screen — that pauses the gate ")
    append("rather than clearing it. The gate returns when you scroll again.")
}
