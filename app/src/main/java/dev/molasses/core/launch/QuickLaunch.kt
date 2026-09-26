package dev.molasses.core.launch

/**
 * The console's quick-launch rows, chosen by the user. Pure.
 *
 * ## How it is stored
 * `quick_launch_packages` is a list of entry tokens, and `quick_launch_chosen`
 * says whether the user has ever edited it. Each token is one of two things:
 *
 * - a package name, for a row that launches that app;
 * - a built-in row, spelled `@` plus its name: `@phone`, `@messages`,
 *   `@calendar`, `@calculator`, `@clock`.
 *
 * The `@` is the tiebreak. Android package names are letters, digits,
 * underscores and dots, so no package can ever be mistaken for a built-in or
 * the reverse. The built-ins are rows that are not one package: `[messages]`
 * opens its multi-client selector, and the other four walk a
 * [ShortcutLadder], because "the calendar" is a different package on every
 * device.
 *
 * ## Chosen versus untouched
 * The same shape as `TargetScope`, for the same reason. False and empty is a
 * device where the user never opened the picker, and it shows [DEFAULTS]: the
 * five rows the console had before this was editable. True and empty is a
 * user who removed every row, and it is honoured. A non-empty list is the
 * list either way. The flag is set on every write, including the one that
 * empties it.
 *
 * ## A dead row is never drawn
 * An uninstalled app is skipped when the rows are resolved for display
 * ([visible]) and dropped from the stored list the next time it is written
 * ([pruned]). Skipped first because a render cannot write, and pruned second
 * so the list does not carry a ghost forever.
 */
object QuickLaunch {

    /** How many rows the console shows at most. Five fit above the fold. */
    const val MAX_SLOTS = 5

    enum class BuiltIn(val token: String) {
        PHONE("@phone"),
        MESSAGES("@messages"),
        CALENDAR("@calendar"),
        CALCULATOR("@calculator"),
        CLOCK("@clock"),
    }

    sealed interface Entry {
        val token: String

        data class App(val pkg: String) : Entry {
            override val token: String get() = pkg
        }

        data class Row(val builtIn: BuiltIn) : Entry {
            override val token: String get() = builtIn.token
        }
    }

    /** The five rows an untouched install shows, in the order it always had. */
    val DEFAULTS: List<Entry> = BuiltIn.entries.map { Entry.Row(it) }

    data class Selection(val stored: List<String>, val chosen: Boolean)

    /** A stored token as an entry, or null for one this build does not know. */
    fun decode(token: String): Entry? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("@")) return BuiltIn.entries.firstOrNull { it.token == t }?.let { Entry.Row(it) }
        return Entry.App(t)
    }

    /** What the rows are, before anything about the device is known. */
    fun resolve(selection: Selection): List<Entry> {
        val decoded = selection.stored.mapNotNull(::decode).distinct().take(MAX_SLOTS)
        return when {
            decoded.isNotEmpty() -> decoded
            selection.chosen -> emptyList()
            else -> DEFAULTS
        }
    }

    /** The rows to draw: [resolve] less any app that is not launchable now. */
    fun visible(selection: Selection, isLaunchable: (String) -> Boolean): List<Entry> =
        resolve(selection).filter { it !is Entry.App || isLaunchable(it.pkg) }

    /** The list to store: the same filter, applied at a write. */
    fun pruned(entries: List<Entry>, isLaunchable: (String) -> Boolean): List<String> =
        entries.filter { it !is Entry.App || isLaunchable(it.pkg) }.map { it.token }

    /** [entries] with [entry] added at the end, or null when full or already there. */
    fun added(entries: List<Entry>, entry: Entry): List<Entry>? = when {
        entry in entries -> null
        entries.size >= MAX_SLOTS -> null
        else -> entries + entry
    }

    fun removed(entries: List<Entry>, entry: Entry): List<Entry> = entries - entry

    /**
     * [entries] with [old] replaced by [new] in the same slot, or null.
     *
     * The route to the picker when the rows are full: [added] refuses at
     * five, so a full section offers a swap per row instead of hiding the
     * picker. The order is kept because the slot is kept, and the count
     * cannot grow because nothing is appended. Refused when [old] is not a
     * row, and when [new] is already pinned, including [new] equal to
     * [old]: a swap that would duplicate a row is not a swap.
     */
    fun swapped(entries: List<Entry>, old: Entry, new: Entry): List<Entry>? {
        val slot = entries.indexOf(old)
        if (slot < 0 || new in entries) return null
        return entries.toMutableList().also { it[slot] = new }
    }
}
