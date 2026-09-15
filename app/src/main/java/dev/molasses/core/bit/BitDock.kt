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
     * Long enough that it does not dock between two taps of the same gesture,
     * short enough that it is docked by the time anyone looks away and back.
     */
    const val IDLE_MS = 8_000L

    /**
     * @param msSinceInteraction since the last tap or drag. A negative value,
     *   which within one boot can only be a bad read, docks rather than
     *   un-docking: retreating is the safe direction.
     */
    fun isDocked(typing: Boolean, msSinceInteraction: Long): Boolean =
        typing || msSinceInteraction < 0L || msSinceInteraction >= IDLE_MS
}
