package dev.molasses.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molasses.R
import dev.molasses.core.bit.BitStateMachine
import dev.molasses.core.command.CommandRender
import dev.molasses.core.command.LockConfirmation
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorGreen

/**
 * A long console lock, waiting on the panel. [commit] dispatches it confirmed
 * and hands the outcome back to the console, so the console's own reaction
 * (Bit's face, the ack) is what the user sees afterwards.
 */
class LockConfirmRequest(val panel: LockConfirmation.Panel, val commit: () -> Unit)

/**
 * The full-screen confirmation for a console lock above a day. See
 * [LockConfirmation] for why it exists and why it has no cancel button.
 *
 * Opaque, and it swallows taps on its background, so nothing behind it can be
 * reached while it is up. Back is handled at the activity root: it aborts and
 * writes nothing.
 */
@Composable
fun LockConfirmPanel(
    panel: LockConfirmation.Panel,
    onCommit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground)
            // Consumes taps that miss the button, so they do not fall through
            // to the prompt or the list underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = BitStateMachine.BLINK_HALF,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_confirm_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.lock_confirm_target_fmt,
                    when (val target = panel.target) {
                        is LockConfirmation.Target.App -> target.label
                        LockConfirmation.Target.AllTracked -> stringResource(R.string.lock_confirm_all_tracked)
                    },
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_confirm_duration_fmt, CommandRender.duration(panel.durationMs)),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.lock_confirm_commit),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onCommit)
                    .padding(vertical = 10.dp, horizontal = 16.dp),
            )
        }
    }
}
