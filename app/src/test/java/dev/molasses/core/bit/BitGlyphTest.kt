package dev.molasses.core.bit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed slot.
 *
 * Bit's snap target is computed from its measured width. A glyph that is two
 * characters narrower than the last one moves that target, which moves Bit
 * while nobody touched it. These tests are what make the slot a guarantee
 * rather than a convention.
 */
class BitGlyphTest {

    @Test
    fun `every string constant is a declared face or a named exception`() {
        // The derivation of WIDTH is only honest if FACES is complete. This
        // is the check that fails when someone adds a face and forgets.
        val declared = (BitStateMachine.FACES + BitStateMachine.NON_FACES).toSet()
        val constants = BitStateMachine::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map {
                it.isAccessible = true
                it.get(BitStateMachine) as String
            }
        assertTrue("no string constants found; the reflection has rotted", constants.size >= 10)
        for (c in constants) {
            assertTrue("'$c' is neither in FACES nor NON_FACES", c in declared)
        }
    }

    @Test
    fun `the slot is as wide as the widest face`() {
        assertEquals(BitStateMachine.FACES.maxOf { it.length }, BitGlyph.WIDTH)
    }

    @Test
    fun `every face pads to exactly the slot width`() {
        for (face in BitStateMachine.FACES) {
            assertEquals(face, BitGlyph.WIDTH, BitGlyph.pad(face).length)
        }
    }

    @Test
    fun `every slit pads to exactly the slot width`() {
        for (slit in BitGlyph.SLITS) {
            assertEquals(slit, BitGlyph.WIDTH, BitGlyph.pad(slit).length)
        }
    }

    @Test
    fun `padding centres, so a narrow glyph does not sit against the bezel`() {
        assertEquals("  (o_o) ", " " + BitGlyph.pad("(o_o)"))
        assertEquals(BitGlyph.WIDTH, BitGlyph.pad("(o_o)").length)
        assertTrue(BitGlyph.pad("(o_o)").trim() == "(o_o)")
    }

    @Test
    fun `an oversized string is truncated rather than widening the slot`() {
        // Nothing should ever reach here, and BitHudTest proves the readouts
        // cannot. The clamp is unconditional anyway, because a glyph that
        // silently widened the slot is the exact bug this file prevents.
        assertEquals(BitGlyph.WIDTH, BitGlyph.pad("aaaaaaaaaaaaaaaaaaa").length)
    }

    @Test
    fun `an empty string still occupies the slot`() {
        assertEquals(BitGlyph.WIDTH, BitGlyph.pad("").length)
    }

    // -------------------------------------------------------------- slit

    @Test
    fun `the slit is normal when nothing is wrong`() {
        assertEquals(BitGlyph.SLIT_NORMAL, BitGlyph.slitFor(curfew = false, penaltyAccruing = false))
    }

    @Test
    fun `an overdue checkpoint raises the alert glyph`() {
        assertEquals(BitGlyph.SLIT_ALERT, BitGlyph.slitFor(curfew = false, penaltyAccruing = true))
    }

    @Test
    fun `a curfew shows the sleep glyph`() {
        assertEquals(BitGlyph.SLIT_CURFEW, BitGlyph.slitFor(curfew = true, penaltyAccruing = false))
    }

    @Test
    fun `a curfew outranks an overdue checkpoint`() {
        // During a curfew the checkpoint is not the thing the user can act on.
        assertEquals(BitGlyph.SLIT_CURFEW, BitGlyph.slitFor(curfew = true, penaltyAccruing = true))
    }

    @Test
    fun `every slit is one character inside its brackets`() {
        // The whole point: a vi mode indicator, not a status bar.
        for (slit in BitGlyph.SLITS) assertEquals(slit, 3, slit.length)
    }

    @Test
    fun `the slits are distinct`() {
        assertEquals(BitGlyph.SLITS.size, BitGlyph.SLITS.distinct().size)
    }
}
