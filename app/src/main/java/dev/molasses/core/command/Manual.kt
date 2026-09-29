package dev.molasses.core.command

/**
 * The inline manual, generated from the registry.
 *
 * ## Why generated and not written
 * A hand-written help page is a second list of commands, and a second list
 * drifts: a verb gets added and the manual does not mention it, or a verb is
 * removed and the manual still offers it. The registry already has to be
 * complete, because `CommandRegistryTest` round-trips it against the `Command`
 * type in both directions. Generating from it means a command documents itself
 * or does not exist.
 *
 * ## Unavailable commands are listed, not hidden
 * Hiding them would make the manual lie by omission: `$ block` parses, and a
 * user who has seen it somewhere and types it deserves to be told that locks
 * are not enforced yet rather than that there is no such command. So the row
 * stays, dimmed, carrying the reason the dispatcher would have given.
 *
 * That also means the manual is live rather than static. A device with no
 * clock app dims `alarm` and says why, on that device only.
 *
 * ## It used to feed a rotating placeholder as well
 * `suggestable` and `at` are gone with it. The prompt cycled through
 * available commands every five seconds, and a hint that moves while you are
 * reading it is a hint you stop reading. One static line pointing at `?` does
 * the same job once, and the page it points at is this one.
 *
 * Pure; no Android imports. Unit-tested in `ManualTest`.
 */
object Manual {

    /**
     * One line of the manual.
     *
     * Resource ids rather than strings, because this module cannot see `R`
     * and because the host resolves copy at the call site. [reasonKey] is
     * null exactly when [available] is true.
     */
    data class Row(
        val verb: String,
        val usageKey: Int,
        val descriptionKey: Int,
        val available: Boolean,
        val reasonKey: Int?,
    )

    /**
     * Every command, in registry order.
     *
     * Registry order groups commands by the surface they land on, which is
     * also roughly how a user thinks about them. Sorting available ones first
     * would reorder the page every time the service died.
     */
    fun rows(
        registry: CommandRegistry,
        availability: (CommandSpec) -> Availability,
    ): List<Row> = registry.specs.map { spec ->
        when (val a = availability(spec)) {
            Availability.Available -> Row(spec.verb, spec.usageKey, spec.descriptionKey, true, null)
            is Availability.Unavailable ->
                Row(spec.verb, spec.usageKey, spec.descriptionKey, false, a.reasonKey)
        }
    }
}
