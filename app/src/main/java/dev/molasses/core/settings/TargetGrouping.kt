package dev.molasses.core.settings

/**
 * The order and the headers for the target app list.
 *
 * ## Why grouping at all
 * Eighty installed apps, of which two or three are tracked, rendered as one
 * alphabetical list means the settings that matter are scattered through the
 * settings that do not. A user coming back to change Instagram's horizon has
 * to remember it begins with I.
 *
 * ## The grouping, and the one it is not
 * Tracked first, then untracked. Within tracked, by horizon and then by label.
 *
 * The obvious alternative is to group by horizon at the top level, which the
 * brief's "20m vs 25m" suggests. That is wrong for this ladder. The steps are
 * 10, 15, 18, 20, 25, 30, 40, 50 and 60 minutes, so grouping by every distinct
 * value can produce nine headers with one app each, which is not a grouping,
 * it is a list with decoration. Worse, the common case is the bad one: every
 * tracked app sits at the 25 minute default until someone deliberately moves
 * it, so the honest first cut is not "which horizon" but "is this app tracked
 * at all", and that is the question the screen is actually for.
 *
 * ## Headers appear only when they separate something
 * A header over the only bucket on screen says nothing, so:
 *
 *  * The tracked and untracked headers appear only when both buckets have
 *    something in them. On a fresh install nothing is tracked and the list is
 *    just a list, with no header claiming to divide it.
 *  * A horizon header appears only when more than one horizon is in use among
 *    tracked apps. One horizon is the default state, and a lone "25m" header
 *    over every tracked app is a label for a thing that is not varying.
 *
 * That last rule is what keeps the common screen quiet and makes the header
 * mean something the moment it shows up: seeing one at all tells the user
 * that their apps are no longer on the same setting.
 *
 * ## Filtering happens first
 * The host passes whatever survived the search box. Grouping a filtered list
 * is the only correct order: headers describe what is on screen, and a
 * "TRACKED" header over an empty run because the filter excluded all of them
 * is exactly the lie this file avoids everywhere else.
 *
 * Pure; no Android imports. Unit-tested in `TargetGroupingTest`.
 */
object TargetGrouping {

    /**
     * One installed app, reduced to what ordering needs.
     *
     * Its own type rather than the repository's `InstalledApp`, because that
     * one lives in the Android layer and this module cannot see it. The host
     * maps at the call site, which is one line and keeps the pure module
     * genuinely pure.
     */
    data class Entry(val pkg: String, val label: String)

    /** A rendered row: a header, or an app. */
    sealed interface Row {
        /** Apps with a curve running. */
        data object TrackedHeader : Row

        /** Everything else. */
        data object UntrackedHeader : Row

        /** Only when tracked apps disagree about their horizon. */
        data class HorizonHeader(val horizonMs: Long) : Row

        data class App(val entry: Entry, val tracked: Boolean, val horizonMs: Long?) : Row
    }

    /**
     * @param apps already filtered by the host's search box.
     * @param tracked the packages with a curve running.
     * @param horizonOf the horizon for a tracked package. Not called for an
     *   untracked one: an untracked app has no curve, so a horizon for it
     *   would be a number with nothing on the other end.
     */
    fun rows(
        apps: List<Entry>,
        tracked: Set<String>,
        horizonOf: (String) -> Long,
    ): List<Row> {
        val (on, off) = apps.partition { it.pkg in tracked }
        val out = mutableListOf<Row>()

        // A header over the only bucket on screen divides nothing.
        val bothPresent = on.isNotEmpty() && off.isNotEmpty()

        if (on.isNotEmpty()) {
            if (bothPresent) out += Row.TrackedHeader
            val withHorizon = on
                .map { it to horizonOf(it.pkg) }
                .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            val mixed = withHorizon.map { it.second }.distinct().size > 1
            var lastHorizon: Long? = null
            for ((entry, horizonMs) in withHorizon) {
                if (mixed && horizonMs != lastHorizon) {
                    out += Row.HorizonHeader(horizonMs)
                    lastHorizon = horizonMs
                }
                out += Row.App(entry, tracked = true, horizonMs = horizonMs)
            }
        }

        if (off.isNotEmpty()) {
            if (bothPresent) out += Row.UntrackedHeader
            for (entry in off.sortedBy { it.label.lowercase() }) {
                out += Row.App(entry, tracked = false, horizonMs = null)
            }
        }

        return out
    }

    /**
     * The app rows only, in render order.
     *
     * The alphabet rail indexes apps rather than headers, so it needs the same
     * order without them. Derived here rather than recomputed at the call
     * site, so the two orders cannot drift.
     */
    fun appsOf(rows: List<Row>): List<Row.App> = rows.filterIsInstance<Row.App>()
}
