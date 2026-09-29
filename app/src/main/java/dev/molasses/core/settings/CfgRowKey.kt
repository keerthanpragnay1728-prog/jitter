package dev.molasses.core.settings

/**
 * The identity of one row in CFG's list.
 *
 * ## Why a key scheme is worth a file
 * CFG is moving to a single `items()` call over one flat list, so that a row's
 * absolute index is simply its position in that list. The alphabet rail needs
 * that index: it finds an app's row and scrolls to it. Two things therefore
 * have to agree exactly, and they are written in different places by different
 * people at different times:
 *
 *  * the key the renderer hands Compose, and
 *  * the key the rail looks the row up by.
 *
 * A mismatch is silent. `indexOfFirst` returns -1, the rail declines to
 * scroll, and the only symptom is that one letter does nothing. So the strings
 * come from here and nowhere else, which is the same reason `Manual` is
 * generated from the registry rather than written twice.
 *
 * ## Why strings and not a sealed type
 * Compose's `items(key =)` wants something `Parcelable`-safe and stable across
 * recomposition. A string is both, and it survives being logged, which a
 * sealed type does not without ceremony. The namespacing below is what a
 * sealed type would have bought, at the cost of one convention rather than one
 * hierarchy.
 *
 * ## The namespace rule
 * Every key is `prefix:payload`, and the prefixes are disjoint. A package name
 * cannot collide with a section name because a package can never contain the
 * literal prefix this file puts in front of it, and `CfgRowKeyTest` sweeps the
 * real inputs to say so rather than leaving it to the reader.
 *
 * Pure; no Android imports. Unit-tested in `CfgRowKeyTest`.
 */
object CfgRowKey {

    private const val SEPARATOR = ":"

    /**
     * A row that belongs to no section.
     *
     * The masthead, and nothing else so far. It is above the accordion rather
     * than inside it, so there is no section to name it by, and giving it a
     * section's key would make the screen's own shape a lie in the one place
     * a reader would check it.
     */
    fun chrome(id: String): String = "chrome$SEPARATOR$id"

    /** A section header row. One per section, open or closed. */
    fun section(section: CfgAccordion.Section): String = "section$SEPARATOR${section.name}"

    /**
     * A fixed row inside a section, named by the section and a local id.
     *
     * The section is part of the key because a local id like "title" is the
     * obvious thing to write in more than one section, and two rows with one
     * key is a crash rather than a glitch.
     */
    fun body(section: CfgAccordion.Section, id: String): String =
        "body$SEPARATOR${section.name}$SEPARATOR$id"

    /** A target app row. The package is already unique, so it is the payload. */
    fun app(pkg: String): String = "app$SEPARATOR$pkg"

    /** The TRACKED and NOT TRACKED dividers in the target list. */
    fun group(name: String): String = "group$SEPARATOR$name"

    /** A horizon divider, keyed by its value so two of them cannot collide. */
    fun horizon(horizonMs: Long): String = "horizon$SEPARATOR$horizonMs"

    /**
     * The key for a grouped target row, whichever kind it is.
     *
     * The one entry point the renderer uses, so a new `TargetGrouping.Row`
     * case is a compile error here rather than a row that silently shares a
     * key with its neighbour. The rail asks for [app] directly, because it is
     * looking up a package rather than rendering a row.
     */
    fun of(row: TargetGrouping.Row): String = when (row) {
        is TargetGrouping.Row.App -> app(row.entry.pkg)
        TargetGrouping.Row.TrackedHeader -> group("tracked")
        TargetGrouping.Row.UntrackedHeader -> group("untracked")
        is TargetGrouping.Row.HorizonHeader -> horizon(row.horizonMs)
    }

    /**
     * Every prefix this file emits.
     *
     * Exposed so the test can assert they are disjoint rather than trusting
     * that five string literals written on five different lines happen not to
     * be prefixes of each other. "app" and "apps" would be a real bug and an
     * easy one to write.
     */
    val PREFIXES: List<String> =
        listOf("chrome", "section", "body", "app", "group", "horizon")
}
