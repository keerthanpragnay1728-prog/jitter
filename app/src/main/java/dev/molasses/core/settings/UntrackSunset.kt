package dev.molasses.core.settings

import dev.molasses.core.time.ClockTamperClamp
import dev.molasses.core.time.StampedInstant

/**
 * Untracking a social app is not permanent: it comes back after seven days.
 * Pure.
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
 * ending it late is the bypass. It is measured with
 * [ClockTamperClamp.Direction.RELIEF], which credits the larger of the wall
 * and monotonic deltas, so winding the clock back does not extend it.
 *
 * A reboot ends it. Relief must not trust a wall clock across a boot (see
 * CLAUDE.md, "Which clock a deadline is measured on"), and seven days is
 * long enough that a reboot inside it is the ordinary case, not the attack.
 * Ending early is the direction that errs toward friction, so the app is
 * tracked again from the first connect after a restart. The cooling-off
 * panel says so before the user confirms.
 *
 * ## Stored as the deadline
 * [Sunset.deadline] is the instant the grace ends, stamped on all three
 * clocks at the moment of confirming, each moved on by [GRACE_MS]. Both
 * clocks move by the same amount, so the start is recovered exactly by
 * moving them back, which is what [remainingMs] hands the clamp.
 */
object UntrackSunset {

    const val GRACE_DAYS: Int = 7
    const val GRACE_MS: Long = GRACE_DAYS * 24L * 60 * 60 * 1000

    const val YOUTUBE = "com.google.android.youtube"

    /** Instagram, Facebook, X, TikTok (both of its package names), Snapchat. */
    val FALLBACK: Set<String> = setOf(
        "com.instagram.android",
        "com.facebook.katana",
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

    /** Grace left, never negative. Zero across a reboot. See the class doc. */
    fun remainingMs(sunset: Sunset, now: StampedInstant): Long {
        val d = sunset.deadline
        val verdict = ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = d.wallMs - GRACE_MS,
                lastSeenElapsedMs = d.elapsedMs - GRACE_MS,
                nowWallMs = now.wallMs,
                nowElapsedMs = now.elapsedMs,
                bootIdChanged = now.bootId != d.bootId,
            ),
            ClockTamperClamp.Direction.RELIEF,
        )
        if (verdict.bootChanged) return 0L
        return (GRACE_MS - verdict.creditedMs).coerceAtLeast(0L)
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
