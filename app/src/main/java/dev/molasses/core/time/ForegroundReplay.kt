package dev.molasses.core.time

/**
 * Reconciler step 2, as a pure function.
 *
 * When the process dies mid-session the in-memory ticker is gone, so the only
 * honest source for "how long was Instagram actually in front of you" is the
 * system's own record. [ForegroundReconciler][dev.molasses.monitor.ForegroundReconciler]
 * pulls `UsageStatsManager.queryEvents()` and maps it onto
 * [ForegroundIntervals.Event]s; [ForegroundIntervals] does the pairing and
 * bounding, the same rule the ledger uses, and this object adds up the
 * targets.
 *
 * It is split out for one reason: a synthetic `UsageEvents` stream is
 * effectively unmockable on the JVM (`UsageEvents.Event` has no public
 * constructor and its fields are hidden), so a test that exercised the real
 * class would have to be instrumented. As a pure function over events, the
 * interesting cases are unit-testable: a RESUMED with no matching PAUSED
 * because we died while it was still open, and one whose PAUSED never came
 * at all. See `ForegroundReplayTest`.
 */
object ForegroundReplay {

    data class Result(
        /** Real foreground milliseconds per package over the replayed range. */
        val foregroundMsByPkg: Map<String, Long>,
        /** Package still in front at [windowEndMs], if any. */
        val stillOpenPkg: String?,
        val stillOpenSinceWallMs: Long?,
        /** Wall time of the last moment any target app was in front. */
        val lastTargetUseWallMs: Long?,
    ) {
        val totalMs: Long get() = foregroundMsByPkg.values.sum()
    }

    /**
     * @param events need not be sorted; need not be balanced. Every
     *   package's and the device's, not only the targets': another app's
     *   resume, the screen going off or a shutdown is what bounds a target's
     *   interval when its own close never arrived. See [ForegroundIntervals].
     * @param windowStartMs a RESUMED before this is credited only from here.
     * @param windowEndMs the furthest an interval is credited, and only for
     *   the app in front with the screen interactive.
     * @param interactiveNow `PowerManager.isInteractive` at reconcile time.
     * @param assumeOpenPkg the package `open_session_pkg` said was live when we
     *   died. If the system stream contains no RESUMED for it (the resume
     *   happened before [windowStartMs]) it is open from [windowStartMs], and
     *   bounded like any other interval.
     */
    fun replay(
        events: List<ForegroundIntervals.Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        targets: Set<String>,
        interactiveNow: Boolean,
        assumeOpenPkg: String? = null,
    ): Result {
        // A close with nothing before it is dropped: the interval started
        // before the replay window and was already counted in accumulated_ms.
        val intervals = ForegroundIntervals.bound(
            events = events,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            interactiveNow = interactiveNow,
            orphan = ForegroundIntervals.Orphan.DROP,
            openAtStart = assumeOpenPkg?.takeIf { it in targets },
        ).filter { it.pkg in targets }

        val totals = mutableMapOf<String, Long>()
        for (i in intervals) totals[i.pkg] = (totals[i.pkg] ?: 0L) + i.ms
        // The one still in front at the window end, if any. Only the app in
        // front with the screen on runs to the end; see ForegroundIntervals.
        val stillOpen = intervals.filter { it.runsToEnd }.maxByOrNull { it.startMs }

        return Result(
            foregroundMsByPkg = totals.filterValues { it > 0 },
            stillOpenPkg = stillOpen?.pkg,
            stillOpenSinceWallMs = stillOpen?.startMs,
            lastTargetUseWallMs = intervals.maxOfOrNull { it.endMs },
        )
    }
}
