package dev.molasses.ui.settings

import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.molasses.R
import dev.molasses.core.bit.BitStateMachine
import dev.molasses.core.settings.UntrackCoolingOff
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorGreen
import kotlinx.coroutines.delay

/**
 * The untrack cooling-off, full screen inside SettingsActivity.
 *
 * ```
 *     ( -_- )
 *     UNTRACK COOLING-OFF
 *     TARGET // <app label>
 *     LAST TARGET. UNTRACKING IT LEAVES NOTHING GATED.
 *     SOCIAL APP. TRACKING RESUMES IN 7 DAYS, OR AT THE NEXT RESTART.
 *     <seconds>
 * ```
 *
 * The fourth line only when [lastTarget]: see [UntrackCoolingOff.isLastTarget].
 * The fifth only when [sunsetDays] is set, which is before the user confirms
 * and for the apps `UntrackSunset.inScope` names: the untrack they are about
 * to confirm is temporary, and they are told so while the answer can still
 * be KEEP TRACKING.
 *
 * and at zero, [ CONFIRM REMOVE ] and [ KEEP TRACKING ] in place of the
 * number. See [UntrackCoolingOff] for the rules; this only draws them.
 *
 * Leaving abandons it: ON_PAUSE (home, recents, the screen going off) and
 * back both call [onLeave], and a rotation drops the state because the
 * caller holds it with `remember`. Taps that miss the answers are swallowed
 * so nothing behind the panel can be reached.
 */
@Composable
fun UntrackCoolingOffPanel(
    state: UntrackCoolingOff.State,
    lastTarget: Boolean,
    /** Days until tracking resumes, for a social app; null when the untrack is permanent. */
    sunsetDays: Int?,
    onConfirmRemove: () -> Unit,
    onKeepTracking: () -> Unit,
    onLeave: (UntrackCoolingOff.Leave) -> Unit,
) {
    val leave by rememberUpdatedState(onLeave)
    var phase by remember(state) {
        mutableStateOf(UntrackCoolingOff.phase(state, SystemClock.elapsedRealtime()))
    }

    // Recomputed from the clock on each tick, never decremented.
    LaunchedEffect(state) {
        while (true) {
            phase = UntrackCoolingOff.phase(state, SystemClock.elapsedRealtime())
            if (phase == UntrackCoolingOff.Phase.Ready) break
            delay(TICK_MS)
        }
    }

    BackHandler { leave(UntrackCoolingOff.Leave.BACK) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) leave(UntrackCoolingOff.Leave.PAUSED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground)
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
            )
            Text(
                text = stringResource(R.string.untrack_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.untrack_target_fmt, state.label),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = PhosphorDim,
                textAlign = TextAlign.Center,
            )
            if (lastTarget) {
                Text(
                    text = stringResource(R.string.untrack_last_target),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = PhosphorGreen,
                    textAlign = TextAlign.Center,
                )
            }
            if (sunsetDays != null) {
                Text(
                    text = stringResource(R.string.untrack_sunset_fmt, sunsetDays.toString()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = PhosphorGreen,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            when (val p = phase) {
                is UntrackCoolingOff.Phase.Counting -> Text(
                    text = UntrackCoolingOff.seconds(p.remainingMs).toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    color = PhosphorGreen,
                )
                UntrackCoolingOff.Phase.Ready -> {
                    Answer(stringResource(R.string.untrack_confirm), onConfirmRemove)
                    Answer(stringResource(R.string.untrack_keep), onKeepTracking)
                }
            }
        }
    }
}

@Composable
private fun Answer(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = PhosphorGreen,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 16.dp),
    )
}

/** Five a second, the lease gate's rate: the digit changes on the second it should. */
private const val TICK_MS = 200L
