package dev.molasses.core.settings

import dev.molasses.core.time.StampedInstant

/**
 * Untracking a social app is not permanent: it comes back after [GRACE_DAYS]
 * days. Pure.
 *
 * ## Why only social apps
 * The untrack cooling-off slows the decision down. It does not stop a user
 * who waits it out once and then never thinks about the app again, and for a
 * feed built to be reopened that is the whole of the risk. A calculator or a
 * banking app that was tracked by mistake has no reason to come back, so
 * everything outside [inScope] untracks permanently, as it always did.
 *
 * Scope is the platform's own answer first, `ApplicationInfo.category ==
 * CATEGORY_SOCIAL`, which the caller reads and passes in so this stays pure.
 * Many social apps do not declare a category, so [FALLBACK] names the ones
 * that matter by package. YouTube is excluded by name even if it ever
 * declares itself social: it is where lectures and tutorials live, and a
 * user who untracked it for that reason should not have to do it weekly.
 *
 * ## Which clock
 * The grace is a relief: while it runs the app has no friction at all, so
 * ending it late is the bypass. It follows the RELIEF rule of
 * `ClockTamperClamp`: time served is credited the larger way, which is the
 * same as saying time left is the smaller of what the two clocks say. So it
 * ends when either clock reaches the deadline, and winding the wall clock
 * back does not extend it.
 *
 * A reboot ends it. Relief must not trust a wall clock across a boot (see
 * CLAUDE.md, "Which clock a deadline is measured on"), and a grace of days is
 * long enough that a reboot inside it is the ordinary case, not the attack.
 * Ending early is the direction that errs toward friction, so the app is
 * tracked again from the first connect after a restart. The cooling-off
 * panel says so before the user confirms, and CFG's row says "or sooner
 * after a restart" beside the date.
 *
 * ## Stored as the deadline, and read as one
 * [Sunset.deadline] is the instant the grace ends, stamped on all three
 * clocks at the moment of confirming, each moved on by [GRACE_MS].
 * [remainingMs] reads the deadline and nothing else, so [GRACE_MS] decides
 * only what a new untrack is given. A sunset stored under an earlier grace
 * ends on the date its user was shown, with no migration.
 *
 * That is why it measures from the deadline rather than recovering the start
 * as the deadline minus [GRACE_MS]. That recovery was exact only while the
 * grace never changed. When the grace was shortened from its first value of
 * 7 days, a pending sunset from before would have been read as starting in
 * the future: its served time floors at zero, and CFG would have shown "now
 * plus the new grace" for days, although the re-arm itself still landed on
 * the original day.
 */
object UntrackSunset {

    /** How long a new untrack of a social app lasts. The only copy of the number. */
    const val GRACE_DAYS: Int = 3
    const val GRACE_MS: Long = GRACE_DAYS * 24L * 60 * 60 * 1000

    const val YOUTUBE = "com.google.android.youtube"

    /** Instagram, Facebook and Facebook Lite, X, TikTok (both of its package names), Snapchat. */
    val FALLBACK: Set<String> = setOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
    )

    data class Sunset(val pkg: String, val deadline: StampedInstant)

    /**
     * @param categorySocial `ApplicationInfo.category == CATEGORY_SOCIAL`, or
     *   false when the caller could not read it.
     */
    fun inScope(pkg: String, categorySocial: Boolean): Boolean =
        pkg.isNotEmpty() && pkg != YOUTUBE && (categorySocial || pkg in FALLBACK)

    fun grant(pkg: String, now: StampedInstant): Sunset = Sunset(
        pkg = pkg,
        deadline = StampedInstant(
            wallMs = now.wallMs + GRACE_MS,
            elapsedMs = now.elapsedMs + GRACE_MS,
            bootId = now.bootId,
        ),
    )

    /**
     * Grace left, never negative, read from the stored deadline alone. Zero
     * across a reboot. The smaller of what the two clocks leave, which is the
     * RELIEF rule: a wall clock wound back leaves more by the wall and the
     * same by the monotonic clock, so it gains nothing. See the class doc.
     */
    fun remainingMs(sunset: Sunset, now: StampedInstant): Long {
        val d = sunset.deadline
        if (now.bootId != d.bootId) return 0L
        val byWall = d.wallMs - now.wallMs
        val byElapsed = d.elapsedMs - now.elapsedMs
        return minOf(byWall, byElapsed).coerceAtLeast(0L)
    }

    fun due(sunset: Sunset, now: StampedInstant): Boolean = remainingMs(sunset, now) == 0L

    /**
     * When tracking resumes, on the wall clock, for display. Measured from
     * now by what is left rather than read off the stored wall stamp, so a
     * clock that was moved shows the same date the check will act on.
     */
    fun resumesAtWallMs(sunset: Sunset, now: StampedInstant): Long = now.wallMs + remainingMs(sunset, now)

    /**
     * The sunsets after a target is switched on or off.
     *
     * Tracked again by hand: its sunset goes, or the row would promise a
     * re-arm of an app that is already tracked. Untracked through the
     * cooling-off and in scope: a fresh sunset replaces any old one.
     * Anything else leaves the list as it was.
     */
    fun afterToggle(
        sunsets: List<Sunset>,
        pkg: String,
        trackedAfter: Boolean,
        grantsSunset: Boolean,
        now: StampedInstant,
    ): List<Sunset> {
        val others = sunsets.filter { it.pkg != pkg }
        return when {
            trackedAfter -> others
            grantsSunset -> others + grant(pkg, now)
            else -> sunsets
        }
    }

    /** What a re-arm writes: the target list with the due apps added back, and the sunsets still running. */
    data class Rearm(val targets: List<String>, val remaining: List<Sunset>, val rearmed: List<String>)

    /**
     * Put every due app back on the target list.
     *
     * @param current the resolved target list, never the raw stored one: a
     *   re-arm that read an empty stored list as empty would drop the
     *   defaults on a fresh install. See `TargetScope.resolve`.
     * @return null when nothing is due, so the caller writes nothing.
     */
    fun rearm(current: List<String>, sunsets: List<Sunset>, now: StampedInstant): Rearm? {
        val (due, running) = sunsets.partition { due(it, now) }
        if (due.isEmpty()) return null
        val back = due.map { it.pkg }.distinct()
        return Rearm(
            targets = current + back.filter { it !in current },
            remaining = running,
            rearmed = back,
        )
    }
}
