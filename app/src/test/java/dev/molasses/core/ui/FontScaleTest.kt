package dev.molasses.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleTest {

    @Test
    fun `the five documented steps`() {
        assertEquals(
            listOf(0.80f, 0.90f, 1.00f, 1.15f, 1.30f),
            FontScale.entries.map { it.multiplier },
        )
    }

    @Test
    fun `medium is exactly one, so default changes nothing`() {
        // If the default were anything else, every user who never opens this
        // setting would silently have the system font scale overridden.
        assertEquals(1.00f, FontScale.MEDIUM.multiplier, 0.0f)
        assertEquals(FontScale.MEDIUM, FontScale.DEFAULT)
    }

    @Test
    fun `the ladder ascends`() {
        val values = FontScale.entries.map { it.multiplier }
        assertEquals(values.sorted(), values)
        assertEquals(values.distinct(), values)
    }

    @Test
    fun `ordinals round trip`() {
        for (scale in FontScale.entries) {
            assertEquals(scale, FontScale.fromOrdinal(scale.ordinal))
        }
    }

    @Test
    fun `an out of range ordinal falls back rather than throwing`() {
        // A corrupt preference should make the text ordinary, not stop the
        // launcher from starting.
        assertEquals(FontScale.DEFAULT, FontScale.fromOrdinal(-1))
        assertEquals(FontScale.DEFAULT, FontScale.fromOrdinal(99))
        assertEquals(FontScale.DEFAULT, FontScale.fromOrdinal(Int.MIN_VALUE))
    }

    @Test
    fun `bar cells shrink at the larger steps and never grow`() {
        val cells = FontScale.entries.map { FontScale.barCells(it) }
        assertEquals(cells.sortedDescending(), cells)
        assertEquals(20, FontScale.barCells(FontScale.MEDIUM))
        assertTrue(FontScale.barCells(FontScale.VERY_LARGE) < 20)
    }

    @Test
    fun `every bar cell count is positive and divides a hundred percent sanely`() {
        for (scale in FontScale.entries) {
            val cells = FontScale.barCells(scale)
            assertTrue("$scale gave $cells", cells in 1..20)
        }
    }

    @Test
    fun `no step is large enough to double the text`() {
        // The grid is monospace and the telemetry row must not wrap. Anything
        // past about 1.3 stops fitting on a narrow phone even before the
        // system scale compounds on top.
        for (scale in FontScale.entries) {
            assertTrue("$scale", scale.multiplier in 0.5f..1.5f)
        }
    }
}
