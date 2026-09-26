package dev.molasses.core.lock

import dev.molasses.core.safety.SensitivePackages

/**
 * A new never-draw-over prefix cannot cover a locked package.
 *
 * ## The bypass this closes
 * A package covered by a sensitive prefix gets no overlay at all, and
 * `LockEnforcement` stands down for it rather than draw over what might be a
 * payment screen. That is right for a bank. It also meant typing a locked
 * app's package into the SAFETY field voided the lock: no lock screen, no
 * gate, and the app open. The same two-taps-from-settings unlock that
 * [TargetLock] closed for untracking, arriving by a different field.
 *
 * So the write is guarded the same way, inside the same `updateData`
 * transform that reads the locks: a prefix that is being added and would
 * cover a package with a standing lock is refused. Prefixes already stored
 * stay, including one added before a lock was armed, because removing a
 * user's entry on an unrelated edit would be a surprise and keeping it
 * changes nothing that edit caused. Removing a prefix is never refused: it
 * only narrows suppression.
 *
 * Pure; no Android imports. Unit-tested in `PrefixLockTest`.
 */
object PrefixLock {

    /** A prefix that was not added, and the locked package it would have covered. */
    data class Refusal(val prefix: String, val lockedPkg: String)

    /**
     * What to store, and what was refused so a caller can say so. One
     * [Refusal] per locked package a refused prefix covers, so the screen can
     * name each lock it would have voided.
     */
    data class Result(val prefixes: List<String>, val refused: List<Refusal>)

    /**
     * @param stored the user prefixes already in the store.
     * @param requested the whole list the user submitted.
     * @param lockedPackages every package with a lock standing now.
     */
    fun admitted(stored: List<String>, requested: List<String>, lockedPackages: Collection<String>): Result {
        val existing = stored.map(::normalise).toSet()
        val keep = mutableListOf<String>()
        val refused = mutableListOf<Refusal>()
        for (prefix in requested.map(::normalise).filter { it.isNotEmpty() }.distinct()) {
            val voided = lockedPackages.filter { SensitivePackages.isSensitive(it, setOf(prefix)) }
            if (prefix !in existing && voided.isNotEmpty()) {
                voided.forEach { refused += Refusal(prefix, it) }
            } else {
                keep += prefix
            }
        }
        return Result(keep, refused)
    }

    /** The same folding `SensitivePackages.resolve` applies. */
    private fun normalise(prefix: String) = prefix.trim().lowercase()
}
