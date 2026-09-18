package dev.molasses.core.lock

/**
 * A locked app cannot be untracked.
 *
 * ## The bypass this closes
 * `ForegroundEventRouter` routes only packages in the target set, and
 * `enforceLockIfNeeded` runs on the routed paths. So unticking an app in CFG
 * does not merely stop counting it, it stops the service receiving its events
 * at all, and a standing lock stops being enforced.
 *
 * Two taps, from inside the app, with no confirmation: open CFG, turn the app
 * off. That is the unlock this app deliberately does not have. `LockRegistry`
 * is extend-only, `CycleStateStore` has no single-package revoke and its doc
 * says there must not be one, and `$ block` routes through `LockRequest` so a
 * long duration needs confirming. All of that was reachable around.
 *
 * ## Why the decision is the whole toggle and not a predicate beside it
 * [toggled] returns the next list or null, rather than answering "is this
 * allowed" and leaving the caller to do the arithmetic. The caller's
 * arithmetic is where this would go wrong: the list is a read-modify-write
 * over a resolved set, that resolution has already inverted a control once
 * (see CLAUDE.md, "The stored target list is not the tracked set"), and a
 * guard bolted beside a read-modify-write is a guard that can be passed and
 * then have the wrong thing written anyway.
 *
 * So there is one function, it is pure, and refusing is a null rather than a
 * thrown exception or a silently unchanged list, because the caller has to
 * tell "refused" from "toggled to the same value" in order to say so on
 * screen.
 *
 * ## Why not in `TargetScope`
 * `TargetScope.resolve` turns a stored list into an effective one and knows
 * nothing about locks. Teaching it would make the resolver depend on the lock
 * registry, and both are read on the accessibility callback thread where the
 * cost of a wider dependency is real. The lock belongs at the write.
 *
 * ## It covers all three lock sources at once
 * `$ block`, `$ focus` and `$ bedtime` all end in `LockRegistry`, and this
 * asks the registry for a remaining time rather than asking which verb armed
 * it. A user cannot untick their way out of a focus session either, and that
 * needs no separate guard, which is the point of enforcing on the standing
 * lock rather than on the command that set it.
 *
 * Pure; no Android imports. Unit-tested in `TargetLockTest`.
 */
object TargetLock {

    /**
     * The target list after toggling [pkg], or **null when the toggle is
     * refused**.
     *
     * @param current the resolved tracked set, not the raw stored list. See
     *   CLAUDE.md: resolving one end of a read-modify-write is worse than
     *   resolving neither, and this is the other end.
     * @param lockRemainingMs from `LockRegistry.remainingMs`. Zero when no
     *   lock stands, which is also what an expired one reads as, so this
     *   needs no separate notion of expiry.
     */
    fun toggled(current: List<String>, pkg: String, lockRemainingMs: Long): List<String>? {
        if (pkg.isEmpty()) return null
        if (pkg !in current) return current + pkg
        // Tracking it is the only direction a lock forbids. Adding a locked
        // package back is not a bypass, and refusing it would strand anyone
        // who untracked an app before the lock was armed.
        if (lockRemainingMs > 0L) return null
        return current - pkg
    }

    /**
     * Whether this row's toggle is held by a lock, for the renderer.
     *
     * The same condition [toggled] refuses on, exposed so the control can be
     * disabled rather than merely inert. A tap that does nothing and says
     * nothing reads as a broken screen, and this app cannot afford one on the
     * screen where the user goes to check what it is doing.
     */
    fun isPinned(tracked: Boolean, lockRemainingMs: Long): Boolean =
        tracked && lockRemainingMs > 0L
}
