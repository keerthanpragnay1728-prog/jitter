package dev.molasses.core.bit

import dev.molasses.core.bit.BitStateMachine.Mood
import dev.molasses.core.bit.BitStateMachine.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitStateMachineTest {

    private val min = 60_000L

    private fun frame(
        mood: Mood = Mood.IDLE,
        reaction: Reaction = Reaction.None,
        ageMs: Long = 0,
        tickMs: Long = 0,
    ) = BitStateMachine.frame(mood, reaction, ageMs, tickMs)

    // ------------------------------------------------------------------ mood

    @Test
    fun `mood boundaries match the ladder`() {
        assertEquals(Mood.IDLE, BitStateMachine.moodFor(0))
        assertEquals(Mood.IDLE, BitStateMachine.moodFor(7 * min - 1))
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(7 * min))
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(15 * min - 1))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(15 * min))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(20 * min - 1))
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(20 * min))
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(99 * min))
    }

    // -------------------------------------------------------- CONFIRM

    @Test
    fun `confirm runs neutral to happy to neutral in about 900ms`() {
        val ack = "ACK: LOCK ARMED FOR 30M"
        val r = Reaction.Confirm(ack)
        assertEquals(900L, BitStateMachine.CONFIRM_TOTAL_MS)

        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 0).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 149).face)
        assertEquals(BitStateMachine.HAPPY, frame(reaction = r, ageMs = 150).face)
        assertEquals(BitStateMachine.HAPPY, frame(reaction = r, ageMs = 749).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 750).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 899).face)
    }

    @Test
    fun `confirm renders the ack line throughout and drops it at the end`() {
        val ack = "FOCUS ENGAGED"
        val r = Reaction.Confirm(ack)
        for (age in listOf(0L, 150L, 500L, 899L)) {
            assertEquals("age=$age", ack, frame(reaction = r, ageMs = age).line)
            assertTrue("age=$age", frame(reaction = r, ageMs = age).reactionActive)
        }
        val after = frame(reaction = r, ageMs = 900)
        assertNull(after.line)
        assertFalse(after.reactionActive)
    }

    @Test
    fun `confirm expires exactly at the total`() {
        val r = Reaction.Confirm("ok")
        assertFalse(BitStateMachine.isExpired(r, 899))
        assertTrue(BitStateMachine.isExpired(r, 900))
    }

    @Test
    fun `every command shares one confirm sequence`() {
        // The whole point: the faces do not depend on which command ran, only
        // the ack line does. Fourteen bespoke animations would be fourteen
        // things to keep in step.
        val faces = listOf("block", "focus", "allow", "bedtime", "timer").map { verb ->
            listOf(0L, 200L, 800L).map { age ->
                frame(reaction = Reaction.Confirm("ACK: $verb"), ageMs = age).face
            }
        }
        assertEquals(1, faces.distinct().size)
    }

    // --------------------------------------------------------- FAILED

    @Test
    fun `failed is dry and holds the error line`() {
        val msg = "block <app> <duration>"
        val r = Reaction.Failed(msg)
        assertEquals(BitStateMachine.DRY, frame(reaction = r, ageMs = 0).face)
        assertEquals(BitStateMachine.DRY, frame(reaction = r, ageMs = 1_999).face)
        assertEquals(msg, frame(reaction = r, ageMs = 1_000).line)
        assertFalse(frame(reaction = r, ageMs = 2_000).reactionActive)
    }

    @Test
    fun `failed outlasts confirm`() {
        // An error is the only place the user learns the grammar, so it has to
        // survive looking away from the keyboard.
        assertTrue(BitStateMachine.FAILED_TOTAL_MS > BitStateMachine.CONFIRM_TOTAL_MS)
    }

    // ------------------------------------------------------- other reactions

    @Test
    fun `poke and irritation are transient, turning away blocks input`() {
        assertEquals(BitStateMachine.HAPPY, frame(reaction = Reaction.Poked, ageMs = 0).face)
        assertEquals(BitStateMachine.IRRITATED, frame(reaction = Reaction.Irritated, ageMs = 0).face)

        val away = frame(reaction = Reaction.TurnedAway, ageMs = 0)
        assertEquals(BitStateMachine.TURNED_AWAY, away.face)
        assertTrue(away.ignoresInput)
        assertFalse(frame(reaction = Reaction.Poked, ageMs = 0).ignoresInput)
    }

    @Test
    fun `battery critical is not transient and keeps its readout`() {
        val f = frame(reaction = Reaction.BatteryCritical, ageMs = 999_999)
        assertEquals(BitStateMachine.BATTERY_CRITICAL, f.face)
        assertEquals(BitStateMachine.BAT_CRIT, f.line)
        assertFalse(BitStateMachine.isExpired(Reaction.BatteryCritical, 999_999))
    }

    @Test
    fun `a negative reaction age is treated as expired rather than rendering`() {
        assertFalse(frame(reaction = Reaction.Confirm("x"), ageMs = -5).reactionActive)
    }

    // -------------------------------------------------------------- the idle

    @Test
    fun `the resting face is neutral most of the time`() {
        val faces = (0 until 20_000 step 50).map { frame(tickMs = it.toLong()).face }
        val neutral = faces.count { it == BitStateMachine.NEUTRAL }
        assertTrue("neutral $neutral of ${faces.size}", neutral > faces.size * 0.9)
        assertTrue(faces.contains(BitStateMachine.BLINK_HALF))
    }

    @Test
    fun `blink intervals stay inside the three to seven second band`() {
        for (i in 0L until 500L) {
            val interval = BitStateMachine.blinkIntervalMs(i)
            assertTrue(
                "index $i gave $interval",
                interval in BitStateMachine.BLINK_MIN_INTERVAL_MS..
                    BitStateMachine.BLINK_MAX_INTERVAL_MS,
            )
        }
    }

    @Test
    fun `blink intervals are not all identical, so it does not read as a spinner`() {
        val intervals = (0L until 50L).map { BitStateMachine.blinkIntervalMs(it) }
        assertTrue("distinct=${intervals.distinct().size}", intervals.distinct().size > 5)
    }

    @Test
    fun `the blink is deterministic for the same tick`() {
        for (t in listOf(0L, 1_234L, 99_999L, 250_000L)) {
            assertEquals(frame(tickMs = t).face, frame(tickMs = t).face)
        }
    }

    @Test
    fun `blink phase never goes negative and tolerates a negative tick`() {
        assertTrue(BitStateMachine.blinkPhaseMs(-500) >= 0)
        for (t in 0L until 60_000L step 137L) {
            assertTrue("t=$t", BitStateMachine.blinkPhaseMs(t) >= 0)
        }
    }

    // ------------------------------------------------------------ the glitch

    @Test
    fun `glitch frames never run faster than twelve fps`() {
        var changes = 0
        var previous = frame(mood = Mood.GLITCHED, tickMs = 0).face
        val spanMs = 10_000L
        for (t in 1..spanMs) {
            val face = frame(mood = Mood.GLITCHED, tickMs = t).face
            if (face != previous) changes += 1
            previous = face
        }
        val maxChanges = spanMs / BitStateMachine.MIN_GLITCH_FRAME_MS
        assertTrue("$changes changes in ${spanMs}ms, cap $maxChanges", changes <= maxChanges)
    }

    @Test
    fun `only the glitched mood glitches`() {
        val calm = listOf(Mood.IDLE, Mood.VIGILANT, Mood.ANNOYED)
        for (mood in calm) {
            val faces = (0L until 3_000L step 50).map { frame(mood = mood, tickMs = it).face }
            assertFalse(mood.toString(), faces.contains(BitStateMachine.WARDEN))
        }
        val glitched = (0L until 3_000L step 50).map { frame(mood = Mood.GLITCHED, tickMs = it).face }
        assertTrue(glitched.contains(BitStateMachine.WARDEN))
    }

    @Test
    fun `a reaction overrides the mood, including while glitched`() {
        assertEquals(
            BitStateMachine.HAPPY,
            frame(mood = Mood.GLITCHED, reaction = Reaction.Confirm("ok"), ageMs = 300).face,
        )
    }

    @Test
    fun `every face is plain ascii so it cannot box on device`() {
        // The brief called for glyphs that may not render in every monospace
        // face. These are the fallbacks, and this pins them as safe.
        val faces = listOf(
            BitStateMachine.NEUTRAL, BitStateMachine.BLINK_HALF, BitStateMachine.BLINK_NARROW,
            BitStateMachine.HAPPY, BitStateMachine.DRY, BitStateMachine.IRRITATED,
            BitStateMachine.TURNED_AWAY, BitStateMachine.BATTERY_CRITICAL, BitStateMachine.WARDEN,
        )
        for (f in faces) {
            assertTrue("$f has a non-ascii char", f.all { it.code in 32..126 })
        }
    }
}
