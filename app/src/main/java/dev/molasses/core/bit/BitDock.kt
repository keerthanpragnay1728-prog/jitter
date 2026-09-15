package dev.molasses.core.bit

/**
 * Whether Bit has retreated to the bezel.
 *
 * Section 05 requires it to retreat while the user is typing or idle, and
 * that retreat is what makes the HUD safe to add: docked Bit answers a
 * question on tap, undocked Bit keeps the startle reaction. One state, two
 * behaviours, and no new gesture for the user to learn.
 *
 * ## Why typing docks it
 * The prompt is the only thing on that screen that wants sustained attention.
 * A face reacting next to a line the user is composing is exactly the "pet
 * that demands attention" failure section 05 exists to prevent.
 *
 * ## Why idle docks it
 * An ambient presence that is always fully present is not ambient. Retreating
 * after a stretch of nothing is what makes the moments it does not retreat
 * mean something.
 *
 * Pure; no Android imports. Unit-tested in `BitDockTest`.
 */
object BitDock {

    /**
     * How long without an interaction before Bit retreats.
     *
     * ## Why thirty seconds and not eight
     * Eight made the face the exception rather than the default, and the face
     * is what carries the mood. A friction signal that is hidden unless you
     * touch something is not ambient, it is a menu item.
     *
     * It also made the blink nearly unobservable. The blink band is three to
     * seven seconds, so an eight second window holds one blink at best and
     * reads as a twitch. Thirty holds four to ten, which is what makes it
     * read as breathing rather than as a glitch. [IDLE_MS] is required by
     * test to be at least two full maximum intervals, so that reasoning is
     * structural rather than a comment.
     *
     * Thirty is also longer than any ordinary visit to the launcher. Coming
     * home between two apps takes a second or two, and a deliberate look is
     * five to fifteen, so in practice the retreat happens when the user has
     * actually gone away rather than when they paused. That is the right
     * split: a face while you are here, a glyph of status to come back to.
     */
    const val IDLE_MS = 30_000L

    /**
     * @param msSinceInteraction since the last tap or drag. A negative value,
     *   which within one boot can only be a bad read, docks rather than
     *   un-docking: retreating is the safe direction.
     *
     * The host seeds the clock at first composition rather than at zero, so
     * landing on the launcher shows the face and then watching it retreat is
     * the first thing anyone sees. Seeded at zero it was docked before the
     * first frame, which is how the whole of Bit ended up behind a gesture
     * nobody knew to perform.
     */
    fun isDocked(typing: Boolean, msSinceInteraction: Long): Boolean =
        typing || msSinceInteraction < 0L || msSinceInteraction >= IDLE_MS
}
