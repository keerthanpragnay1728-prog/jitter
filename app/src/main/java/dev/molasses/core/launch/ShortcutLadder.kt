package dev.molasses.core.launch

/**
 * What the console's favourite rows try, in order, before giving up.
 *
 * ## Why a ladder and not one Intent
 * `[clock]` used to fire `ACTION_SHOW_ALARMS` and nothing else. That is the
 * right first try and it is not a complete answer: several OEM clock apps
 * never declare it, so the row did nothing at all on those devices, silently,
 * because `startActivity` was wrapped in a `runCatching` that discarded the
 * failure. A row that does nothing is worse than a row that says it cannot,
 * because the user repeats it.
 *
 * ## Why the list is here and not at the call site
 * Two reasons, and the second is the one that bites.
 *
 * First, it is reviewable. A list of OEM package names is the kind of thing
 * that rots, and it should be somewhere a person can read it as a list rather
 * than as a chain of elvis operators.
 *
 * Second, **every action probed here has to appear in the manifest's
 * `queries` block**. On API 30+ `resolveActivity` returns null for an action
 * that is not declared there, on a device that handles it perfectly well, so
 * a ladder and a manifest that disagree produce a fallback chain that silently
 * collapses to nothing. That is not hypothetical: it is exactly how the
 * Digital Wellbeing link failed, on every device, for as long as it existed.
 * [PROBED_ACTIONS] exists so a test can hold the two in step rather than a
 * reader having to.
 *
 * ## Why package candidates need no action entry
 * A package rung is launched through `getLaunchIntentForPackage`, which needs
 * the package to be visible rather than the action to be declared. Every app
 * here has a launcher icon, so the manifest's existing `MAIN` + `LAUNCHER`
 * query already makes it visible. Only [Candidate.action] rungs need a
 * `queries` entry, which is why [PROBED_ACTIONS] draws from those alone.
 *
 * Pure; no Android imports. The strings are Android's own constants, repeated
 * rather than imported because this module cannot see them; `ShortcutLadderTest`
 * is not able to check them against the SDK and says so.
 *
 * Unit-tested in `ShortcutLadderTest`.
 */
object ShortcutLadder {

    /**
     * One rung.
     *
     * Exactly one of [action] or [pkg] is set. An action rung resolves an
     * `Intent`; a package rung asks for that app's launcher intent. They are
     * one type because they are one ordered list, and the order is the whole
     * point: the specific intent first, the generic category second, a named
     * app last.
     */
    data class Candidate(
        val action: String? = null,
        val category: String? = null,
        val pkg: String? = null,
    ) {
        init {
            require((action != null) != (pkg != null)) {
                "a rung is an action or a package, never both and never neither"
            }
        }
    }

    private const val MAIN = "android.intent.action.MAIN"

    /**
     * The clock.
     *
     * `SHOW_ALARMS` first because it lands on the alarm list rather than on
     * whatever screen the clock app opens to, which is what `[clock]` means.
     * `APP_CLOCK` second, which most clocks declare and which opens the app.
     * Then the four packages that ship on the phones people actually have.
     */
    val CLOCK: List<Candidate> = listOf(
        Candidate(action = "android.intent.action.SHOW_ALARMS"),
        Candidate(action = MAIN, category = "android.intent.category.APP_CLOCK"),
        Candidate(pkg = "com.google.android.deskclock"),
        Candidate(pkg = "com.android.deskclock"),
        Candidate(pkg = "com.sec.android.app.clockpackage"),
        Candidate(pkg = "com.coloros.alarmclock"),
        Candidate(pkg = "com.oneplus.deskclock"),
    )

    /**
     * The calculator.
     *
     * No specific action exists, so the category is the first and best rung.
     * The packages below it are for the handful of OEM calculators that ship
     * without declaring `APP_CALCULATOR` at all.
     */
    val CALCULATOR: List<Candidate> = listOf(
        Candidate(action = MAIN, category = "android.intent.category.APP_CALCULATOR"),
        Candidate(pkg = "com.google.android.calculator"),
        Candidate(pkg = "com.android.calculator2"),
        Candidate(pkg = "com.sec.android.app.popupcalculator"),
        Candidate(pkg = "com.miui.calculator"),
        Candidate(pkg = "com.coloros.calculator"),
    )

    /**
     * The calendar, moved here with the others so all three read the same way.
     */
    val CALENDAR: List<Candidate> = listOf(
        Candidate(action = MAIN, category = "android.intent.category.APP_CALENDAR"),
        Candidate(pkg = "com.google.android.calendar"),
        Candidate(pkg = "com.samsung.android.calendar"),
    )

    /**
     * Chat apps the messaging selector offers alongside the device's SMS apps.
     *
     * A separate list because they are a different question. An SMS client
     * declares `CATEGORY_APP_MESSAGING` and can be queried for; WhatsApp,
     * Signal and Telegram do not declare it and never will, because they are
     * not SMS clients. Asking the system for "messaging apps" therefore
     * returns exactly the ones most people do not use.
     *
     * Named rather than queried, and the cost of that is honest: an app not on
     * this list does not appear in the selector, and the app drawer is the
     * answer for it. The alternative is showing every installed app under
     * `[messages]`, which is the drawer with a different label.
     */
    val CHAT_PACKAGES: List<String> = listOf(
        "com.whatsapp",
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.signal.messenger",
    )

    /** The SMS clients on this device, asked for by category. */
    const val MESSAGING_CATEGORY = "android.intent.category.APP_MESSAGING"

    /** Every ladder, so a test can sweep them without naming each one. */
    val ALL: List<List<Candidate>> = listOf(CLOCK, CALCULATOR, CALENDAR)

    /**
     * Every action any ladder probes.
     *
     * The manifest's `queries` block must declare all of these. See the class
     * doc for what happens when it does not, and `ShortcutLadderTest` for the
     * assertion that keeps them in step.
     */
    val PROBED_ACTIONS: Set<String> =
        ALL.flatten().mapNotNull { it.action }.toSet()
}
