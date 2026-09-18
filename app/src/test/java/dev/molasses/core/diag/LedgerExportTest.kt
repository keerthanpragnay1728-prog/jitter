package dev.molasses.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the file says about itself, and what it does with awkward values.
 *
 * The header is the part worth testing hardest. A diagnostic file that
 * implies it is complete when it is a window is worse than no file, because
 * the conclusion drawn from it is wrong in a direction nobody checks.
 */
class LedgerExportTest {

    private fun row(wallMs: Long, type: String = "RESUMED", meta: String? = null) =
        LedgerExport.Row(wallMs, bootId = 41, type = type, pkg = "com.instagram.android", meta = meta)

    private val stamp: (Long) -> String = { "T$it" }

    @Test
    fun `the header says how many rows and how many exist`() {
        val out = LedgerExport.format(listOf(row(1), row(2)), totalInDatabase = 900, formatWall = stamp)
        assertTrue(out, out.contains("2 rows, the most recent of 900 in the database"))
        assertTrue("it must say the rest exist", out.contains("Older rows exist and are not in this file"))
    }

    @Test
    fun `a complete export says so instead of implying a window`() {
        // The other direction, and the reason the header is not one fixed
        // sentence. Telling a user rows are missing when none are is the same
        // class of lie as the reverse.
        val out = LedgerExport.format(listOf(row(1), row(2)), totalInDatabase = 2, formatWall = stamp)
        assertTrue(out, out.contains("which is everything in the database"))
        assertTrue(!out.contains("Older rows exist"))
    }

    @Test
    fun `an empty ledger still produces a file that explains itself`() {
        val out = LedgerExport.format(emptyList(), totalInDatabase = 0, formatWall = stamp)
        assertTrue(out.startsWith("# Jitter ledger export"))
        assertTrue(out.contains("0 rows"))
        // Header plus column names, and nothing pretending to be data.
        assertEquals(4, out.trim().lines().size)
    }

    @Test
    fun `every row has the same column count as the header`() {
        // The failure a separator without escaping produces: one row gains a
        // column and every value after it moves, silently.
        val rows = listOf(
            row(1, meta = "countdown=8000ms expired=false"),
            row(2, type = "LEASE_TAKEN", meta = "duration=300000ms untilAccum=315567 n=3"),
            row(3, meta = null),
        )
        val out = LedgerExport.format(rows, totalInDatabase = 3, formatWall = stamp)
        val body = out.trim().lines().filterNot { it.startsWith("#") }
        val columns = body.first().split(LedgerExport.SEPARATOR).size
        for (line in body) {
            assertEquals(line, columns, line.split(LedgerExport.SEPARATOR).size)
        }
    }

    @Test
    fun `a tab or a newline in a value cannot add a column or a row`() {
        val rows = listOf(
            LedgerExport.Row(1, 0, "A\tB", "pkg\nname", "x\ty\r\nz"),
        )
        val out = LedgerExport.format(rows, totalInDatabase = 1, formatWall = stamp)
        val body = out.trim().lines().filterNot { it.startsWith("#") }
        assertEquals("header and one row", 2, body.size)
        assertEquals(
            body[1],
            body[0].split(LedgerExport.SEPARATOR).size,
            body[1].split(LedgerExport.SEPARATOR).size,
        )
    }

    @Test
    fun `a row with no detail does not end in a separator`() {
        // Found by the column-count test above, and it is a defect in the
        // file rather than in the test. meta is null on RESUMED and PAUSED,
        // the two commonest rows there are, so an empty last column would
        // leave those lines ending in a tab. Anything that trims trailing
        // whitespace then destroys the column count of exactly those rows and
        // of no others, which is the kind of corruption that looks like data.
        val out = LedgerExport.format(listOf(row(1, meta = null)), 1, stamp)
        val line = out.trim().lines().last()
        assertTrue(line, line.endsWith(LedgerExport.ABSENT))
        assertTrue("must not end in a separator", !line.endsWith(LedgerExport.SEPARATOR))
    }

    @Test
    fun `the raw millisecond value is kept beside the formatted one`() {
        // The formatted time is for a human and is not comparable across
        // devices or locales. The raw value is what a second reading is
        // actually done against, and dropping it would make the file
        // readable and useless.
        val out = LedgerExport.format(listOf(row(1_700_000_000_000L)), 1, stamp)
        assertTrue(out, out.contains("1700000000000"))
    }

    @Test
    fun `the file name carries a stamp and a usable extension`() {
        assertEquals("jitter-ledger-2026-09-18-1646.tsv", LedgerExport.fileName("2026-09-18-1646"))
    }

    @Test
    fun `the export window is wider than the screen's`() {
        // The debug list is sized for a thumb; a file is read by something
        // else. Bounded either way, because an unbounded export on a device
        // with months of history is a file nobody opens twice.
        assertTrue(LedgerExport.LIMIT > 300)
    }
}
