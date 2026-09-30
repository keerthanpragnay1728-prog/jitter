package dev.molasses.core.stats

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
 * pairing arithmetic here clips every interval to the window.
 *
 * ## Why this is not [ForegroundReplay][dev.molasses.core.time.ForegroundReplay]
 * That one answers a different question. It replays only the monitored
 * targets, to reconcile a session the process died in the middle of, and it
 * deliberately *drops* a PAUSED with no matching RESUMED because the interval
 * before the window was already counted in `accumulated_ms`.
 *
 * Here the window is the day and there is no prior accounting, so the same
 * event means the opposite: an app that was in front before midnight is owed
 * its time from midnight to the pause. Merging the two functions would mean a
 * flag that reverses the meaning of an event, which is how one of them ends
 * up wrong.
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

    enum class Kind { RESUMED, PAUSED }

    data class Transition(val pkg: String, val kind: Kind, val wallMs: Long)

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
     * @param transitions need not be sorted and need not be balanced.
     * @param exclude packages to leave out entirely. The launcher's own
     *   package belongs here.
     */
    fun replay(
        transitions: List<Transition>,
        windowStartMs: Long,
        windowEndMs: Long,
        exclude: Set<String> = emptySet(),
    ): Result {
        require(windowEndMs >= windowStartMs) {
            "windowEndMs ($windowEndMs) precedes windowStartMs ($windowStartMs)"
        }

        val totals = mutableMapOf<String, Long>()
        // Per package, because the stream interleaves: A resumed, B resumed,
        // A paused is an ordinary sequence when an app hands off to another.
        val openSince = mutableMapOf<String, Long>()
        val seen = mutableSetOf<String>()
        val opens = mutableMapOf<String, Int>()
        /** When each package was last not in front, for the visit rule. */
        val lastClosed = mutableMapOf<String, Long>()

        val ordered = transitions
            .filter { it.pkg.isNotEmpty() && it.pkg !in exclude }
            .sortedWith(compareBy({ it.wallMs }, { it.kind.ordinal }))

        for (t in ordered) {
            val at = t.wallMs.coerceIn(windowStartMs, windowEndMs)
            when (t.kind) {
                Kind.RESUMED -> {
                    if (t.pkg !in openSince) {
                        val closed = lastClosed[t.pkg]
                        if (closed == null || at - closed >= VISIT_GAP_MS) {
                            opens[t.pkg] = (opens[t.pkg] ?: 0) + 1
                        }
                    }
                    // A second RESUMED with no PAUSED between (an app
                    // recreating its task) keeps the earlier timestamp, so
                    // the interval is not silently dropped.
                    openSince.putIfAbsent(t.pkg, at)
                }
                Kind.PAUSED -> {
                    // A PAUSED whose RESUMED is the first thing we know about
                    // this package happened before the window opened. Credit
                    // from the window start, clipped: that is the part of the
                    // interval that falls inside today.
                    val from = openSince.remove(t.pkg)
                        ?: if (t.pkg in seen) {
                            null
                        } else {
                            // In front since before the window opened. That
                            // is a visit the user is having inside it, so it
                            // counts as one.
                            opens[t.pkg] = (opens[t.pkg] ?: 0) + 1
                            windowStartMs
                        }
                    if (from != null && at > from) {
                        totals[t.pkg] = (totals[t.pkg] ?: 0L) + (at - from)
                    }
                    if (from != null) lastClosed[t.pkg] = at
                }
            }
            seen += t.pkg
        }

        // Whatever is still open ran up to the moment we looked.
        for ((pkg, from) in openSince) {
            if (windowEndMs > from) {
                totals[pkg] = (totals[pkg] ?: 0L) + (windowEndMs - from)
            }
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
