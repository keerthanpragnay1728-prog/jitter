package dev.molasses.ui.lock

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molasses.R
import dev.molasses.core.lock.LockReason
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorDivider
import dev.molasses.ui.theme.PhosphorGreen

/**
 * The lock flash: four lines, then the user is sent home.
 *
 * ## Why it is a message and not just a bounce
 * Being thrown to the launcher with no explanation is indistinguishable from a
 * crash, and a user who thinks the app crashed opens it again. The remaining
 * time is the load-bearing line: it is the difference between "something went
 * wrong" and "I did this to myself on purpose, for four more hours".
 *
 * ## Why it is not dismissable
 * There is nothing to dismiss it to. The window is up for a fixed hold and
 * then the home action fires whether or not anyone looked at it, so there is
 * no button, no back handling and no timer the user can outwait.
 *
 * Opaque rather than translucent: the locked app is behind this, and a
 * see-through message over the feed the lock exists to hide would be a worse
 * outcome than no message.
 */
@Composable
fun LockScreen(
    label: String,
    reason: LockReason,
    remainingText: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.lock_flash_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )
            Text(
                text = remainingText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(reason.messageRes()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = PhosphorDivider,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The line that explains why, per reason.
 *
 * A plain function from state to a resource id, resolved at the call site, so
 * the mapping stays pure and every string stays in `strings.xml`.
 */
@StringRes
fun LockReason.messageRes(): Int = when (this) {
    LockReason.BLOCK -> R.string.lock_reason_block
    LockReason.FOCUS -> R.string.lock_reason_focus
    LockReason.BEDTIME -> R.string.lock_reason_bedtime
    LockReason.CHECKPOINT -> R.string.lock_reason_checkpoint
}
