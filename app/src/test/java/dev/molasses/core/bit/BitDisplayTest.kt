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

    private val glitch = Reaction.Glitching

    @Test
    fun `the glitch burst beats the HUD`() {
        // The illusion outranks the readout. A readout that stayed legible
        // through a glitch would say plainly that something is in control.
        assertEquals(BitDisplay.Face(Mood.IDLE, glitch), resolve(reaction = glitch, hud = hud))
    }

    @Test
    fun `the glitch burst beats the armed state`() {
        assertEquals(
            BitDisplay.Face(Mood.IDLE, glitch),
            resolve(reaction = glitch, shutterArmed = true),
        )
    }

    @Test
    fun `the glitch burst beats a curfew`() {
        assertEquals(BitDisplay.Face(Mood.IDLE, glitch), resolve(reaction = glitch, curfew = true))
    }

    @Test
    fun `the glitch burst beats the slit`() {
        assertEquals(BitDisplay.Face(Mood.IDLE, glitch), resolve(reaction = glitch, docked = true))
    }

    @Test
    fun `the glitch burst beats everything at once`() {
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, glitch),
            resolve(Mood.GLITCHED, glitch, hud, shutterArmed = true, curfew = true, docked = true),
        )
    }

    @Test
    fun `the burst runs broken to composed and ends on a whole frame`() {
        // Even frame count, so it hands back on NEUTRAL rather than cutting
        // off mid-WARDEN. A convulsion, not a fault.
        assertEquals(0L, BitStateMachine.GLITCH_BURST_FRAMES % 2)
        assertEquals(
            BitStateMachine.MIN_GLITCH_FRAME_MS * BitStateMachine.GLITCH_BURST_FRAMES,
            BitStateMachine.GLITCH_BURST_MS,
        )
        val faces = (0 until BitStateMachine.GLITCH_BURST_FRAMES).map {
            BitStateMachine.frame(
                BitDisplay.Face(Mood.IDLE, glitch),
                it * BitStateMachine.MIN_GLITCH_FRAME_MS,
                0,
            ).face
        }
        assertEquals(BitStateMachine.WARDEN, faces.first())
        assertEquals(BitStateMachine.NEUTRAL, faces.last())
        assertEquals(setOf(BitStateMachine.WARDEN, BitStateMachine.NEUTRAL), faces.toSet())
    }

    @Test
    fun `the burst is silent`() {
        for (age in 0 until BitStateMachine.GLITCH_BURST_MS step 17L) {
            assertEquals(null, BitStateMachine.frame(Mood.IDLE, glitch, age, 0).line)
        }
    }

    @Test
    fun `the burst expires, unlike the mood it replaced`() {
        assertTrue(BitStateMachine.isExpired(glitch, BitStateMachine.GLITCH_BURST_MS))
        assertTrue(!BitStateMachine.isExpired(glitch, BitStateMachine.GLITCH_BURST_MS - 1))
    }

    // ------------------------- the regression the transient glitch creates

    @Test
    fun `the HUD is reachable at the terminal tier`() {
        // While the glitch was a permanent mood it sat at the top of the
        // table, so a user past the terminal could not read their own cycle
        // timer at all. This is that regression, pinned.
        assertEquals(hud, resolve(mood = Mood.GLITCHED, hud = hud))
    }

    @Test
    fun `command feedback is reachable at the terminal tier`() {
        // The sharper half: the command bar went mute exactly when someone
        // was most likely to reach for it.
        assertEquals(
            BitDisplay.Face(Mood.GLITCHED, confirm),
            resolve(mood = Mood.GLITCHED, reaction = confirm),
        )
    }

    @Test
    fun `an unavailable reason is reachable at the terminal tier`() {
        val reaction = Reaction.Unavailable("nope")
        val f = BitStateMachine.frame(resolve(mood = Mood.GLITCHED, reaction = reaction), 0, 0)
        assertEquals("nope", f.line)
    }

    @Test
    fun `the permanent terminal face is still there, at the bottom`() {
        // It did not go away, it moved to where a mood belongs.
        val d = resolve(mood = Mood.GLITCHED)
        assertEquals(BitDisplay.Face(Mood.GLITCHED, Reaction.None), d)
        val faces = (0L..2_000L step 37L)
            .map { BitStateMachine.frame(d, 0, it).face }
            .toSet()
        assertEquals(setOf(BitStateMachine.WARDEN, BitStateMachine.NEUTRAL), faces)
    }

    @Test
    fun `the terminal mood no longer outranks anything`() {
        // The whole point of the change, stated as one assertion.
        for (r in listOf(confirm, Reaction.Absorbed, Reaction.Poked)) {
            assertEquals(BitDisplay.Face(Mood.GLITCHED, r), resolve(mood = Mood.GLITCHED, reaction = r))
        }
        assertEquals(hud, resolve(mood = Mood.GLITCHED, hud = hud))
        assertTrue(resolve(mood = Mood.GLITCHED, docked = true) is BitDisplay.Slit)
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
    fun `the terminal mood does not beat the slit`() {
        // It is a mood now, and moods sit at the bottom of the table. A
        // retreated Bit stays retreated at the terminal; the permanent signal
        // there is the stall marker, and the slit is already carrying the
        // overdue checkpoint that the terminal guarantees.
        assertEquals(
            BitDisplay.Slit(BitGlyph.SLIT_ALERT),
            resolve(Mood.GLITCHED, docked = true, penaltyAccruing = true),
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

    // ------------------------------------------ the two uses of flat eyes

    @Test
    fun `an unavailable reason never shows its face without its line`() {
        // FLAT is now two things: "that command cannot run" and "the sink is
        // armed". They are only distinguishable by the line, so the line has
        // to last exactly as long as the face does. It does, because both
        // come out of the same phased() call with the same total, and this is
        // the test that keeps it that way.
        val reaction = Reaction.Unavailable("locks are not enforced yet")
        for (age in 0 until BitStateMachine.UNAVAILABLE_TOTAL_MS step 7L) {
            val f = BitStateMachine.frame(Mood.IDLE, reaction, age, tickMs = 0)
            assertEquals("age=$age", BitStateMachine.FLAT, f.face)
            assertEquals("age=$age", "locks are not enforced yet", f.line)
        }
    }

    @Test
    fun `when the reason expires the flat face goes with it`() {
        val reaction = Reaction.Unavailable("nope")
        val f = BitStateMachine.frame(Mood.IDLE, reaction, BitStateMachine.UNAVAILABLE_TOTAL_MS, 0)
        assertEquals(null, f.line)
        assertTrue("a flat face with no line must mean armed", f.face != BitStateMachine.FLAT)
    }

    @Test
    fun `flat eyes with no line can only mean the sink is armed`() {
        // The residual case, stated as a test rather than left implicit: the
        // only way to reach FLAT without a line beside it is the armed state.
        val armed = BitStateMachine.frame(BitDisplay.Face(Mood.ARMED, Reaction.None), 0, 0)
        assertEquals(BitStateMachine.FLAT, armed.face)
        assertEquals(null, armed.line)
    }

    @Test
    fun `an armed sink does not shorten the reason showing over it`() {
        // resolve puts a reaction over ARMED, so the line still runs its full
        // course while the sink is armed underneath.
        val reaction = Reaction.Unavailable("nope")
        val d = resolve(reaction = reaction, shutterArmed = true)
        val f = BitStateMachine.frame(d, BitStateMachine.UNAVAILABLE_TOTAL_MS - 1, 0)
        assertEquals(BitStateMachine.FLAT, f.face)
        assertEquals("nope", f.line)
    }

    // ------------------------------------------------------------ totality

    @Test
    fun `every combination resolves to something`() {
        // Totality, asserted rather than assumed. Five inputs, and the
        // resolver must never be able to return nothing or throw.
        val moods = Mood.entries
        val reactions =
            listOf(Reaction.None, confirm, Reaction.Absorbed, Reaction.TurnedAway, glitch)
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
