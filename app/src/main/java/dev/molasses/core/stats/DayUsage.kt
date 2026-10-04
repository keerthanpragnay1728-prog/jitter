package dev.molasses.core.stats

import dev.molasses.core.time.ForegroundIntervals

/**
 * Per-app foreground milliseconds over an arbitrary window, from the system's
 * own event stream.
 *
 * ## Why not `queryAndAggregateUsageStats`
 * The ledger page used it and reported a number consistently larger than
 * Digital Wellbeing's for the same day. Three reasons, and only the first is
 * obvious:
 *
 *  1. It counts every package, including our own. Jitter is the home
 *     screen, so its own foreground time is most of the gap between one app
 *     and the next. Counting it inflates the day by however long the user
 *     spent looking at the launcher.
 *  2. `queryAndAggregateUsageStats` merges the daily buckets that *overlap*
 *     the requested range, and a bucket's `totalTimeInForeground` is the
 *     whole bucket's, not the overlapping part. A query from local midnight
 *     therefore picks up the bucket that straddles midnight and adds all of
 *     the previous evening to today.
 *  3. Buckets are rolled up on the system's own schedule, so the most recent
 *     minutes may be missing entirely while yesterday's are double counted.
 *
 * `queryEvents` has none of that: it is a raw, timestamped stream, and the
 * pairing clips every interval to the window.
 *
 * ## What it shares with [ForegroundReplay][dev.molasses.core.time.ForegroundReplay]
 * The pairing. Both read [ForegroundIntervals], so an interval is bounded the
 * same way in the ledger as in the reconciler's credit, and an interval with
 * no close of its own cannot run to the moment of reading in one and not the
 * other.
 *
 * They differ in what one event means: a close with nothing before it. Here
 * the window is the day and there is no prior accounting, so an app that was
 * in front before midnight is owed its time from midnight, bounded like any
 * other interval. There, the interval before the window was already counted
 * in `accumulated_ms`. Each caller names its answer, as
 * [ForegroundIntervals.Orphan], so neither inherits the other's by default.
 *
 * Pure, so `DayUsageTest` can exercise the cases a synthetic `UsageEvents`
 * stream cannot: its `Event` has no public constructor.
 */
object DayUsage {

    /**
     * Two intervals closer together than this are one visit.
     *
     * A multi-activity app fires PAUSED and RESUMED crossing between its own
     * screens, and counting those as separate opens would report a user who
     * opened Instagram twice and tapped through four profiles as six opens.
     * Two seconds is longer than any within-app transition and far shorter
     * than any real return to an app, which makes the boundary unambiguous in
     * both directions.
     *
     * The number is therefore honestly a count of visits, and is labelled as
     * opens because that is what a visit is to the person having it.
     */
    const val VISIT_GAP_MS = 2_000L

    data class Entry(
        val pkg: String,
        val foregroundMs: Long,
        /**
         * Visits, not resumes. See [VISIT_GAP_MS] for what separates two.
         */
        val opens: Int = 0,
    )

    /**
     * @param underFloor apps with some time today, all of it under the floor.
     * @param pastCap apps over the floor that did not fit in the rows.
     */
    data class Distribution(val rows: List<Entry>, val underFloor: Int, val pastCap: Int)

    data class Result(
        /** Every package with a non-zero interval, longest first. */
        val apps: List<Entry>,
    ) {
        /**
         * The headline. Sums every app, not the leaders: a day spent in
         * fifteen short visits is exactly the day this app exists to show,
         * and truncating the list before summing hid it.
         */
        val totalMs: Long get() = apps.sumOf { it.foregroundMs }

        /**
         * The distribution list, which is a different thing from the total.
         * It is a bar chart with a fixed number of rows, so it takes the
         * leaders and applies a floor: a row reading `0m` carries no
         * information and pushes a real one off the screen.
         *
         * What the floor and the cap leave out is counted rather than
         * dropped. A list that silently omits an app the user just used
         * reads as the app not having been counted at all, which is the
         * report that brought this in: Chess, opened briefly, was missing
         * with nothing on the page to say why.
         */
        fun distribution(count: Int, minMs: Long): Distribution {
            val above = apps.filter { it.foregroundMs >= minMs }
            return Distribution(
                rows = above.take(count),
                underFloor = apps.size - above.size,
                pastCap = (above.size - count).coerceAtLeast(0),
            )
        }

        /** One package's row, or null when it had no foreground time. */
        fun entry(pkg: String): Entry? = apps.firstOrNull { it.pkg == pkg }
    }

    /**
     * @param events need not be sorted and need not be balanced. Every
     *   package's, not only the ones being asked about: another app's resume
     *   is what bounds an interval whose own close never arrived.
     * @param interactiveNow the screen is interactive at [windowEndMs]. See
     *   [ForegroundIntervals] for why it matters.
     * @param exclude packages to leave out of the result. The launcher's own
     *   package belongs here. They still bound everyone else's intervals: the
     *   console in front means nothing else is.
     */
    fun replay(
        events: List<ForegroundIntervals.Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        interactiveNow: Boolean,
        exclude: Set<String> = emptySet(),
    ): Result {
        // The pairing, and every bound on it, is the shared rule. An app in
        // front across the window start is owed its time from the start, so
        // a close with nothing before it is credited from there.
        val intervals = ForegroundIntervals.bound(
            events = events,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            interactiveNow = interactiveNow,
            orphan = ForegroundIntervals.Orphan.CREDIT_FROM_WINDOW_START,
        ).filter { it.pkg !in exclude }

        val totals = mutableMapOf<String, Long>()
        val opens = mutableMapOf<String, Int>()
        for ((pkg, list) in intervals.groupBy { it.pkg }) {
            totals[pkg] = list.sumOf { it.ms }
            // A visit is an interval more than the gap after the one before
            // it. Zero-length intervals take part, as a resume always did.
            var lastEnd: Long? = null
            var count = 0
            for (i in list.sortedBy { it.startMs }) {
                val prev = lastEnd
                if (prev == null || i.startMs - prev >= VISIT_GAP_MS) count++
                lastEnd = maxOf(prev ?: i.endMs, i.endMs)
            }
            opens[pkg] = count
        }

        val apps = totals
            .filterValues { it > 0L }
            .map { (pkg, ms) -> Entry(pkg, ms, opens[pkg] ?: 0) }
            // Package name breaks ties so the list does not reshuffle between
            // two recompositions that read the same events.
            .sortedWith(compareByDescending<Entry> { it.foregroundMs }.thenBy { it.pkg })

        return Result(apps)
    }
}
