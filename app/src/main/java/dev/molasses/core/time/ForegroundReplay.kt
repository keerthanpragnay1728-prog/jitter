package dev.molasses.core.time

/**
 * Reconciler step 2, as a pure function.
 *
 * When the process dies mid-session the in-memory ticker is gone, so the only
 * honest source for "how long was Instagram actually in front of you" is the
 * system's own record. [ForegroundReconciler][dev.molasses.monitor.ForegroundReconciler]
 * pulls `UsageStatsManager.queryEvents()` and maps it onto [Transition]s; this
 * object does the pairing arithmetic.
 *
 * It is split out for one reason: a synthetic `UsageEvents` stream is
 * effectively unmockable on the JVM (`UsageEvents.Event` has no public
 * constructor and its fields are hidden), so a test that exercised the real
 * class would have to be instrumented. As a pure function over [Transition]s,
 * the interesting case -- a RESUMED with no matching PAUSED because we died
 * while it was still open -- is unit-testable. See `ForegroundReplayTest`.
 */
object ForegroundReplay {

    enum class Kind { RESUMED, PAUSED }

    data class Transition(val pkg: String, val kind: Kind, val wallMs: Long)

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
     * @param transitions need not be sorted; need not be balanced.
     * @param windowStartMs a RESUMED before this is credited only from here.
     * @param windowEndMs an unmatched RESUMED is credited up to here.
     * @param assumeOpenPkg the package `open_session_pkg` said was live when we
     *   died. If the system stream contains no RESUMED for it (the resume
     *   happened before [windowStartMs]) it is credited from [windowStartMs].
     */
    fun replay(
        transitions: List<Transition>,
        windowStartMs: Long,
        windowEndMs: Long,
        targets: Set<String>,
        assumeOpenPkg: String? = null,
    ): Result {
        require(windowEndMs >= windowStartMs) {
            "windowEndMs ($windowEndMs) precedes windowStartMs ($windowStartMs)"
        }

        val totals = mutableMapOf<String, Long>()
        // Per-package open timestamp. Per-package rather than a single slot
        // because the stream can interleave: A resumed, B resumed, A paused.
        val openSince = mutableMapOf<String, Long>()
        var lastUse: Long? = null

        assumeOpenPkg
            ?.takeIf { it.isNotEmpty() && it in targets }
            ?.let { openSince[it] = windowStartMs }

        val ordered = transitions
            .filter { it.pkg in targets }
            .sortedWith(compareBy({ it.wallMs }, { it.kind.ordinal }))

        for (t in ordered) {
            val at = t.wallMs.coerceIn(windowStartMs, windowEndMs)
            when (t.kind) {
                Kind.RESUMED -> {
                    // Duplicate RESUMED without a PAUSED (common: an app
                    // recreating its task). Keep the earlier open timestamp so
                    // we do not silently drop the interval.
                    openSince.putIfAbsent(t.pkg, at)
                }
                Kind.PAUSED -> {
                    val from = openSince.remove(t.pkg)
                    if (from != null && at > from) {
                        totals[t.pkg] = (totals[t.pkg] ?: 0L) + (at - from)
                        lastUse = maxOf(lastUse ?: at, at)
                    } else if (from != null) {
                        lastUse = maxOf(lastUse ?: at, at)
                    }
                    // A PAUSED with no matching RESUMED is dropped: the
                    // interval started before the replay window and was
                    // already accounted for in accumulated_ms.
                }
            }
        }

        // Whatever is still open at the end of the window was in front when we
        // looked. This is the mid-session-death case.
        var stillOpen: String? = null
        var stillOpenSince: Long? = null
        for ((pkg, from) in openSince) {
            if (windowEndMs > from) {
                totals[pkg] = (totals[pkg] ?: 0L) + (windowEndMs - from)
            }
            // If several are somehow open, report the most recent.
            if (stillOpenSince == null || from > stillOpenSince) {
                stillOpen = pkg
                stillOpenSince = from
            }
            lastUse = maxOf(lastUse ?: windowEndMs, windowEndMs)
        }

        return Result(
            foregroundMsByPkg = totals.filterValues { it > 0 },
            stillOpenPkg = stillOpen,
            stillOpenSinceWallMs = stillOpenSince,
            lastTargetUseWallMs = lastUse,
        )
    }
}
