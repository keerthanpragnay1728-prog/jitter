package dev.molasses.ui.lock

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.molasses.core.bit.BitStateMachine
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lock screen.
 *
 *     ( -_- )
 *     BLOCKED
 *     TARGET // <app label>
 *     WILL BE OPEN BY
 *     <date and time>
 *     [ ARCHITECT'S SPACE ]
 *
 * ## Why an opening time and not a remainder
 * "3h 12m" is a number to do arithmetic on, and it is a different number every
 * time the screen is seen. The instant the app opens is the same on every
 * visit, which is what makes it read as a fact about a decision already made
 * rather than as a countdown to wait out. It comes from the stored lock
 * through the restriction clamp, see `LockOpensAt`, and is formatted in the
 * device locale with the system's 12 or 24 hour choice.
 *
 * ## Why it stays until the user leaves
 * It used to bounce on a timer, which on a device read as the screen changing
 * underneath you while you were still reading why. The way out is the one
 * button, and pressing it is what sends the user home.
 *
 * Opaque rather than translucent: the locked app is behind this, and a
 * see-through message over the feed the lock exists to hide would be a worse
 * outcome than no message.
 */
@Composable
fun LockScreen(
    label: String,
    opensAtText: String,
    onExit: () -> Unit,
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
                text = BitStateMachine.BLINK_HALF,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_target_fmt, label),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_opens_by),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )
            Text(
                text = opensAtText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // The way out, and the only one. Bracketed, like every other
            // pressable thing here, because in a zero-border layout the
            // brackets are the affordance.
            Text(
                text = stringResource(R.string.lock_exit),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onExit)
                    .padding(vertical = 10.dp, horizontal = 16.dp),
            )
        }
    }
}

/**
 * [wallMs] as a date and time in the device locale, 12 or 24 hour as the
 * system setting says. The pattern comes from the locale's best match for a
 * weekday, day, month and time skeleton, so field order and separators are
 * the user's own.
 */
fun lockOpensAtText(context: Context, wallMs: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "EEEdMMMHHmm" else "EEEdMMMhmma"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(wallMs))
}
