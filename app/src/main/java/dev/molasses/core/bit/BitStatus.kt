package dev.molasses.core.bit

import dev.molasses.core.model.AppSnapshot

/**
 * The conditions the docked slit reports.
 *
 * ## Why one character
 * A docked Bit is a shell prompt, not a status bar. `vi` says which mode it is
 * in with a word at the bottom of the screen; a prompt says the last command
 * failed by changing one glyph. The second is the one that can sit on screen
 * permanently without asking for anything, which is exactly the property
 * section 05 protects.
 *
 * Zero interaction by construction. There is nothing to tap and nothing to
 * read; a user who has never noticed the glyph loses nothing, and one who has
 * gets the state for free.
 *
 * Pure; no Android imports. Unit-tested in `BitStatusTest`.
 */
object BitStatus {

    /**
     * True when any tracked app has passed a checkpoint it did not clear.
     *
     * Reads the checkpoint schedule rather than `penaltyMs` itself. The
     * ratchet only ever grows, so a non-zero `penaltyMs` says a checkpoint
     * went unpaid at some point in this cycle, not that one is unpaid now.
     * The glyph is about the second: it is an alert the user can act on by
     * clearing the gate.
     */
    fun penaltyAccruing(apps: Collection<AppSnapshot>): Boolean =
        apps.any { it.accumulatedMs > it.tierUnlockedUntilMs }
}
