package dev.molasses.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeParserTest {

    private fun ok(input: String): Int {
        val r = TimeParser.parse(input)
        return (r as? TimeParser.Result.Ok)?.minuteOfDay
            ?: throw AssertionError("expected Ok for '$input', got $r")
    }

    private fun err(input: String): TimeParser.Kind {
        val r = TimeParser.parse(input)
        return (r as? TimeParser.Result.Err)?.kind
            ?: throw AssertionError("expected Err for '$input', got $r")
    }

    @Test
    fun `twenty four hour forms`() {
        assertEquals(6 * 60, ok("6:00"))
        assertEquals(6 * 60, ok("06:00"))
        assertEquals(18 * 60 + 30, ok("18:30"))
        assertEquals(0, ok("0:00"))
        assertEquals(0, ok("00:00"))
        assertEquals(23 * 60 + 59, ok("23:59"))
    }

    @Test
    fun `twelve hour forms`() {
        assertEquals(6 * 60, ok("6am"))
        assertEquals(18 * 60, ok("6pm"))
        assertEquals(6 * 60 + 30, ok("6:30am"))
        assertEquals(18 * 60 + 30, ok("6:30pm"))
        assertEquals(11 * 60 + 59, ok("11:59am"))
    }

    @Test
    fun `midnight and noon, the two everyone gets wrong`() {
        assertEquals("12am is midnight", 0, ok("12am"))
        assertEquals("12pm is noon", 12 * 60, ok("12pm"))
        assertEquals(30, ok("12:30am"))
        assertEquals(12 * 60 + 30, ok("12:30pm"))
        // The hour after noon and the hour after midnight are not the same.
        assertEquals(13 * 60, ok("1pm"))
        assertEquals(60, ok("1am"))
    }

    @Test
    fun `case and spacing are tolerated`() {
        assertEquals(18 * 60, ok("6PM"))
        assertEquals(18 * 60, ok(" 6 pm "))
    }

    @Test
    fun `empty is rejected`() {
        assertEquals(TimeParser.Kind.EMPTY, err(""))
        assertEquals(TimeParser.Kind.EMPTY, err("   "))
    }

    @Test
    fun `out of range values are rejected`() {
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("25:00"))
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("24:00"))
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("6:75"))
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("13pm"))
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("0am"))
        assertEquals(TimeParser.Kind.OUT_OF_RANGE, err("6:60pm"))
    }

    @Test
    fun `garbage is rejected`() {
        for (bad in listOf("garbage", "6", "600", "6:0", "::", "6:00:00", "am", "6a", "-6:00")) {
            assertEquals(bad, TimeParser.Kind.MALFORMED, err(bad))
        }
    }

    @Test
    fun `msUntil walks forward only`() {
        val hour = 60 * 60 * 1000L
        assertEquals(2 * hour, TimeParser.msUntil(4 * 60, 6 * 60))
        // Across midnight.
        assertEquals(8 * hour, TimeParser.msUntil(22 * 60, 6 * 60))
    }

    @Test
    fun `msUntil on the target minute is a full day, never zero`() {
        // A zero length lock is indistinguishable from the command failing.
        assertEquals(24 * 60 * 60 * 1000L, TimeParser.msUntil(6 * 60, 6 * 60))
    }
}
