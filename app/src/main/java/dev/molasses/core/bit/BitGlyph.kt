package dev.molasses.core.bit

/**
 * The fixed-width slot every Bit glyph is rendered into.
 *
 * ## Why a slot and not just a string
 * Bit is draggable, docks to a bezel, and its snap target is computed from its
 * measured width. A glyph that is two characters narrower than the last one
 * changes that width, which changes the right-hand snap target, which moves
 * Bit while nobody touched it. `BezelSnap` already carries the scar tissue
 * from getting that arithmetic wrong once.
 *
 * So every face and every HUD string is padded to one width before it is
 * measured. The layout then cannot move, whatever is being shown, and the HUD
 * structurally cannot fight edge docking.
 *
 * ## Why the padding is on the right and not both sides
 * It used to centre, and that was a second bug wearing the first one's
 * clothes. The slot stayed 7 wide, exactly as intended, but the ink inside it
 * did not: `(o_o)` is five characters and centres to one leading space, while
 * the blink `( -_- )` is seven and centres to none. So the face jumped one
 * character cell left for the 120 ms of every blink and snapped back.
 *
 * That read as a twitchy blink and survived three fixes aimed at blink
 * *timing*, all of which were correct and none of which could touch it. The
 * eyes were closing on schedule the whole time; the face was also sliding
 * sideways while they did it.
 *
 * Left-aligned, every glyph starts at column zero and only the trailing pad
 * varies, which nothing can see. The invariant is now the whole of what it
 * always claimed to be: the slot does not move, and neither do its contents.
 *
 * `BitGlyphTest` asserts the leading edge directly, so a future return to
 * centring fails rather than looking tidier.
 *
 * [WIDTH] is derived from the faces rather than written down, so a new face
 * cannot be added that quietly overflows the slot. `BitGlyphTest` asserts that
 * every string constant on [BitStateMachine] is either a face in
 * [BitStateMachine.FACES] or a named exception, which is what keeps the
 * derivation honest.
 *
 * Pure; no Android imports. Unit-tested in `BitGlyphTest`.
 */
object BitGlyph {

    /** The docked slit, which carries one character of status. */
    const val SLIT_NORMAL = "[|]"

    /** A checkpoint is overdue and the penalty ratchet is running. */
    const val SLIT_ALERT = "[!]"

    /** A bedtime lock is standing. */
    const val SLIT_CURFEW = "[z]"

    val SLITS: List<String> = listOf(SLIT_NORMAL, SLIT_ALERT, SLIT_CURFEW)

    /** The widest thing Bit can ever be. Derived, never written down. */
    val WIDTH: Int = BitStateMachine.FACES.maxOf { it.length }

    /** What the docked slit shows. One character, no text, no gesture. */
    enum class Slit { NORMAL, ALERT, CURFEW }

    /**
     * The status glyph.
     *
     * Ordered, not combined: a curfew outranks an overdue checkpoint because
     * during a curfew the checkpoint is not the thing the user can act on.
     */
    fun slitFor(curfew: Boolean, penaltyAccruing: Boolean): String = when {
        curfew -> SLIT_CURFEW
        penaltyAccruing -> SLIT_ALERT
        else -> SLIT_NORMAL
    }

    /**
     * [text] in a [WIDTH] slot, left aligned.
     *
     * Left rather than centred: see the class doc. Padding on both sides
     * moves the glyph whenever its length changes, which is every blink.
     *
     * Truncates rather than overflowing. Nothing this renders should ever be
     * too long, and `BitHudTest` sweeps the whole input range to prove it, but
     * a glyph that silently widened the slot would reintroduce the bug this
     * file exists to make impossible, so the clamp is unconditional.
     */
    fun pad(text: String, width: Int = WIDTH): String {
        if (text.length >= width) return text.take(width)
        return text + " ".repeat(width - text.length)
    }
}
