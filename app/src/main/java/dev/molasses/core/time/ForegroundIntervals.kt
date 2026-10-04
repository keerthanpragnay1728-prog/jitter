package dev.molasses.core.time

/**
 * Foreground intervals from the system's event stream, each one bounded.
 * Pure. The one pairing rule `DayUsage` (the ledger and the gate's "today")
 * and `ForegroundReplay` (credit after a process death) both read.
 *
 * ## Why every interval needs a bound other than its own close
 * Pairing an app's resume with its own pause, and nothing else, trusts that
 * the pause arrives. On a device it did not: YONO SBI logged
 * ACTIVITY_RESUMED and then ACTIVITY_STOPPED with no ACTIVITY_PAUSED, three
 * times in a minute, and the stopped event was not read. Each interval stayed
 * open until whenever the ledger was read, so 13 seconds of use could read as
 * hours. The same rule credits a process death, where an interval left open
 * becomes accumulated friction time, and friction is never refunded.
 *
 * So an interval for a package closes at the earliest of:
 *  - its own close, [Kind.CLOSED], which is ACTIVITY_PAUSED or
 *    ACTIVITY_STOPPED, whichever comes first;
 *  - any other package's [Kind.RESUMED]: two apps are not in front at once;
 *  - [Kind.SCREEN_OFF], [Kind.KEYGUARD_SHOWN] or [Kind.SHUTDOWN];
 *  - the end of the window.
 *
 * An interval still open at the end runs to the end only if its package is
 * the most recent foreground event and the screen is interactive now. That
 * is the app the user is looking at. Otherwise nothing says when it ended,
 * so it closes at its last evidence: its own latest resume.
 *
 * ## What this costs, stated so it is not rediscovered
 * Split screen and other multi-resume layouts put two apps in front at once.
 * Bounding one by the other's resume undercounts the first. That is the
 * direction this rule chooses on purpose: an undercount in the ledger is a
 * smaller number, and an overcount in the reconciler is friction the user
 * cannot get back.
 *
 * Events need not be sorted. At one instant a resume sorts before every
 * close and bound, so a resume and its own pause at the same millisecond
 * are a zero-length interval and not a reason to reach back to the window
 * start.
 */
object ForegroundIntervals {

    /** In the order they sort at one instant. */
    enum class Kind { RESUMED, CLOSED, SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN }

    /** [pkg] is the package for [Kind.RESUMED] and [Kind.CLOSED], and empty for the device events. */
    data class Event(val kind: Kind, val wallMs: Long, val pkg: String = "")

    data class Interval(
        val pkg: String,
        val startMs: Long,
        val endMs: Long,
        /** Its only evidence in the window is its close: it was in front at the window start. */
        val fromBeforeWindow: Boolean = false,
        /** In front at the window end, so it ran to the end. */
        val runsToEnd: Boolean = false,
    ) {
        val ms: Long get() = endMs - startMs
    }

    /** A close for a package the window has not seen open. */
    enum class Orphan {
        /** The ledger: it was in front at the window start, so credit from there, bounded. */
        CREDIT_FROM_WINDOW_START,

        /** The reconciler: that time was already counted before the window. */
        DROP,
    }

    /**
     * @param interactiveNow the screen is interactive at [windowEndMs]. The
     *   caller reads `PowerManager.isInteractive`.
     * @param openAtStart a package known to be in front at [windowStartMs]
     *   with no event to say so, which is the reconciler's open session.
     */
    fun bound(
        events: List<Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        interactiveNow: Boolean,
        orphan: Orphan,
        openAtStart: String? = null,
    ): List<Interval> {
        require(windowEndMs >= windowStartMs) {
            "windowEndMs ($windowEndMs) precedes windowStartMs ($windowStartMs)"
        }
        val out = mutableListOf<Interval>()
        // Per package, in opening order. At most one is open after any
        // resume, because a resume closes every other.
        val open = LinkedHashMap<String, Long>()
        // A package's latest resume, which is where it closes when nothing
        // bounded it and it is not the app in front now.
        val lastResumed = mutableMapOf<String, Long>()
        val seen = mutableSetOf<String>()
        // The first bound of any kind in the window. A package in front at
        // the window start was out of front by then at the latest.
        var firstBound: Long? = null
        var lastForeground: String? = null
        // One app was in front at the window start, at most: the open
        // session, or the first close with nothing before it. A second such
        // close would overlap the first and is read as a stray.
        var startClaimed = false

        fun closeAll(at: Long, except: String? = null) {
            val it = open.entries.iterator()
            while (it.hasNext()) {
                val (pkg, from) = it.next()
                if (pkg == except) continue
                out += Interval(pkg, from, at)
                it.remove()
            }
        }

        openAtStart?.takeIf { it.isNotEmpty() }?.let {
            open[it] = windowStartMs
            lastResumed[it] = windowStartMs
            seen += it
            lastForeground = it
            startClaimed = true
        }

        val ordered = events
            .filter { e -> (e.kind != Kind.RESUMED && e.kind != Kind.CLOSED) || e.pkg.isNotEmpty() }
            .sortedWith(compareBy({ it.wallMs }, { it.kind.ordinal }))

        for (e in ordered) {
            val at = e.wallMs.coerceIn(windowStartMs, windowEndMs)
            when (e.kind) {
                Kind.RESUMED -> {
                    closeAll(at, except = e.pkg)
                    if (firstBound == null) firstBound = at
                    // A second resume with no close between (an app
                    // recreating its task, or moving between its own
                    // activities) keeps the earlier start.
                    open.putIfAbsent(e.pkg, at)
                    lastResumed[e.pkg] = at
                    seen += e.pkg
                    lastForeground = e.pkg
                }
                Kind.CLOSED -> {
                    val from = open.remove(e.pkg)
                    when {
                        from != null -> out += Interval(e.pkg, from, at)
                        e.pkg !in seen && !startClaimed && orphan == Orphan.CREDIT_FROM_WINDOW_START -> {
                            out += Interval(e.pkg, windowStartMs, minOf(at, firstBound ?: at), fromBeforeWindow = true)
                            startClaimed = true
                        }
                        // A close for a package already closed, by its own
                        // pause, a stop after it, or a bound: not evidence of
                        // anything. Or an orphan the caller already counted.
                        else -> Unit
                    }
                    seen += e.pkg
                }
                Kind.SCREEN_OFF, Kind.KEYGUARD_SHOWN, Kind.SHUTDOWN -> {
                    closeAll(at)
                    if (firstBound == null) firstBound = at
                }
            }
        }

        for ((pkg, from) in open) {
            out += if (interactiveNow && pkg == lastForeground) {
                Interval(pkg, from, windowEndMs, runsToEnd = true)
            } else {
                Interval(pkg, from, maxOf(from, lastResumed[pkg] ?: from))
            }
        }
        return out
    }
}
