package dev.molasses.core.session

/**
 * The apps tracked until the user chooses a list, and the one migration that
 * brings an untouched install up to date when that set changes. Pure.
 *
 * `DEFAULT_TARGETS` in the data layer is [CURRENT]. The accessibility
 * profile's declared `packageNames` is our own package plus [CURRENT], and
 * `DefaultTargetsTest` holds the three together.
 *
 * ## Why changing the list is not enough on its own
 * The store's default value writes the defaults into the stored list, so an
 * install that never touched its targets has them on disk, with
 * `targets_chosen` false. `TargetScope.resolve` returns a non-empty stored
 * list as it is, so a new default would reach fresh installs only. [migrate]
 * rewrites the stored list for an install that is still exactly on the old
 * defaults and has never chosen, and for nobody else.
 */
object DefaultTargets {

    /** The defaults before Snapchat was added. */
    val BEFORE_SNAPCHAT: List<String> = listOf(
        "com.instagram.android",
        "com.twitter.android",
        "com.google.android.youtube",
    )

    val CURRENT: List<String> = BEFORE_SNAPCHAT + "com.snapchat.android"

    /**
     * The list an untouched install should now store, or null to leave it.
     *
     * Only a list the user never chose and that is exactly the old defaults
     * (in any order) moves. A chosen list is the user's. An unchosen list
     * that differs predates the `targets_chosen` flag, when a custom list
     * was stored with it false, and is the user's too. An empty one already
     * resolves to [CURRENT].
     */
    fun migrate(selection: TargetScope.Selection): List<String>? {
        if (selection.chosen) return null
        val cleaned = selection.stored.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        if (cleaned.size != BEFORE_SNAPCHAT.size || cleaned.toSet() != BEFORE_SNAPCHAT.toSet()) return null
        return CURRENT
    }
}
