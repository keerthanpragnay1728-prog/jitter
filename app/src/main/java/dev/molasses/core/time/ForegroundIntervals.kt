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
 *  - the last of its resumed activities closing (see below);
 *  - any other package's [Kind.RESUMED]: two apps are not in front at once;
 *  - [Kind.SCREEN_OFF], [Kind.KEYGUARD_SHOWN] or [Kind.SHUTDOWN];
 *  - the end of the window.
 *
 * An interval still open at the end runs to the end only if its package is
 * the most recent foreground event and the screen is interactive now. That
 * is the app the user is looking at. Otherwise nothing says when it ended,
 * so it closes at its last evidence: its own latest resume.
 *
 * ## Per activity, because an app is several
 * A package is in front while any of its activities is resumed, tracked by
 * package and class name. Its own [Kind.PAUSED] or [Kind.STOPPED] removes
 * only the matching activity, and the interval ends when none is left.
 *
 * The first version of this rule closed the package on any pause or stop of
 * the package, and that undercounted every app with more than one screen.
 * Moving within an app logs X paused, Y resumed, then X stopped, and X's
 * stop closed the interval Y had just opened, so everything after the first
 * screen change went uncounted until the next resume: the ledger read 1 h
 * 56 min on a day Digital Wellbeing put at 3 h 40 min.
 *
 * A stop for an activity not in the set is ignored. A stop for an activity
 * that is in the set but was paused since it last resumed is the old
 * instance's stop and is ignored too, which is what keeps two screens of the
 * same class (one chat, then another) open across the change. A stop with no
 * pause before it, the shape YONO SBI logged, removes the activity as
 * before. An event with no class name closes the whole package, which is the
 * package-only rule this replaced, so a stream without class names reads
 * exactly as it did.
 *
 * ## What this costs, stated so it is not rediscovered
 * Split screen and other multi-resume layouts put two apps in front at once.
 * Bounding one by the other's resume undercounts the first. That is the
 * direction this rule chooses on purpose: an undercount in the ledger is a
 * smaller number, and an overcount in the reconciler is friction the user
 * cannot get back.
 *
 * Each screen change inside an app costs the gap between the old screen's
 * pause and the new one's resume, when no activity of the app is resumed.
 * That is tens of milliseconds a change, and it is kept rather than bridged,
 * because bridging a gap is a guess about what was in front during it.
 *
 * Events need not be sorted. At one instant a resume sorts before every
 * close and bound, so a resume and its own pause at the same millisecond
 * are a zero-length interval and not a reason to reach back to the window
 * start.
 */
object ForegroundIntervals {

    /** In the order they sort at one instant. */
    enum class Kind { RESUMED, PAUSED, STOPPED, SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN }

    /**
     * [pkg] and [cls] are the package and activity class for [Kind.RESUMED],
     * [Kind.PAUSED] and [Kind.STOPPED], and empty for the device events. An
     * empty [cls] on an activity event means the class is unknown, and that
     * event is read by package alone.
     */
    data class Event(val kind: Kind, val wallMs: Long, val pkg: String = "", val cls: String = "")

    private val Kind.isActivity: Boolean get() = this == Kind.RESUMED || this == Kind.PAUSED || this == Kind.STOPPED

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
        // Per open package, the activities resumed in it, by class. Empty
        // string is an activity of unknown class: the open session seeded at
        // the window start, or an event with no class name.
        val resumed = mutableMapOf<String, MutableSet<String>>()
        // Per package and class, activities paused and not yet stopped. Their
        // stop is the old instance's and must not close a new one.
        val stopsOwed = mutableMapOf<String, MutableMap<String, Int>>()

        fun close(pkg: String, at: Long) {
            val from = open.remove(pkg) ?: return
            out += Interval(pkg, from, at)
            resumed.remove(pkg)
        }

        fun closeAll(at: Long, except: String? = null) {
            for (pkg in open.keys.toList()) {
                if (pkg == except) continue
                close(pkg, at)
                // A bound ends every activity of the package at once, so no
                // stop is owed any more: a later one finds nothing to close.
                stopsOwed.remove(pkg)
            }
        }

        /** Remove [cls] from [pkg]'s set, or the unknown one if [cls] is not in it. True when it removed one. */
        fun removeActivity(pkg: String, cls: String): Boolean {
            val set = resumed[pkg] ?: return false
            return set.remove(cls) || set.remove("")
        }

        openAtStart?.takeIf { it.isNotEmpty() }?.let {
            open[it] = windowStartMs
            resumed[it] = mutableSetOf("")
            lastResumed[it] = windowStartMs
            seen += it
            lastForeground = it
            startClaimed = true
        }

        val ordered = events
            .filter { e -> !e.kind.isActivity || e.pkg.isNotEmpty() }
            .sortedWith(compareBy({ it.wallMs }, { it.kind.ordinal }))

        for (e in ordered) {
            val at = e.wallMs.coerceIn(windowStartMs, windowEndMs)
            when (e.kind) {
                Kind.RESUMED -> {
                    // Another package's resume clears every other package's
                    // set: two apps are not in front at once.
                    closeAll(at, except = e.pkg)
                    if (firstBound == null) firstBound = at
                    // A second resume with no close between (an app
                    // recreating its task, or moving between its own
                    // activities) keeps the earlier start.
                    open.putIfAbsent(e.pkg, at)
                    resumed.getOrPut(e.pkg) { mutableSetOf() } += e.cls
                    lastResumed[e.pkg] = at
                    seen += e.pkg
                    lastForeground = e.pkg
                }
                Kind.PAUSED, Kind.STOPPED -> {
                    if (e.pkg in open) {
                        val owed = stopsOwed.getOrPut(e.pkg) { mutableMapOf() }
                        val empties = when {
                            // No class: read by package alone, as the rule
                            // before this one did.
                            e.cls.isEmpty() -> true
                            e.kind == Kind.PAUSED -> {
                                if (e.cls in resumed[e.pkg].orEmpty()) owed[e.cls] = (owed[e.cls] ?: 0) + 1
                                removeActivity(e.pkg, e.cls)
                                resumed[e.pkg].isNullOrEmpty()
                            }
                            // The stop of an instance already paused: a new
                            // instance of the same class may be in front now.
                            (owed[e.cls] ?: 0) > 0 -> {
                                owed[e.cls] = owed.getValue(e.cls) - 1
                                false
                            }
                            // A stop with no pause before it closes its
                            // activity; one for an activity not in the set
                            // closes nothing.
                            else -> removeActivity(e.pkg, e.cls) && resumed[e.pkg].isNullOrEmpty()
                        }
                        if (empties) close(e.pkg, at)
                    } else if (e.pkg !in seen && !startClaimed && orphan == Orphan.CREDIT_FROM_WINDOW_START) {
                        out += Interval(e.pkg, windowStartMs, minOf(at, firstBound ?: at), fromBeforeWindow = true)
                        startClaimed = true
                    } else if (e.kind == Kind.STOPPED) {
                        // A stop owed by an activity whose pause closed the
                        // package: settled here, closing nothing.
                        stopsOwed[e.pkg]?.let { owed -> owed[e.cls]?.let { if (it > 0) owed[e.cls] = it - 1 } }
                    }
                    // Otherwise a close for a package already closed, by its
                    // own last activity or by a bound: not evidence of
                    // anything. Or an orphan the caller already counted.
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
