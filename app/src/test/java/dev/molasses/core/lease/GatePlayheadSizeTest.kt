package dev.molasses.core.lease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePlayheadSizeTest {

    private fun size(width: Float, columns: Int, scale: Float = 1f) = GatePlayhead.fontSizeSp(width, columns, scale)

    @Test
    fun `360 dp, 31 columns, is the largest that fits inside the gutters`() {
        // 328 dp across, 31 columns at 0.62 em each: 17.06 sp, floored to 17.0.
        assertEquals(17.0f, size(360f, 31), 0.0001f)
    }

    @Test
    fun `412 dp, 31 columns`() {
        // 380 dp across: 19.77 sp, floored to 19.7.
        assertEquals(19.7f, size(412f, 31), 0.0001f)
    }

    @Test
    fun `9 columns hit the cap on both widths`() {
        assertEquals(GatePlayhead.MAX_SP, size(360f, 9), 0f)
        assertEquals(GatePlayhead.MAX_SP, size(412f, 9), 0f)
    }

    @Test
    fun `it never exceeds the cap`() {
        for (width in listOf(320f, 360f, 412f, 600f, 1200f, 4000f)) {
            for (columns in 1..31) {
                assertTrue("$width dp, $columns columns", size(width, columns) <= GatePlayhead.MAX_SP)
            }
        }
        assertEquals(22f, GatePlayhead.MAX_SP, 0f)
    }

    @Test
    fun `the track always fits on one line inside the gutters`() {
        for (width in (280..1200 step 4).map { it.toFloat() }) {
            for (columns in listOf(9, 13, 17, 21, 25, 29, 31)) {
                for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
                    val sp = size(width, columns, scale)
                    val drawn = GatePlayhead.widthDp(sp, columns, scale)
                    assertTrue(
                        "$width dp, $columns columns, scale $scale: $sp sp draws $drawn dp",
                        drawn <= width - 2 * GatePlayhead.GUTTER_DP,
                    )
                }
            }
        }
    }

    @Test
    fun `31 columns stay one line on a 360 dp screen at every system font size`() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
            val sp = size(360f, 31, scale)
            assertTrue(sp > 0f)
            assertTrue(GatePlayhead.widthDp(sp, 31, scale) <= 328f)
        }
    }

    @Test
    fun `a larger system font size gets a smaller sp, and the same width`() {
        assertTrue(size(360f, 31, 1.3f) < size(360f, 31, 1f))
    }

    @Test
    fun `nothing to draw draws at nothing`() {
        assertEquals(0f, size(360f, 0), 0f)
        assertEquals(0f, size(20f, 31), 0f)
    }
}
