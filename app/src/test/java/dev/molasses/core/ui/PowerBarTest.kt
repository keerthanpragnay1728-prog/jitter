package dev.molasses.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerBarTest {

    private val ascii = PowerBar.Glyphs.ASCII

    @Test
    fun `the documented example renders exactly`() {
        assertEquals("[####......]", PowerBar.bar(49, ascii))
        assertEquals("31", PowerBar.hexCapacity(49))
    }

    @Test
    fun `the bar is always ten cells plus brackets`() {
        for (p in 0..100) {
            val b = PowerBar.bar(p, ascii)
            assertEquals("at $p%: $b", PowerBar.CELLS + 2, b.length)
            assertTrue(b.startsWith("[") && b.endsWith("]"))
        }
    }

    @Test
    fun `empty and full`() {
        assertEquals("[..........]", PowerBar.bar(0, ascii))
        assertEquals("[##########]", PowerBar.bar(100, ascii))
    }

    @Test
    fun `a non-zero charge always shows at least one cell`() {
        // A bar that reads empty on a phone that still works is a small lie
        // that makes the rest of the readout untrustworthy.
        for (p in 1..9) {
            assertEquals("at $p%", 1, PowerBar.filledCells(p))
        }
    }

    @Test
    fun `ten cells means genuinely full, not merely close`() {
        assertEquals(9, PowerBar.filledCells(99))
        assertEquals(10, PowerBar.filledCells(100))
    }

    @Test
    fun `filled cells never decrease as charge rises`() {
        var previous = 0
        for (p in 0..100) {
            val n = PowerBar.filledCells(p)
            assertTrue("fell at $p%", n >= previous)
            assertTrue("out of range at $p%: $n", n in 0..PowerBar.CELLS)
            previous = n
        }
    }

    @Test
    fun `out of range input is clamped rather than overflowing the bar`() {
        assertEquals("[..........]", PowerBar.bar(-50, ascii))
        assertEquals("[##########]", PowerBar.bar(500, ascii))
        assertEquals("00", PowerBar.hexCapacity(-1))
        assertEquals("64", PowerBar.hexCapacity(101))
    }

    @Test
    fun `hex capacity is always two characters so the header never reflows`() {
        for (p in 0..100) {
            assertEquals("at $p%", 2, PowerBar.hexCapacity(p).length)
        }
        assertEquals("00", PowerBar.hexCapacity(0))
        assertEquals("0A", PowerBar.hexCapacity(10))
        assertEquals("5C", PowerBar.hexCapacity(92))
        assertEquals("64", PowerBar.hexCapacity(100))
    }

    @Test
    fun `the ascii fallback is pure ascii and the block set is not`() {
        assertTrue(PowerBar.bar(50, ascii).all { it.code in 32..126 })
        assertFalse(PowerBar.bar(50, PowerBar.Glyphs.BLOCK).all { it.code in 32..126 })
    }

    @Test
    fun `both glyph sets produce the same shape`() {
        for (p in listOf(0, 1, 49, 50, 99, 100)) {
            assertEquals(
                PowerBar.bar(p, ascii).length,
                PowerBar.bar(p, PowerBar.Glyphs.BLOCK).length,
            )
        }
    }

    @Test
    fun `low is below fifteen percent`() {
        assertTrue(PowerBar.isLow(0))
        assertTrue(PowerBar.isLow(14))
        assertFalse(PowerBar.isLow(15))
        assertFalse(PowerBar.isLow(100))
    }

    // ------------------------------------------------------------ charging

    @Test
    fun `no charging cell when not charging`() {
        // The animation contract: null means stop asking, not draw nothing.
        for (phase in listOf(0f, 0.5f, 0.99f)) {
            assertNull(PowerBar.chargingCellIndex(50, charging = false, phase = phase))
        }
    }

    @Test
    fun `no charging cell when the bar is empty`() {
        assertNull(PowerBar.chargingCellIndex(0, charging = true, phase = 0.5f))
    }

    @Test
    fun `the charging cell stays inside the filled region`() {
        for (p in listOf(1, 10, 49, 100)) {
            val filled = PowerBar.filledCells(p)
            var phase = 0f
            while (phase < 1f) {
                val i = PowerBar.chargingCellIndex(p, charging = true, phase = phase)!!
                assertTrue("p=$p phase=$phase gave $i", i in 0 until filled)
                phase += 0.01f
            }
        }
    }

    @Test
    fun `the charging cell travels across the filled region`() {
        val seen = (0..99)
            .map { PowerBar.chargingCellIndex(100, charging = true, phase = it / 100f)!! }
            .distinct()
        assertEquals(PowerBar.CELLS, seen.size)
        assertEquals(0, seen.first())
        assertEquals(PowerBar.CELLS - 1, seen.last())
    }

    @Test
    fun `phase wraps rather than running off the end`() {
        assertEquals(
            PowerBar.chargingCellIndex(100, true, 0.25f),
            PowerBar.chargingCellIndex(100, true, 1.25f),
        )
        assertEquals(
            PowerBar.chargingCellIndex(100, true, 0.25f),
            PowerBar.chargingCellIndex(100, true, 7.25f),
        )
    }

    @Test
    fun `the cycle is nine hundred milliseconds as specified`() {
        assertEquals(900L, PowerBar.CHARGE_CYCLE_MS)
    }
}
