package dev.molasses.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.molasses.R
import dev.molasses.core.friction.NextScroll
import dev.molasses.core.lease.GateReadout
import dev.molasses.core.lease.LeaseLadder
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorGreen

/**
 * The launch gate: what stands between opening a target app and being in it.
 *
 * ```
 *              ( -_- )
 *
 *             INSTAGRAM
 *
 *   TODAY                 2h14m
 *   THIS CYCLE              38m
 *   OPENS TODAY              17
 *
 *                 8
 * ```
 *
 * Pitch black, monospace, borderless, no card, no elevation, no accent. It is
 * not styled as part of the app it is covering and it is not styled as a
 * dialog, because it is neither. See [GateReadout] for why there is no
 * sentence on it and no progress bar in it.
 *
 * ## The countdown is the only thing that moves
 * Nothing pulses, nothing fades, nothing animates in. A screen with two moving
 * elements invites watching the other one, and this screen has exactly one
 * job, which is to be waited through while three numbers are visible.
 *
 * ## The panel appears at zero and not before
 * The buttons are not disabled-then-enabled, they are absent and then present.
 * A greyed-out `[ 15m ]` is something to aim at for eight seconds, which turns
 * the wait into a loading screen for the reward at the end of it. An empty
 * space that later has buttons in it is just a wait.
 *
 * All copy comes from `res/values/strings.xml`. See the header of that file.
 */
@Composable
fun LeaseGateScreen(
    appLabel: String,
    expired: Boolean,
    fields: GateReadout.Fields,
    nextScroll: NextScroll.Reading,
    panelUp: Boolean,
    onTakeLease: (durationMs: Long) -> Unit,
    onTakeMeOut: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = fields.face,
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp,
                color = PhosphorGreen,
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(
                    if (expired) R.string.lease_gate_header_expired_fmt
                    else R.string.lease_gate_header_fmt,
                    appLabel.uppercase(),
                ),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PhosphorGreen,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            StatRow(stringResource(R.string.lease_gate_today), fields.today)
            StatRow(stringResource(R.string.lease_gate_cycle), fields.cycle)
            StatRow(stringResource(R.string.lease_gate_opens), fields.opens)

            Spacer(Modifier.height(20.dp))

            NextScrollLine(nextScroll)

            Spacer(Modifier.height(20.dp))

            if (panelUp) {
                DecisionPanel(onTakeLease = onTakeLease, onTakeMeOut = onTakeMeOut)
            } else {
                Text(
                    text = fields.countdown,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    color = PhosphorGreen,
                )
            }
        }
    }
}

/**
 * What the next scroll costs, dimmed, under the three totals.
 *
 * ## Why it is a different sentence in each band and not a number
 * See `NextScroll` for the measured reason. In short: with five minute leases
 * the first two gates of a cycle sit below the onset and would read `0ms 0%`,
 * and everything from the horizon on is pinned, so a live readout is blank at
 * the gate seen most and frozen for the rest of a long session. Zero is the
 * absence of a reading rather than a reading, and `0ms 0%` looks like a broken
 * display on the one screen whose whole claim is honest numbers.
 *
 * ## Why the `when` is here and not a `@StringRes` helper
 * The usual shape in this app maps state to a string id in a pure function and
 * resolves it at the call site. That works when the arguments are the same
 * across the family, and here they are not: one band takes two numbers and the
 * other two take none. Passing surplus arguments would compile and would make
 * the id mapping the only place a reader could check which band formats what.
 * So the branch and its arguments stay together, and a new band is a compile
 * error here rather than a silently unformatted line.
 *
 * Dimmed rather than green. It is context for the decision, not the decision,
 * and the countdown is still the only thing on this screen that moves.
 */
@Composable
private fun NextScrollLine(reading: NextScroll.Reading) {
    val text = when (reading) {
        NextScroll.Reading.BeforeOnset ->
            stringResource(R.string.lease_gate_next_none)
        is NextScroll.Reading.OnTheRamp -> stringResource(
            R.string.lease_gate_next_fmt,
            NextScroll.percent(reading.probability).toString(),
            reading.stallMs.toString(),
        )
        is NextScroll.Reading.Pinned ->
            stringResource(R.string.lease_gate_next_pinned)
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = PhosphorDim,
        textAlign = TextAlign.Center,
    )
}

/**
 * One label and one value, on a line.
 *
 * `SpaceBetween` rather than padding the label to a fixed width in Kotlin: the
 * values are right aligned against the same edge either way, and a hardcoded
 * column width would be wrong on the first device with a different font scale.
 */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = PhosphorDim,
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = PhosphorGreen,
        )
    }
}

/**
 * Three durations and the way out.
 *
 * `[ TAKE ME OUT ]` is a peer of the lease buttons, not a dismissal tucked in
 * a corner, because leaving is one of the four answers and the only one that
 * costs nothing. Making it smaller or dimmer than the others would be the
 * screen arguing for a lease, which is the one thing it must not do.
 */
@Composable
private fun DecisionPanel(
    onTakeLease: (durationMs: Long) -> Unit,
    onTakeMeOut: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.lease_gate_select),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeaseLadder.OFFERED_MS.forEach { ms ->
                Text(
                    text = stringResource(
                        R.string.lease_gate_button_fmt,
                        GateReadout.leaseLabel(ms),
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = PhosphorGreen,
                    modifier = Modifier
                        .clickable { onTakeLease(ms) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.lease_gate_out),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = PhosphorGreen,
            modifier = Modifier
                .clickable { onTakeMeOut() }
                .padding(vertical = 10.dp, horizontal = 6.dp),
        )
    }
}
