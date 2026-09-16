package dev.molasses.core.lease

import dev.molasses.core.bit.BitStateMachine
import dev.molasses.core.ui.CycleLine

/**
 * What the launch gate puts on the glass, as strings.
 *
 * ```
 * ( -_- )
 *
 * INSTAGRAM
 *
 * TODAY         2h 14m
 * THIS CYCLE    38m
 * OPENS TODAY   17
 *
 *      8
 * ```
 *
 * ## No copy
 * There is no sentence anywhere on this screen. No question, no encouragement,
 * no lecture. The three numbers are the argument, and a user who has spent two
 * hours in an app today does not need to be told what that means by the app
 * that took them.
 *
 * It is also the only version that stays true. Any sentence here would be
 * making a claim about what the user wants, and the numbers make no claim at
 * all.
 *
 * ## No progress bar
 * The countdown is a number and the only moving thing on the screen. A bar
 * filling up invites watching the bar, which is a second thing to look at
 * instead of the numbers. A single digit changing once a second is the
 * smallest possible moving element and it is unambiguous about how long is
 * left, which a bar is not.
 *
 * ## Unknown is [UNKNOWN], never zero and never hidden
 * A device without usage access cannot answer TODAY or OPENS TODAY. It renders
 * `--`. Hiding the row would make the screen's shape depend on a permission,
 * and a confident `0m` would be a lie told by the one screen whose entire
 * authority is that its numbers are real.
 *
 * Pure; no Android imports. Unit-tested in `GateReadoutTest`.
 */
object GateReadout {

    /** For anything there is no reading for. Never a zero. */
    const val UNKNOWN = CycleLine.UNKNOWN

    /** Waiting. Flat eyes, because this is not a negotiation. */
    const val FACE_WAITING = BitStateMachine.BLINK_HALF

    /** The countdown is done and the panel is up. */
    const val FACE_READY = BitStateMachine.NEUTRAL

    data class Fields(
        val face: String,
        val today: String,
        val cycle: String,
        val opens: String,
        val countdown: String,
    )

    /**
     * @param todayMs foreground milliseconds in this app today, from
     *   [dev.molasses.core.stats.DayUsage]. Null when usage access is absent.
     * @param cycleMs accumulated milliseconds this cycle, from the engine.
     *   Null when the engine has no record of this package, which is not the
     *   same as a record of zero.
     * @param opensToday visits today. Null for the same reason as [todayMs].
     * @param remainingMs milliseconds left on the countdown.
     */
    fun fields(
        todayMs: Long?,
        cycleMs: Long?,
        opensToday: Int?,
        remainingMs: Long,
    ) = Fields(
        face = faceFor(remainingMs),
        today = todayMs?.let { CycleLine.duration(it) } ?: UNKNOWN,
        cycle = cycleMs?.let { CycleLine.duration(it) } ?: UNKNOWN,
        opens = opensToday?.takeIf { it >= 0 }?.toString() ?: UNKNOWN,
        countdown = countdown(remainingMs),
    )

    fun faceFor(remainingMs: Long): String =
        if (remainingMs > 0L) FACE_WAITING else FACE_READY

    /**
     * Seconds left, rounded **up**.
     *
     * So an eight second gate opens reading `8` rather than `7`, and the
     * screen only reads `0` once there is genuinely nothing left. Rounding
     * down would show `0` for the last full second, and a zero that is not
     * zero on the one screen whose whole point is honest numbers is not worth
     * the millisecond of accuracy.
     */
    fun countdown(remainingMs: Long): String {
        if (remainingMs <= 0L) return "0"
        return ((remainingMs + 999L) / 1000L).toString()
    }

    /** True once the decision panel should be on screen. */
    fun panelUp(remainingMs: Long): Boolean = remainingMs <= 0L

    /** A lease button's label: `5m`, `10m`, `15m`. */
    fun leaseLabel(durationMs: Long): String = "${durationMs / 60_000L}m"
}
