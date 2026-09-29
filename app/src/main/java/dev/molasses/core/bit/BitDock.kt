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
 * ## An explicit touch outranks the typing retreat
 * The text clause used to be unconditional, which made it a second reason to
 * be docked that no gesture could clear, and `BitTap`'s readout cycle could
 * not clear it either. The closing tap of that cycle reports an interaction
 * and the host writes it to the interaction clock, but with text in the
 * prompt the answer was docked anyway, so the readout cycled forever and no
 * number of taps returned a face. That is the exact lockup `BitTap`'s doc
 * says its exit prevents, reached through the other clause.
 *
 * The ambient rule and an explicit request are not the same kind of thing.
 * Retreating while the prompt is being composed is a policy about attention
 * nobody asked for; three deliberate taps on the slit are a request. So the
 * retreat holds while the keystroke is the more recent of the two clocks,
 * and a touch more recent than the last keystroke hands Bit back until the
 * next character is typed.
 *
 * Which clock moved last, rather than a window on either. A grace period
 * would be a guess at how long someone pauses mid-line, and the answer to
 * "are they still composing" is already on the two clocks the host keeps.
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
     * @param hasText whether the prompt holds anything. Named for what it
     *   measures rather than for what it means, because "typing" is a
     *   question about two clocks and this is only one of its halves.
     * @param msSinceInteraction since the last tap or drag on Bit. A negative
     *   value, which within one boot can only be a bad read, docks rather
     *   than un-docking: retreating is the safe direction.
     * @param msSinceKeystroke since the last edit to the prompt.
     *
     * The host seeds the interaction clock at first composition rather than
     * at zero, so landing on the launcher shows the face and then watching it
     * retreat is the first thing anyone sees. Seeded at zero it was docked
     * before the first frame, which is how the whole of Bit ended up behind a
     * gesture nobody knew to perform.
     */
    fun isDocked(
        hasText: Boolean,
        msSinceInteraction: Long,
        msSinceKeystroke: Long,
    ): Boolean = when {
        msSinceInteraction < 0L -> true
        msSinceInteraction >= IDLE_MS -> true
        // The text retreat, and the one thing that outranks it. Which clock
        // moved last is the whole question: a keystroke at least as recent as
        // the touch means the user is composing, and a touch more recent than
        // the keystroke means they asked for Bit back.
        hasText -> msSinceKeystroke <= msSinceInteraction
        else -> false
    }
}
