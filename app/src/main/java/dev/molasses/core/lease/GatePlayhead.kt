package dev.molasses.core.lease

/**
 * The lease gate's playhead: a fixed dashed track with one marker that steps
 * one column left per whole second. Pure.
 *
 * ```
 *   24 of 30:  ------------------------|------
 *    9 of 30:  ---------|---------------------
 *    0 of 30:  |------------------------------
 * ```
 *
 * The track is [totalSec] + 1 columns, one per whole second from the full
 * countdown down to zero inclusive, and its length never changes during a
 * gate, so nothing on the line moves but the marker. The marker sits at the
 * column numbered by the seconds left, counted from the left edge.
 *
 * It moves only when the numeral above it does: the caller hands it the same
 * whole-second value the numeral prints, computed once per tick. No easing,
 * no sub-second position. See `GateReadout.Fields.remainingSec`.
 *
 * ASCII only, so it renders identically in every monospace font.
 */
object GatePlayhead {

    const val TRACK = '-'
    const val MARKER = '|'

    /**
     * @param remainingSec whole seconds left, as the numeral shows them.
     *   Clamped to 0..[totalSec].
     * @param totalSec the gate's whole countdown in seconds (8, 12, ... up to
     *   the 30 second cap in `GateCountdown`). A negative value reads as 0.
     */
    fun playhead(remainingSec: Int, totalSec: Int): String {
        val total = totalSec.coerceAtLeast(0)
        val at = remainingSec.coerceIn(0, total)
        val track = CharArray(total + 1) { TRACK }
        track[at] = MARKER
        return String(track)
    }
}
