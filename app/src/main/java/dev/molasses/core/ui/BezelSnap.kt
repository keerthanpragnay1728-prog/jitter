package dev.molasses.core.ui

/**
 * Where a draggable element is allowed to rest, and where it snaps to.
 *
 * ## The bug this exists to make impossible
 * Bit docked to the left bezel and stayed draggable; docked to the right it
 * became untouchable. Three faults compounded:
 *
 *  1. The right target was `containerWidth` rather than
 *     `containerWidth - itemWidth`, so the element landed one full width
 *     outside the parent.
 *  2. `itemWidth` is zero until the first layout pass reports it, and zero
 *     made the wrong target look arithmetically fine.
 *  3. The decay animation ran unbounded before the snap, so even a correct
 *     target was reached via a position far outside the parent.
 *
 * Compose does not deliver touch events to a child outside its parent's
 * bounds. Left worked only because its target is zero, which is inside by
 * accident rather than by calculation.
 *
 * Every offset handed to an animation now goes through [clamp], and
 * [canSnap] refuses to compute a target from a measurement that has not
 * arrived yet.
 *
 * Pure; no Android imports. Unit-tested in `BezelSnapTest`.
 */
object BezelSnap {

    /**
     * Largest in-bounds offset. Never negative: an item wider than its
     * container pins at 0 rather than producing a target to the left of the
     * parent, which would be the same bug mirrored.
     */
    fun maxOffset(containerPx: Int, itemPx: Int): Float =
        (containerPx - itemPx).coerceAtLeast(0).toFloat()

    /**
     * False while a measurement is missing.
     *
     * A zero [itemPx] is not a legitimate width, it is `onSizeChanged` not
     * having fired. Snapping on it puts the element exactly one width outside
     * the parent on the right, which is the reported symptom.
     */
    fun canSnap(containerPx: Int, itemPx: Int): Boolean = containerPx > 0 && itemPx > 0

    fun clamp(value: Float, containerPx: Int, itemPx: Int): Float =
        value.coerceIn(0f, maxOffset(containerPx, itemPx))

    /**
     * The nearer bezel.
     *
     * Ties go right. The prompt and the app list are left aligned, so the
     * right edge is the side where Bit obscures least, and an exactly centred
     * release is otherwise a coin flip the user cannot predict.
     */
    fun snapTargetX(currentX: Float, containerPx: Int, itemPx: Int): Float {
        val max = maxOffset(containerPx, itemPx)
        if (max <= 0f) return 0f
        val clamped = currentX.coerceIn(0f, max)
        return if (clamped >= max / 2f) max else 0f
    }

    /**
     * Where a docked element must move to when its own measured width
     * changes underneath it.
     *
     * ## Why this is needed at all
     * Bit is rendered into a fixed-width slot precisely so this cannot
     * happen, and for one state it now has two: the retreated slit gets a
     * narrower slot so it can sit flush on the bezel. See `BitGlyph`.
     *
     * A narrower item makes [maxOffset] *larger*, so the old right-hand
     * offset is still perfectly in bounds and nothing corrects it. Bit
     * therefore stays where the wide slot put it, which is the difference
     * between the two widths in from the edge. That is the docked-slit bug as
     * reported: not off screen, just not flush.
     *
     * The mirror case is the dangerous one. A wider item makes [maxOffset]
     * *smaller*, so the old offset is out of bounds, and an element outside
     * its parent receives no touch events at all. That is the original right
     * bezel lockup this file was written for, arriving by a second route.
     *
     * ## Why null in the middle
     * Only an element already resting against a bezel is repositioned. One
     * sitting anywhere else was put there by a drag or a decay that is
     * probably still running, and moving it would be the layout wrestling the
     * user's finger. Its bounds are still corrected by the caller; only the
     * re-dock is declined.
     *
     * The return is always inside `[0, maxOffset(containerPx, toItemPx)]` by
     * construction, so applying it cannot leave the element part way off
     * screen at either width.
     *
     * @param fromItemPx the width the element was measured at, or 0 before
     *   the first layout pass, which returns null.
     */
    fun reSnap(currentX: Float, containerPx: Int, fromItemPx: Int, toItemPx: Int): Float? {
        if (!canSnap(containerPx, fromItemPx) || !canSnap(containerPx, toItemPx)) return null
        val fromMax = maxOffset(containerPx, fromItemPx)
        return when {
            currentX <= 0f -> 0f
            currentX >= fromMax -> maxOffset(containerPx, toItemPx)
            else -> null
        }
    }

    /** Guard for the call site, so an out-of-bounds offset fails loudly. */
    fun isWithinBounds(value: Float, containerPx: Int, itemPx: Int): Boolean =
        value >= 0f && value <= maxOffset(containerPx, itemPx)
}
