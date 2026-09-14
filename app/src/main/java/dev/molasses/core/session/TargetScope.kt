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
 * Maximum battery cost, zero behaviour, and nothing in the log. A DataStore
 * file written before `DEFAULT_TARGETS` was added to the serializer lands
 * exactly here.
 *
 * [resolve] makes that unreachable: an empty stored list falls back to the
 * defaults, so the scope is always narrow and always matches what the router
 * will accept.
 *
 * Pure; no Android imports. Unit-tested in `TargetScopeTest`.
 */
object TargetScope {

    /**
     * The set the router matches against.
     *
     * Blank entries are dropped before the emptiness test, so a stored list of
     * `["", "  "]` is empty for this purpose rather than a scope of two
     * packages that can never match.
     */
    fun resolve(stored: List<String>, defaults: List<String>): Set<String> {
        val cleaned = stored.map { it.trim() }.filter { it.isNotEmpty() }
        return if (cleaned.isEmpty()) defaults.toSet() else cleaned.toSet()
    }

    /**
     * The value for `packageNames`. Never null, never empty.
     *
     * [ownPackage] is always included: without it no event arrives when the
     * user leaves a target app, so the session never closes and time keeps
     * accruing against an app that is no longer on screen. That is asserted in
     * `AccessibilityConfigTest` for the manifest and here for the runtime
     * path, because both can drop it independently.
     */
    fun packageNames(targets: Set<String>, ownPackage: String, defaults: List<String>): Array<String> {
        val effective = if (targets.isEmpty()) defaults.toSet() else targets
        return (effective + ownPackage).toTypedArray()
    }

    /** True when the stored list was unusable and [resolve] substituted. */
    fun usedFallback(stored: List<String>): Boolean =
        stored.none { it.trim().isNotEmpty() }
}
