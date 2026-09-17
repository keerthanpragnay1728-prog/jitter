package dev.molasses.core.bit

import dev.molasses.core.console.ConsoleLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `padding is on the right, so the glyph's leading edge never moves`() {
        // The bug this replaces: centring kept the slot 7 wide and moved the
        // ink inside it. Five characters centred to one leading space, seven
        // to none, so the face jumped a character cell left for every blink
        // and back. It read as a twitchy blink and outlived three fixes aimed
        // at blink timing, none of which could reach it.
        assertEquals("(o_o)  ", BitGlyph.pad("(o_o)"))
        assertEquals(BitGlyph.WIDTH, BitGlyph.pad("(o_o)").length)
        assertEquals("(o_o)", BitGlyph.pad("(o_o)").trim())
    }

    @Test
    fun `every face starts at column zero, whatever its length`() {
        // Stated over the whole set rather than on one pair, because the
        // blink is only the transition that happens most often. Any two faces
        // of different lengths would move the same way, and three of them are
        // not five characters long.
        for (face in BitStateMachine.FACES) {
            val padded = BitGlyph.pad(face)
            assertEquals("face $face", BitGlyph.WIDTH, padded.length)
            assertEquals(
                "face $face does not start at column zero",
                0,
                padded.indexOfFirst { !it.isWhitespace() },
            )
        }
    }

    @Test
    fun `the blink transition moves nothing`() {
        // The exact pair the spasm was made of.
        val resting = BitGlyph.pad(BitStateMachine.NEUTRAL)
        val shut = BitGlyph.pad(BitStateMachine.BLINK_HALF)
        assertEquals(resting.length, shut.length)
        assertEquals(
            resting.indexOfFirst { !it.isWhitespace() },
            shut.indexOfFirst { !it.isWhitespace() },
        )
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

    // -------------------------------------------------- the second slot

    @Test
    fun `the slit slot is as wide as the widest slit`() {
        // Derived exactly the way WIDTH is derived from FACES, so [!] and [z]
        // cannot drift away from [|] and be silently truncated.
        assertEquals(BitGlyph.SLITS.maxOf { it.length }, BitGlyph.SLIT_WIDTH)
    }

    @Test
    fun `the slit slot is narrower than the face slot`() {
        // If it were not, the whole exercise is pointless: the retreat exists
        // to get out of the way, and it could not reach the bezel while it
        // was padded to the width of a face.
        assertTrue(
            "slit slot ${BitGlyph.SLIT_WIDTH} is not narrower than ${BitGlyph.WIDTH}",
            BitGlyph.SLIT_WIDTH < BitGlyph.WIDTH,
        )
    }

    @Test
    fun `padFor puts a slit in the narrow slot and a face in the wide one`() {
        for (slit in BitGlyph.SLITS) {
            assertEquals(slit, BitGlyph.SLIT_WIDTH, BitGlyph.padFor(slit).length)
        }
        for (face in BitStateMachine.FACES) {
            assertEquals(face, BitGlyph.WIDTH, BitGlyph.padFor(face).length)
        }
    }

    @Test
    fun `padFor is still left aligned, in either slot`() {
        // The blink fix is not undone by the second width. Every glyph starts
        // at column zero; only the trailing pad varies.
        for (glyph in BitGlyph.SLITS + BitStateMachine.FACES) {
            assertTrue(glyph, BitGlyph.padFor(glyph).startsWith(glyph))
        }
    }

    @Test
    fun `no face is a slit`() {
        // padFor keys on content, so the two sets being disjoint is what
        // makes it sound. A face spelled [|] would narrow the slot for a
        // face, which is the original snap-target bug wearing a new hat.
        for (face in BitStateMachine.FACES) {
            assertTrue("face '$face' collides with a slit", face !in BitGlyph.SLITS)
        }
        for (slit in BitGlyph.SLITS) {
            assertTrue("slit '$slit' collides with a face", slit !in BitStateMachine.FACES)
        }
    }

    @Test
    fun `no readout can be mistaken for a slit`() {
        // BitHud emits exactly WIDTH characters and a slit is shorter, so the
        // collision is arithmetically impossible rather than merely unlikely.
        // BitHudTest sweeps the input range; this pins the reason.
        assertNotEquals(BitGlyph.WIDTH, BitGlyph.SLIT_WIDTH)
        for (slit in BitGlyph.SLITS) {
            assertNotEquals(slit.length, BitGlyph.WIDTH)
        }
    }

    @Test
    fun `no BitDisplay case renders a slit beside a face`() {
        // The constraint that makes a second width safe at all: a slit and a
        // face never share a frame, so the slot is unambiguous for any one
        // frame. Enumerated over every case of BitDisplay, Speech included,
        // because Speech carries a mood and therefore renders a face rather
        // than a glyph, and that is easy to change by accident.
        val cases: List<BitDisplay> = listOf(
            BitDisplay.Face(BitStateMachine.Mood.IDLE, BitStateMachine.Reaction.None),
            BitDisplay.Face(BitStateMachine.Mood.GLITCHED, BitStateMachine.Reaction.Glitching),
            BitDisplay.Hud(HudStep.PRIMARY, BitGlyph.pad("12m")),
            BitDisplay.Speech(
                ConsoleLine.Notice("scrolled", listOf("27m")),
                BitStateMachine.Mood.VIGILANT,
            ),
            BitDisplay.Speech(
                ConsoleLine.Prompt("scrolled", listOf("27m"), action = "focus"),
                BitStateMachine.Mood.ANNOYED,
            ),
        ) + BitGlyph.SLITS.map { BitDisplay.Slit(it) }

        for (case in cases) {
            val face = BitStateMachine.frame(case, reactionAgeMs = 0L, tickMs = 0L).face
            val isSlit = case is BitDisplay.Slit
            assertEquals(
                "$case rendered '$face'",
                isSlit,
                face in BitGlyph.SLITS,
            )
            assertEquals(
                "$case rendered '$face' into the wrong slot",
                if (isSlit) BitGlyph.SLIT_WIDTH else BitGlyph.WIDTH,
                BitGlyph.padFor(face).length,
            )
        }
    }

    @Test
    fun `a speaking Bit renders a face, not a glyph`() {
        // Stated on its own because it is the case the constraint is most
        // likely to be broken by: Speech is the one display that carries
        // something other than a face and still has to produce one.
        for (mood in BitStateMachine.Mood.entries) {
            val speech = BitDisplay.Speech(ConsoleLine.Notice("scrolled"), mood)
            val face = BitStateMachine.frame(speech, reactionAgeMs = 0L, tickMs = 0L).face
            assertTrue("mood $mood spoke as '$face'", face !in BitGlyph.SLITS)
            assertEquals(mood.toString(), BitGlyph.WIDTH, BitGlyph.padFor(face).length)
        }
    }
}
