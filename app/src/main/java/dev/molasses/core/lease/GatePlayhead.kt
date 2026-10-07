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

    /** The largest the track is ever drawn. A short gate's nine columns would otherwise fill the screen. */
    const val MAX_SP: Float = 22f

    /** The side gutter the track keeps on each side of the gate's width. */
    const val GUTTER_DP: Float = 16f

    /**
     * One monospace column's advance, as a fraction of the font size. The
     * monospace faces Android maps `FontFamily.Monospace` to advance 0.6 em;
     * this allows a little more, so a face a hair wider still fits on one
     * line rather than wrapping or clipping the marker.
     */
    const val MONO_ADVANCE_EM: Float = 0.62f

    /**
     * The track's font size: the largest at which [columns] monospace
     * columns fit [widthDp] less a [GUTTER_DP] gutter each side, capped at
     * [MAX_SP], floored to a tenth of a sp. [fontScale] is the system's sp to
     * dp ratio (`LocalDensity.fontScale`), so a larger system font size gets
     * a smaller sp and the same one line.
     *
     * The screen measures its width once and calls this; the track is never
     * allowed to wrap.
     */
    fun fontSizeSp(widthDp: Float, columns: Int, fontScale: Float = 1f): Float {
        val available = widthDp - 2 * GUTTER_DP
        if (columns <= 0 || available <= 0f || fontScale <= 0f) return 0f
        val fit = available / (columns * MONO_ADVANCE_EM * fontScale)
        return minOf(MAX_SP, kotlin.math.floor(fit * 10f) / 10f)
    }

    /** How wide [columns] columns draw at [sp], in dp. What [fontSizeSp] keeps inside the gutters. */
    fun widthDp(sp: Float, columns: Int, fontScale: Float = 1f): Float = columns * MONO_ADVANCE_EM * sp * fontScale

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
