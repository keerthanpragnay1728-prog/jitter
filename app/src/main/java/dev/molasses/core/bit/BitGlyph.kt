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
 * ## The one exception: the docked slit gets its own slot
 * The rule above buys a layout that cannot move, and it charged the retreat
 * for it. `[|]` padded into a seven wide slot is three characters of ink with
 * four cells of nothing after them, so a Bit docked to the right bezel drew
 * its slit four cells in from the edge and looked dropped rather than docked.
 * The retreat is the one state whose whole point is to be out of the way, and
 * it was the one state that could not reach the edge.
 *
 * So there are two slots, [WIDTH] and [SLIT_WIDTH], and [padFor] picks
 * between them by content. That is only safe because a slit and a face can
 * never occupy the same frame: `BitDisplay.Slit` is a case of its own,
 * `BitStateMachine.frame` renders it in a branch of its own, and every other
 * case including `Speech` resolves to a face. `BitGlyphTest` pins that, and
 * pins the two glyph sets disjoint, so a face named `[|]` fails rather than
 * silently narrowing the slot.
 *
 * A second width does move the snap target, which is the thing the single
 * slot existed to prevent. That is handled where it belongs, in
 * `BezelSnap.reSnap`, which moves an already docked Bit to the new edge when
 * its measurement changes. The guarantee is therefore weaker but still
 * stated: the slot does not move *while a glyph of one kind is showing*, and
 * a change of kind re-docks rather than drifting.
 *
 * [SLIT_WIDTH] is derived from [SLITS] the same way [WIDTH] is derived from
 * the faces, so `[!]` and `[z]` cannot drift away from `[|]`.
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

    /**
     * The narrower slot the retreated glyph is rendered into.
     *
     * Derived from [SLITS] for the same reason [WIDTH] is derived from the
     * faces: a fourth slit added later must widen this or be truncated, and
     * neither can happen quietly.
     */
    val SLIT_WIDTH: Int = SLITS.maxOf { it.length }

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

    /**
     * Which slot [text] belongs in.
     *
     * Keyed on the glyph rather than plumbed through `BitFrame`, because the
     * frame does not know either: it carries a string, and the string is the
     * whole of what distinguishes the two cases. A membership test against
     * three constants declared ten lines above is a shorter path to the same
     * answer than a flag that every producer would have to set correctly.
     *
     * It is only sound while the two sets are disjoint, so that is asserted
     * rather than assumed. A HUD readout cannot collide either: `BitHud`
     * emits exactly [WIDTH] characters and [SLIT_WIDTH] is smaller, so no
     * readout can ever equal a slit.
     */
    fun slotFor(text: String): Int = if (text in SLITS) SLIT_WIDTH else WIDTH

    /** [text] in whichever slot it belongs in, left aligned. See [slotFor]. */
    fun padFor(text: String): String = pad(text, slotFor(text))
}
