package dev.molasses.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationParserTest {

    private val s = 1_000L
    private val m = 60 * s
    private val h = 60 * m
    private val d = 24 * h

    private fun ok(input: String): Long {
        val r = DurationParser.parse(input)
        return (r as? DurationParser.Result.Ok)?.ms
            ?: throw AssertionError("expected Ok for '$input', got $r")
    }

    private fun err(input: String): DurationParser.Kind {
        val r = DurationParser.parse(input)
        return (r as? DurationParser.Result.Err)?.kind
            ?: throw AssertionError("expected Err for '$input', got $r")
    }

    @Test
    fun `single units`() {
        assertEquals(30 * s, ok("30s"))
        assertEquals(45 * m, ok("45m"))
        assertEquals(2 * h, ok("2h"))
        assertEquals(3 * d, ok("3d"))
    }

    @Test
    fun `compound units sum`() {
        assertEquals(1 * h + 30 * m, ok("1h30m"))
        assertEquals(1 * d + 2 * h + 3 * m + 4 * s, ok("1d2h3m4s"))
    }

    @Test
    fun `whitespace and case are tolerated`() {
        assertEquals(2 * h, ok("  2H  "))
        assertEquals(1 * h + 30 * m, ok("1H30M"))
    }

    @Test
    fun `empty is rejected`() {
        assertEquals(DurationParser.Kind.EMPTY, err(""))
        assertEquals(DurationParser.Kind.EMPTY, err("   "))
    }

    @Test
    fun `zero is rejected rather than treated as an unlock`() {
        assertEquals(DurationParser.Kind.ZERO, err("0m"))
        assertEquals(DurationParser.Kind.ZERO, err("0h0m0s"))
    }

    @Test
    fun `negatives never parse`() {
        // A sign is not in the grammar at all, so this is MALFORMED and no
        // downstream code has to defend against a negative duration.
        assertEquals(DurationParser.Kind.MALFORMED, err("-5m"))
        assertEquals(DurationParser.Kind.MALFORMED, err("5m-2h"))
    }

    @Test
    fun `malformed forms are rejected`() {
        for (bad in listOf("m", "5", "5x", "abc", "5 m", "5m 2h", "1.5h", "h5", "5mm", "5m5")) {
            assertEquals(bad, DurationParser.Kind.MALFORMED, err(bad))
        }
    }

    @Test
    fun `the thirty day cap is enforced`() {
        assertEquals(30 * d, ok("30d"))
        assertEquals(DurationParser.Kind.TOO_LONG, err("31d"))
        assertEquals(DurationParser.Kind.TOO_LONG, err("30d1s"))
        assertEquals(DurationParser.Kind.TOO_LONG, err("720h1m"))
    }

    @Test
    fun `an absurd value is capped rather than overflowing to a negative`() {
        // The failure this guards is specific: Long overflow produces a
        // negative duration, which reads as "already expired" and is the one
        // parse failure that would silently grant relief.
        for (bad in listOf("99999999999999d", "9223372036854775807s", "99999999999h")) {
            assertEquals(bad, DurationParser.Kind.TOO_LONG, err(bad))
        }
    }

    @Test
    fun `the cap matches the lock ladder`() {
        assertEquals(dev.molasses.core.lock.LockLadder.MAX_MS, DurationParser.MAX_MS)
    }
}
