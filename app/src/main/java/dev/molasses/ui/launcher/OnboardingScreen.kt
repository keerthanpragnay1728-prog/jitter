package dev.molasses.ui.launcher

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molasses.R
import dev.molasses.core.setup.Onboarding
import dev.molasses.core.setup.Onboarding.Step
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorGreen

/**
 * Set on an Intent to [LauncherActivity] to show the first-run flow again.
 * CFG SETUP sends it. See [Onboarding.shouldShow].
 */
const val EXTRA_ONBOARDING = "dev.molasses.extra.ONBOARDING"

/** A step's name on the progress list. Pure mapping, resolved at the call site. */
@StringRes
fun onboardingStepName(step: Step): Int = when (step) {
    Step.ACCESSIBILITY -> R.string.onboarding_a11y_name
    Step.USAGE_ACCESS -> R.string.onboarding_usage_name
    Step.TARGETS -> R.string.onboarding_targets_name
    Step.LIMITS -> R.string.onboarding_limits_name
}

/**
 * The first-run flow. Stateless: [LauncherActivity] owns the facts, re-reads
 * them on resume and while a grant is outstanding, and decides when this is
 * shown. See [Onboarding] for what each step can and cannot detect.
 *
 * Opaque and it swallows taps on its background, like [LockConfirmPanel], so
 * nothing behind it is reachable. Back is handled at the activity root and
 * does what LATER does.
 *
 * The padding is the house constant, and it carries the inset fault CLAUDE.md
 * describes. Not fixed here: this screen is shown on the console, where the
 * status bar is hidden.
 */
@Composable
fun OnboardingScreen(
    facts: Onboarding.Facts,
    showUnlock: Boolean,
    trackedLabels: List<String>,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onEditTargets: () -> Unit,
    onTargetsSeen: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
) {
    // Null only when everything reads satisfied, which the host closes on.
    // LIMITS is the step whose button finishes, so it is the safe landing.
    val current = Onboarding.current(facts) ?: Step.LIMITS

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 18.dp, vertical = 44.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Line(stringResource(R.string.onboarding_title), PhosphorGreen, bold = true, size = 18)
            Spacer(Modifier.height(4.dp))
            for (step in Step.entries) {
                val name = stringResource(onboardingStepName(step))
                val mark = if (Onboarding.satisfied(step, facts)) {
                    R.string.onboarding_mark_done_fmt
                } else {
                    R.string.onboarding_mark_open_fmt
                }
                Line(
                    stringResource(mark, name),
                    if (step == current) PhosphorGreen else PhosphorDim,
                    bold = step == current,
                )
            }
            Spacer(Modifier.height(16.dp))
            Line(
                stringResource(
                    R.string.onboarding_step_fmt,
                    (current.ordinal + 1).toString(),
                    Step.entries.size.toString(),
                ),
                PhosphorDim,
            )

            when (current) {
                Step.ACCESSIBILITY -> {
                    Line(stringResource(R.string.onboarding_a11y_body), PhosphorGreen)
                    Line(stringResource(R.string.onboarding_a11y_sideload), PhosphorDim)
                    if (Onboarding.waitingForBind(facts)) {
                        Line(stringResource(R.string.onboarding_a11y_waiting), PhosphorGreen)
                    }
                    Command(R.string.onboarding_open_settings, onOpenAccessibility)
                    if (showUnlock) {
                        Spacer(Modifier.height(12.dp))
                        Line(stringResource(R.string.onboarding_unlock_title), PhosphorGreen, bold = true)
                        Line(stringResource(R.string.onboarding_unlock_1), PhosphorGreen)
                        Line(stringResource(R.string.onboarding_unlock_2), PhosphorGreen)
                        Line(stringResource(R.string.onboarding_unlock_3), PhosphorGreen)
                        Line(stringResource(R.string.onboarding_unlock_4), PhosphorGreen)
                        Line(stringResource(R.string.onboarding_unlock_note), PhosphorDim)
                        Command(R.string.onboarding_open_app_info, onOpenAppInfo)
                    }
                }
                Step.USAGE_ACCESS -> {
                    Line(stringResource(R.string.onboarding_usage_body), PhosphorGreen)
                    Command(R.string.onboarding_open_settings, onOpenUsageAccess)
                }
                Step.TARGETS -> {
                    Line(stringResource(R.string.onboarding_targets_body), PhosphorGreen)
                    if (trackedLabels.isEmpty()) {
                        Line(stringResource(R.string.onboarding_targets_none), PhosphorDim)
                    } else {
                        for (label in trackedLabels) Line(label, PhosphorDim)
                    }
                    Command(R.string.onboarding_targets_edit, onEditTargets)
                    Command(R.string.onboarding_next, onTargetsSeen)
                }
                Step.LIMITS -> {
                    Line(stringResource(R.string.onboarding_limit_paytm), PhosphorGreen)
                    Line(stringResource(R.string.onboarding_limit_youtube), PhosphorGreen)
                    Line(stringResource(R.string.onboarding_limit_locks), PhosphorGreen)
                    Command(R.string.onboarding_done, onDone)
                }
            }

            Spacer(Modifier.height(24.dp))
            Command(R.string.onboarding_later, onLater, color = PhosphorDim)
        }
    }
}

@Composable
private fun Line(text: String, color: Color, bold: Boolean = false, size: Int = 14) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = size.sp,
        color = color,
    )
}

@Composable
private fun Command(@StringRes label: Int, onClick: () -> Unit, color: Color = PhosphorGreen) {
    Text(
        text = stringResource(label),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = color,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
