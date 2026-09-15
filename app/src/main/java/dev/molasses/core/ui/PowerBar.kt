package dev.molasses.core.ui

/**
 * The battery readout: `PWR [########..] 5C 49%`.
 *
 * Ten cells, hex capacity, percent. The hex is not decoration. It is the
 * same number twice, once as a bar you read at a glance and once as a value
 * you can quote, and rendering it in hex keeps the field two characters wide
 * at every charge level so the header never reflows.
 *
 * ## Glyph fallback
 * Block glyphs are not present in every monospace face, and a boxed glyph in
 * a ten cell bar is unreadable. [Glyphs.ASCII] is the fallback and the two
 * sets are interchangeable at the call site, so the decision can be made once
 * on device rather than threaded through the renderer.
 *
 * ## The charging cell
 * [chargingCellIndex] returns which filled cell is currently bright. The
 * caller animates by passing a phase; this file does no timing. That keeps
 * the rule that the header never recomposes per second: the caller drives it
 * from one animation while charging and with the screen on, and not at all
 * otherwise.
 *
 * Pure; no Android imports. Unit-tested in `PowerBarTest`.
 */
object PowerBar {

    const val CELLS = 10

    /** Below this the bar dims. Never red: red is the terminal tier alone. */
    const val LOW_PERCENT = 15

    /**
     * Below this Bit's face changes as well.
     *
     * Two thresholds, two magnitudes, and they are not the same signal twice.
     * At [LOW_PERCENT] one element dims a step, which says charge soon. At
     * five percent the companion changes identity, which says the phone is
     * about to go. Five also sits below the platform's own low-battery
     * warning, so it is an escalation rather than a third copy of a nag the
     * user has already dismissed.
     */
    const val CRITICAL_PERCENT = 5

    /** One full traverse of the filled region. */
    const val CHARGE_CYCLE_MS = 900L

    data class Glyphs(val filled: Char, val empty: Char) {
        companion object {
            /** Preferred. Verify on device before trusting it. */
            val BLOCK = Glyphs('█', '░')

            /** Fallback for a face that boxes the block glyphs. */
            val ASCII = Glyphs('#', '.')
        }
    }

    /**
     * Filled cell count for [percent].
     *
     * Rounds down, so ten filled cells means genuinely full rather than
     * merely close, and a non-zero charge always shows at least one cell. A
     * bar that reads empty on a phone that still works is the kind of small
     * lie that makes the rest of the readout untrustworthy.
     */
    fun filledCells(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        if (p <= 0) return 0
        return ((p * CELLS) / 100).coerceIn(1, CELLS)
    }

    /** `[########..]`, always [CELLS] wide plus the brackets. */
    fun bar(percent: Int, glyphs: Glyphs = Glyphs.BLOCK): String {
        val filled = filledCells(percent)
        return buildString {
            append('[')
            repeat(filled) { append(glyphs.filled) }
            repeat(CELLS - filled) { append(glyphs.empty) }
            append(']')
        }
    }

    /**
     * Capacity in hex, upper case, always two characters.
     *
     * 100 is 0x64, so two characters covers the whole range and the field
     * never changes width. Values outside 0 to 100 are clamped rather than
     * rendered, because a three character field would reflow the header.
     */
    fun hexCapacity(percent: Int): String =
        percent.coerceIn(0, 100).toString(16).uppercase().padStart(2, '0')

    fun isLow(percent: Int): Boolean = percent.coerceIn(0, 100) < LOW_PERCENT

    fun isCritical(percent: Int): Boolean = percent.coerceIn(0, 100) < CRITICAL_PERCENT

    /**
     * Which filled cell is bright this frame, or null when nothing should be.
     *
     * Null when not charging, when the bar is empty, and that is the whole
     * of the animation contract: the caller stops its animation entirely
     * rather than continuing to ask.
     *
     * @param phase 0.0 to 1.0 through [CHARGE_CYCLE_MS].
     */
    fun chargingCellIndex(percent: Int, charging: Boolean, phase: Float): Int? {
        if (!charging) return null
        val filled = filledCells(percent)
        if (filled <= 0) return null
        val wrapped = phase - kotlin.math.floor(phase)
        return (wrapped * filled).toInt().coerceIn(0, filled - 1)
    }
}
