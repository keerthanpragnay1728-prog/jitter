package dev.molasses.core.diag

/**
 * The ledger as a file, for reading somewhere that is not a phone.
 *
 * ## What the file has to say about itself
 * An export is a snapshot of a window, and a file that does not say so
 * misrepresents itself to whoever opens it next, which may be the same person
 * a month later with no memory of how it was made. Two facts are load bearing
 * and both go in the header:
 *
 *  * **how many rows are in it**, and
 *  * **how many rows the database actually holds**.
 *
 * Those differ. The debug view reads `dao.recent(300)` and this reads a wider
 * window, but the table is not capped at either: `prune` deletes by age and
 * `count` reports the true total. So "the last 300 rows" would have been
 * wrong in both directions, and the honest line is N of M, most recent first,
 * with the rest named as absent rather than left to be assumed.
 *
 * ## Why this is not a CSV
 * The `meta` column carries `key=value` pairs with commas in them
 * (`duration=300000ms untilAccum=315567 n=3`), so a comma separator would
 * need quoting rules and a reader that honours them. Tabs do not occur in any
 * value this app writes, and [scrub] makes that true rather than assumed.
 *
 * ## No network, ever
 * This produces a string. Where it goes is the caller's problem, and the
 * caller hands it to the system document picker, so the user chooses the
 * destination and the app needs no storage permission and no network.
 *
 * Pure; no Android imports and no `java.*` either, which is why the wall
 * clock arrives as a formatting lambda rather than a `SimpleDateFormat`. The
 * pure set has no JVM imports at all and this is not the file to start with.
 *
 * Unit-tested in `LedgerExportTest`.
 */
object LedgerExport {

    /**
     * How many rows an export reads.
     *
     * Wider than the debug view's 300, because that number is sized for a
     * list a thumb scrolls and this one is sized for a file something else
     * reads. Still bounded: an unbounded export on a device with months of
     * history is a file nobody opens twice.
     */
    const val LIMIT = 5_000

    /** Tab. See the class doc for why not a comma. */
    const val SEPARATOR = "\t"

    /**
     * What an absent value is written as, rather than nothing at all.
     *
     * `meta` is null on the commonest rows there are, `RESUMED` and `PAUSED`,
     * and an empty last column means a line that ends in a tab. Most readers
     * cope; a text editor that strips trailing whitespace, or anything that
     * trims the file, does not, and what it silently destroys is the column
     * count of precisely those rows. A placeholder costs two characters and
     * cannot be trimmed away.
     *
     * ASCII, per CLAUDE.md's note on table placeholders.
     */
    const val ABSENT = "--"

    /** One ledger row, with the enum already resolved to its name. */
    data class Row(
        val wallMs: Long,
        val bootId: Int,
        val type: String,
        val pkg: String,
        val meta: String?,
    )

    /**
     * Anything that would break a line or a column, replaced by a space.
     *
     * The values this app writes contain neither, so this changes nothing
     * today. It exists because the alternative is a file that silently gains
     * a column the first time a package name or a meta string arrives with a
     * tab in it, and a malformed row in a diagnostic file is worse than an
     * approximate one: it moves every value after it.
     */
    fun scrub(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * @param rows most recent first, as the query returns them.
     * @param totalInDatabase from `dao.count()`. When it exceeds [rows] size,
     *   the header says so rather than letting the file imply it is complete.
     * @param formatWall renders a wall-clock millisecond value. Supplied by
     *   the caller because this module holds no date formatter; see the class
     *   doc.
     */
    fun format(
        rows: List<Row>,
        totalInDatabase: Int,
        formatWall: (Long) -> String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "# Jitter ledger export"
        lines += if (totalInDatabase > rows.size) {
            "# ${rows.size} rows, the most recent of $totalInDatabase in the " +
                "database. Older rows exist and are not in this file."
        } else {
            "# ${rows.size} rows, which is everything in the database."
        }
        lines += "# Newest first. Times are wall clock and can move; boot is " +
            "the boot counter they were stamped under."
        lines += listOf("time", "wall_ms", "boot", "event", "package", "detail")
            .joinToString(SEPARATOR)
        for (row in rows) {
            lines += listOf(
                scrub(formatWall(row.wallMs)),
                row.wallMs.toString(),
                row.bootId.toString(),
                scrub(row.type),
                scrub(row.pkg),
                row.meta?.let { scrub(it) }?.ifEmpty { ABSENT } ?: ABSENT,
            ).joinToString(SEPARATOR)
        }
        return lines.joinToString("\n") + "\n"
    }

    /**
     * The name the document picker opens with.
     *
     * The stamp is in the name because a user exporting twice in a session
     * should not be asked to overwrite, and because a file found later with
     * no date in its name is a file of unknown vintage, which for a
     * diagnostic is the same as no file.
     */
    fun fileName(stamp: String): String = "jitter-ledger-${scrub(stamp)}.tsv"
}
