package dev.molasses.core.bit

import dev.molasses.core.bit.BitStateMachine.Mood
import dev.molasses.core.bit.BitStateMachine.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precedence table, one test per pair.
 *
 * `glitch > HUD > reaction > mood`, and every adjacent pair plus every
 * non-adjacent pair is asserted rather than described, because precedence
 * written only in a comment is precedence that drifts the first time someone
 * adds a state.
 */
class BitDisplayTest {

    private val hud = BitDisplay.Hud(HudStep.PRIMARY, " [38m] ")
    private val confirm = Reaction.Confirm("ACK")

    private fun resolve(
        mood: Mood = Mood.IDLE,
        reaction: Reaction = Reaction.None,
        hud: BitDisplay.Hud? = null,
        shutterArmed: Boolean = false,
        curfew: Boolean = false,
        docked: Boolean = false,
        penaltyAccruing: Boolean = false,
    ) = BitDisplay.resolve(mood, reaction, hud, shutterArmed, curfew, docked, penaltyAccruing)

    // ------------------------------------------------- glitch beats all

    @Test
    fun `glitch beats the HUD`() {
        // The illusion outranks the readout. A readout that stayed legible
        // through a glitch would say plainly that something is in control.
        assertEquals(BitDisplay.Face(Mood.GLITCHED, Reaction.None), resolve(Mood.GLITCHED, hud = hud))
    }

