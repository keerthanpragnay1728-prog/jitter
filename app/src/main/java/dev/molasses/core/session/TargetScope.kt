package dev.molasses.core.session

/**
 * What the service tells the platform to send it, and what the router accepts.
 *
 * ## The empty-list trap this exists to close
 * `AccessibilityServiceInfo.packageNames` treats null as "every package on the
 * device". The previous `applyTargets` did:
 *
 * ```
 * info.packageNames = if (packages.isEmpty()) null else (packages + own)
 * ```
 *
 * so a stored target list that came back empty produced the worst possible
 * pairing: the platform delivers every event from every app, and
 * [ForegroundEventRouter] ignores all of them because `targets` is empty.
 * Maximum battery cost, zero behaviour, and nothing in the log.
 *
 * ## Empty meant two things, and now it does not
 * [resolve] used to take a bare list, and an empty one always meant "use the
 * defaults". That is right for a fresh install and wrong for a user who
 * turned every target off: unticking the last app wrote empty, and the
 * defaults came back. Tracking nothing was not expressible.
 *
 * [Selection] carries the list together with whether it is an answer. The two
 * halves are one type rather than two values on purpose. Every bug this
 * repository has had in this area came from reading one half without the
 * other, and a pair that cannot be taken apart cannot be half read.
 *
 * Pure; no Android imports. Unit-tested in `TargetScopeTest`.
 */
object TargetScope {

    /**
     * The stored target list, and whether the user has ever answered.
     *
     * @param stored exactly what is in the store, blanks and all.
     * @param chosen the store's `targets_chosen` flag. False on every install
     *   that predates it, which is correct for all of them: see the proto.
     */
    data class Selection(val stored: List<String>, val chosen: Boolean)

    /**
     * The set the router matches against.
     *
     * Blank entries are dropped before the emptiness test, so a stored list of
     * `["", "  "]` is empty for this purpose rather than a scope of two
     * packages that can never match.
     *
     * A non-empty list wins regardless of [Selection.chosen], because a list
     * with packages in it is data and the flag is only ever a tiebreak for
     * empty.
     */
    fun resolve(selection: Selection, defaults: List<String>): Set<String> {
        val cleaned = selection.stored.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isNotEmpty()) return cleaned.toSet()
        return if (selection.chosen) emptySet() else defaults.toSet()
    }

    /**
     * The value for `packageNames`. Never null, and never empty.
     *
     * [ownPackage] is always included: without it no event arrives when the
     * user leaves a target app, so the session never closes and time keeps
     * accruing against an app that is no longer on screen. That is asserted in
     * `AccessibilityConfigTest` for the manifest and here for the runtime
     * path, because both can drop it independently.
     *
     * It is also what keeps the null trap closed once tracking nothing became
     * expressible. With no targets this returns our own package alone, which
     * is narrow and correct: the platform sends us our own launcher events and
     * nothing else.
     *
     * ## There used to be a defaults fallback here and it had to go
     * This substituted `defaults` whenever the set arrived empty, which was a
     * second answer to a question [resolve] had already answered. Once an
     * empty set can mean "the user chose nothing", substituting here would
     * have overridden that choice at the platform layer: the service would
     * have gone on receiving every default's events for a user who asked for
     * none. Not merely a guard that cannot fire, but one that fires wrongly.
     */
    fun packageNames(targets: Set<String>, ownPackage: String): Array<String> =
        (targets + ownPackage).toTypedArray()

    /** True when the stored list was unusable and [resolve] substituted. */
    fun usedFallback(selection: Selection): Boolean =
        !selection.chosen && selection.stored.none { it.trim().isNotEmpty() }

    /**
     * True when the user has deliberately chosen to track nothing.
     *
     * Distinct from [usedFallback] and the reason the flag exists. This is a
     * configured state with a real consequence, no app is gated at all, and a
     * screen that showed it the same way it shows a fresh install would be
     * hiding the one thing the user most needs to see.
     */
    fun trackingNothing(selection: Selection): Boolean =
        selection.chosen && selection.stored.none { it.trim().isNotEmpty() }
}
