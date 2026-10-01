package dev.molasses.core.support

/**
 * [ REPORT A PROBLEM ] in CFG: what it opens, and when it is on screen. Pure.
 *
 * ## One address, one builder
 * The issue tracker's URL is [ISSUES_URL] and nowhere else in the app. The
 * row, the resolve check and the launch all take their Intent from [spec],
 * so the check that decides whether the row is shown asks about exactly the
 * Intent the tap will send. Two builders would let them drift, and a row
 * that resolved one Intent and launched another would be shown on a device
 * where it cannot work.
 *
 * ## Shown only when something can open it
 * The row is hidden when no browser resolves the Intent, rather than shown
 * dimmed. A control that can never work on this device is the guard with no
 * caller from CLAUDE.md, drawn on screen. When it resolves but the launch
 * still fails (a browser disabled between the check and the tap, a profile
 * restriction), the row stays and carries a one-line note.
 *
 * No permission is involved. Jitter opens the page in the user's browser and
 * never reads it, so INTERNET stays out of the manifest.
 */
object ReportProblem {

    const val ISSUES_URL = "https://github.com/keerthanpragnay1728-prog/jitter/issues"

    /** `Intent.ACTION_VIEW`, spelled out so this file stays free of Android. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** `Intent.CATEGORY_BROWSABLE`. */
    const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"

    /**
     * The glyph at the right edge of the row, where a section header has its
     * chevron: a north-east arrow, U+2197, which reads as "opens elsewhere".
     * Not ">", because a closed section already shows ">", and a row that
     * looked like a collapsed section would invite a tap to expand it. Its
     * own constant rather than `CfgAccordion.chevron`, because that one flips
     * to the open form and this row never expands.
     *
     * Written as an escape, as `CfgAccordion.CHEVRON_OPEN` is, so no editor or
     * paste can mangle it. If it renders badly on a device, "->" is the ASCII
     * fallback, and this line is the only one to change.
     */
    const val ROW_GLYPH = "\u2197"

    data class Spec(val action: String, val category: String, val url: String)

    /** The one Intent this row ever sends. */
    fun spec(): Spec = Spec(action = ACTION_VIEW, category = CATEGORY_BROWSABLE, url = ISSUES_URL)

    sealed interface Row {
        data object Hidden : Row

        /** @param failureNote the last tap did not open anything. */
        data class Shown(val failureNote: Boolean) : Row
    }

    fun row(resolvable: Boolean, launchFailed: Boolean): Row =
        if (resolvable) Row.Shown(failureNote = launchFailed) else Row.Hidden
}