    @Test
    fun `glitch beats a reaction`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, Reaction.None),
            resolve(Mood.GLITCHED, reaction = confirm),
        )
    }

    @Test
    fun `glitch beats the armed state`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, Reaction.None),
            resolve(Mood.GLITCHED, shutterArmed = true),
        )
    }

    @Test
    fun `glitch beats a curfew`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, Reaction.None),
            resolve(Mood.GLITCHED, curfew = true),
        )
    }

    @Test
    fun `glitch beats everything at once`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, Reaction.None),
            resolve(Mood.GLITCHED, confirm, hud, shutterArmed = true, curfew = true),
        )
    }

    // ---------------------------------------------------- HUD beats below

    @Test
    fun `the HUD beats a reaction`() {
        // The user asked a question 40 ms ago. A blink must not eat the answer.
        assertEquals(hud, resolve(reaction = confirm, hud = hud))
    }

    @Test
    fun `the HUD beats the armed state`() {
        assertEquals(hud, resolve(hud = hud, shutterArmed = true))
    }

    @Test
    fun `the HUD beats a curfew`() {
        assertEquals(hud, resolve(hud = hud, curfew = true))
    }

    @Test
    fun `the HUD beats an ordinary mood`() {
        assertEquals(hud, resolve(mood = Mood.ANNOYED, hud = hud))
    }

    @Test
    fun `a HUD on step NONE is not a HUD`() {
        // The host holds NONE between readouts rather than nulling the state,
        // and that must not blank the face.
        val idle = BitDisplay.Hud(HudStep.NONE, "       ")
        assertEquals(BitDisplay.Face(Mood.IDLE, Reaction.None), resolve(hud = idle))
    }

    // ------------------------------------------------ reaction beats mood

    @Test
    fun `a reaction plays over an ordinary mood`() {
        assertEquals(BitDisplay.Face(Mood.ANNOYED, confirm), resolve(Mood.ANNOYED, confirm))
    }

    @Test
    fun `a reaction plays over the armed state`() {
        // Deliberately: the user typed something and is owed the answer, and
        // ARMED is a condition rather than an event.
        assertEquals(BitDisplay.Face(Mood.ARMED, confirm), resolve(reaction = confirm, shutterArmed = true))
    }

    @Test
    fun `a reaction plays over a curfew`() {
        assertEquals(BitDisplay.Face(Mood.DORMANT, confirm), resolve(reaction = confirm, curfew = true))
    }

    @Test
    fun `an absorbed touch plays over the armed state it came from`() {
        // The two are the same event seen twice, and the glitch has to win or
        // the tell never renders.
        val d = resolve(reaction = Reaction.Absorbed, shutterArmed = true)
        assertEquals(BitDisplay.Face(Mood.ARMED, Reaction.Absorbed), d)
    }

    // ------------------------------------------------ inside the mood level

    @Test
    fun `armed beats a curfew`() {
        // Armed is happening right now and lasts seconds. A curfew is a
        // background condition that will still be true afterwards.
        assertEquals(BitDisplay.Face(Mood.ARMED, Reaction.None), resolve(shutterArmed = true, curfew = true))
    }

    @Test
    fun `armed beats the accumulated mood`() {
        assertEquals(
            BitDisplay.Face(Mood.ARMED, Reaction.None),
            resolve(mood = Mood.VIGILANT, shutterArmed = true),
        )
    }

    @Test
    fun `a curfew beats the accumulated mood`() {
        assertEquals(
            BitDisplay.Face(Mood.DORMANT, Reaction.None),
            resolve(mood = Mood.VIGILANT, curfew = true),
        )
    }

    @Test
    fun `with nothing happening the mood is what shows`() {
        assertEquals(BitDisplay.Face(Mood.VIGILANT, Reaction.None), resolve(mood = Mood.VIGILANT))
    }

    // ------------------------------------------------------- the docked slit

    @Test
    fun `a retreated Bit is a slit, not a face`() {
        assertEquals(BitDisplay.Slit(BitGlyph.SLIT_NORMAL), resolve(docked = true))
    }

    @Test
    fun `the slit carries the alert when a checkpoint is overdue`() {
        assertEquals(
            BitDisplay.Slit(BitGlyph.SLIT_ALERT),
            resolve(docked = true, penaltyAccruing = true),
        )
    }

    @Test
    fun `the slit carries the curfew glyph during a bedtime lock`() {
        assertEquals(BitDisplay.Slit(BitGlyph.SLIT_CURFEW), resolve(docked = true, curfew = true))
    }

    @Test
    fun `glitch beats the slit`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, Reaction.None),
            resolve(Mood.GLITCHED, docked = true),
        )
    }

    @Test
    fun `the HUD beats the slit`() {
        // Tapping a docked Bit is what opens the HUD, so this is the ordinary
        // case rather than an edge one.
        assertEquals(hud, resolve(hud = hud, docked = true))
    }

    @Test
    fun `a reaction beats the slit`() {
        // A command the user just typed is owed its answer, retreated or not.
        assertEquals(BitDisplay.Face(Mood.IDLE, confirm), resolve(reaction = confirm, docked = true))
    }

    @Test
    fun `the armed tell beats the slit`() {
        // The one thing in this app that is otherwise completely invisible.
        // Hiding it behind a retreat would lose it exactly when it means
        // something.
        assertEquals(
            BitDisplay.Face(Mood.ARMED, Reaction.None),
            resolve(docked = true, shutterArmed = true),
        )
    }

    @Test
    fun `the slit beats the resting mood`() {
        assertEquals(
            BitDisplay.Slit(BitGlyph.SLIT_NORMAL),
            resolve(mood = Mood.ANNOYED, docked = true),
        )
    }

    @Test
    fun `an undocked Bit is never a slit`() {
        for (curfew in listOf(false, true)) {
            for (penalty in listOf(false, true)) {
                val d = resolve(curfew = curfew, penaltyAccruing = penalty)
                assertTrue("$curfew $penalty", d is BitDisplay.Face)
            }
        }
    }

    // ---------------------------------------------- the absorbed touch tell

    @Test
    fun `an absorbed touch never carries a line`() {
        // The moment Bit narrates a stall the uncanny phase is over. Asserted
        // rather than described, because a line is one constructor argument
        // away at every point in the future.
        for (age in listOf(0L, 100L, 199L)) {
            val f = BitStateMachine.frame(Mood.IDLE, Reaction.Absorbed, age, 0)
            assertEquals(BitStateMachine.ASYMMETRIC, f.face)
            assertEquals(null, f.line)
        }
    }

    @Test
    fun `the absorbed tell is short enough to read as a flicker`() {
        assertTrue(BitStateMachine.ABSORBED_TOTAL_MS <= 250L)
        assertTrue(BitStateMachine.isExpired(Reaction.Absorbed, BitStateMachine.ABSORBED_TOTAL_MS))
        assertTrue(!BitStateMachine.isExpired(Reaction.Absorbed, BitStateMachine.ABSORBED_TOTAL_MS - 1))
    }

    @Test
    fun `the absorbed face is distinct from every other face`() {
        assertEquals(
            BitStateMachine.FACES.size,
            BitStateMachine.FACES.distinct().size,
        )
    }

    // ------------------------------------------------------------ totality

    @Test
    fun `every combination resolves to something`() {
        // Totality, asserted rather than assumed. Five inputs, and the
        // resolver must never be able to return nothing or throw.
        val moods = Mood.entries
        val reactions = listOf(Reaction.None, confirm, Reaction.Absorbed, Reaction.TurnedAway)
        val huds = listOf(null, hud, BitDisplay.Hud(HudStep.NONE, ""))
        for (m in moods) {
            for (r in reactions) {
                for (h in huds) {
                    for (armed in listOf(false, true)) {
                        for (curfew in listOf(false, true)) {
                            for (docked in listOf(false, true)) {
                                for (pen in listOf(false, true)) {
                                    val d = BitDisplay.resolve(m, r, h, armed, curfew, docked, pen)
                                    assertTrue(
                                        "$m $r $h $armed $curfew $docked $pen",
                                        d is BitDisplay.Face ||
                                            d is BitDisplay.Hud ||
                                            d is BitDisplay.Slit,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `resolve is deterministic`() {
        // It is called every frame at 25 fps. A resolver that could answer
        // differently for the same inputs would flicker.
        repeat(5) {
            assertEquals(
                resolve(Mood.ANNOYED, confirm, null, shutterArmed = true, curfew = true),
                resolve(Mood.ANNOYED, confirm, null, shutterArmed = true, curfew = true),
            )
        }
    }

    // ---------------------------------------------------------- rendering

    @Test
    fun `a HUD display renders its text and no reaction clock`() {
        val f = BitStateMachine.frame(hud, reactionAgeMs = 0, tickMs = 0)
        assertEquals(" [38m] ", f.face)
        assertEquals(null, f.line)
        // The host tears down anything reactionActive on expiry. The HUD owns
        // its own timeout, so it must not look like a reaction.
        assertTrue(!f.reactionActive)
    }

    @Test
    fun `a face display renders through the existing machine`() {
        val display = BitDisplay.Face(Mood.IDLE, confirm)
        assertEquals(
            BitStateMachine.frame(Mood.IDLE, confirm, 300, 0),
            BitStateMachine.frame(display, 300, 0),
        )
    }

    @Test
    fun `the armed face does not blink`() {
        // A blink reads as idling. Armed is a live readout.
        val armed = BitDisplay.Face(Mood.ARMED, Reaction.None)
        val faces = (0L..20_000L step 37L).map { BitStateMachine.frame(armed, 0, it).face }.toSet()
        assertEquals(setOf(BitStateMachine.FLAT), faces)
    }

    @Test
    fun `the dormant face does not blink`() {
        val dormant = BitDisplay.Face(Mood.DORMANT, Reaction.None)
        val faces = (0L..20_000L step 37L).map { BitStateMachine.frame(dormant, 0, it).face }.toSet()
        assertEquals(setOf(BitStateMachine.DORMANT), faces)
    }
}
