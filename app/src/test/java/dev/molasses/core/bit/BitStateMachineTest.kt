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
        // Anchored to the friction curve, not to the old discrete ladder.
        // Idle until the curve starts, glitched when it saturates, and the
        // two in between split that span evenly.
        val onset = dev.molasses.core.friction.FrictionCurve.ONSET_MS
        val terminal = dev.molasses.core.friction.FrictionCurve.TERMINAL_MS
        val mid = BitStateMachine.MOOD_MIDPOINT_MS
        val horizon = dev.molasses.core.friction.FrictionCurve.DEFAULT_HORIZON_MS

        assertEquals(Mood.IDLE, BitStateMachine.moodFor(0, horizon))
        assertEquals(Mood.IDLE, BitStateMachine.moodFor(onset - 1, horizon))
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(onset, horizon))
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(mid - 1, horizon))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(mid, horizon))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(terminal - 1, horizon))
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(terminal, horizon))
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(99 * min, horizon))

        // The values those derivations actually produce at the default
        // horizon, so a change to the curve shows up here as a deliberate
        // edit rather than silently.
        assertEquals(10 * min, onset)
        assertEquals(17 * min + 30_000L, mid)
        assertEquals(25 * min, terminal)

        // Bit stops being idle exactly when stalls begin, at every horizon.
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(onset, horizon))
        // And the most alarming face no longer arrives before the worst
        // friction with nowhere left to go: twenty minutes is still short of
        // saturation on the default horizon.
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(20 * min, horizon))
    }

    @Test
    fun `mood scales with the horizon it is read against`() {
        // The defect this guards: a mood read against a fixed pair reports a
        // friction level the engine is not producing. Twenty minutes is
        // saturated on an eighteen minute horizon and has not started on a
        // sixty minute one, and both are correct for the app they describe.
        val short = 18L * min
        val long = 60L * min
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(20 * min, short))
        assertEquals(Mood.IDLE, BitStateMachine.moodFor(20 * min, long))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(20 * min, 25L * min))
        assertEquals(Mood.VIGILANT, BitStateMachine.moodFor(25 * min, long))
        assertEquals(Mood.ANNOYED, BitStateMachine.moodFor(45 * min, long))
        assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(60 * min, long))
    }

    @Test
    fun `every horizon has all four moods in order`() {
        // No horizon may collapse a mood out of existence, which a bad
        // onset fraction or a bad midpoint would do silently.
        for (h in listOf(10L, 18L, 30L, 60L).map { it * min }) {
            val seen = (0..(h / 1000)).map {
                BitStateMachine.moodFor(it * 1000, h)
            }
            assertEquals(
                "horizon ${h / min}m",
                listOf(Mood.IDLE, Mood.VIGILANT, Mood.ANNOYED, Mood.GLITCHED),
                seen.distinct(),
            )
            assertEquals(Mood.GLITCHED, BitStateMachine.moodFor(h, h))
        }
    }

    // -------------------------------------------------------- CONFIRM

    @Test
    fun `confirm runs neutral to happy to neutral in about 900ms`() {
        // The expression is still 900 ms. What changed is that the line no
        // longer ends with it: the face is neutral for the whole linger, so
        // the sequence below is unaffected and the name of this test is still
        // true.
        val ack = "ACK: LOCK ARMED FOR 30M"
        val r = Reaction.Confirm(ack)
        assertEquals(
            900L,
            BitStateMachine.CONFIRM_RISE_MS +
                BitStateMachine.CONFIRM_HOLD_MS +
                BitStateMachine.CONFIRM_FALL_MS,
        )

        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 0).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 149).face)
        assertEquals(BitStateMachine.HAPPY, frame(reaction = r, ageMs = 150).face)
        assertEquals(BitStateMachine.HAPPY, frame(reaction = r, ageMs = 749).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 750).face)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 899).face)
    }

    @Test
    fun `the ack line outlives the expression`() {
        // The bug this fixes: the line lasted exactly as long as a face
        // changing and back, which is a number chosen for an expression and
        // then handed to a sentence. The expression still ends at 900; the
        // text stays while the eye is on it.
        val r = Reaction.Confirm("ACK: WIFI PANEL")
        assertTrue(BitStateMachine.CONFIRM_TOTAL_MS > 900L)
        assertEquals(BitStateMachine.NEUTRAL, frame(reaction = r, ageMs = 1_000).face)
        assertEquals("ACK: WIFI PANEL", frame(reaction = r, ageMs = 1_000).line)
    }

    @Test
    fun `a neutral face with no line is Bit at rest, so the linger is safe`() {
        // The invariant the linger could have broken, stated the other way
        // round. FLAT with no line means the sink is armed, which is why
        // Reaction.Unavailable must expire with its face. NEUTRAL carries no
        // such second meaning, so a neutral face outliving nothing and a
        // neutral face carrying an ack are both unambiguous.
        // blinking = false explicitly: at tick zero the derived blink phase
        // is inside the shut half, so the resting face is the blink rather
        // than the open eyes, which is correct and not the point here.
        val rest = BitStateMachine.frame(
            Mood.IDLE, Reaction.None, reactionAgeMs = 0, tickMs = 0, blinking = false,
        )
        assertEquals(BitStateMachine.NEUTRAL, rest.face)
        assertNull(rest.line)
    }

    @Test
    fun `confirm renders the ack line throughout and drops it at the end`() {
        val ack = "FOCUS ENGAGED"
        val r = Reaction.Confirm(ack)
        for (age in listOf(0L, 150L, 500L, 899L, BitStateMachine.CONFIRM_TOTAL_MS - 1)) {
            assertEquals("age=$age", ack, frame(reaction = r, ageMs = age).line)
            assertTrue("age=$age", frame(reaction = r, ageMs = age).reactionActive)
        }
        val after = frame(reaction = r, ageMs = BitStateMachine.CONFIRM_TOTAL_MS)
        assertNull(after.line)
        assertFalse(after.reactionActive)
    }

    @Test
    fun `confirm expires exactly at the total`() {
        val r = Reaction.Confirm("ok")
        assertFalse(BitStateMachine.isExpired(r, BitStateMachine.CONFIRM_TOTAL_MS - 1))
        assertTrue(BitStateMachine.isExpired(r, BitStateMachine.CONFIRM_TOTAL_MS))
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
        assertEquals(
            BitStateMachine.DRY,
            frame(reaction = r, ageMs = BitStateMachine.FAILED_TOTAL_MS - 1).face,
        )
        assertEquals(msg, frame(reaction = r, ageMs = 1_000).line)
        assertFalse(
            frame(reaction = r, ageMs = BitStateMachine.FAILED_TOTAL_MS).reactionActive,
        )
    }

    @Test
    fun `failed outlasts confirm`() {
        // An error is the only place the user learns the grammar, so it has to
        // survive looking away from the keyboard.
        assertTrue(BitStateMachine.FAILED_TOTAL_MS > BitStateMachine.CONFIRM_TOTAL_MS)
    }

    // --------------------------------------------------------- UNAVAILABLE

    @Test
    fun `unavailable is flat and distinct from failed`() {
        // "block is not wired yet" and "block what?" are different
        // information. Showing the same face for both teaches the user to
        // ignore it.
        val unavailable = frame(reaction = Reaction.Unavailable("locks are not enforced yet"), ageMs = 0)
        val failed = frame(reaction = Reaction.Failed("block <app> <duration>"), ageMs = 0)
        assertEquals(BitStateMachine.FLAT, unavailable.face)
        assertEquals(BitStateMachine.DRY, failed.face)
        assertTrue(unavailable.face != failed.face)
    }

    @Test
    fun `unavailable holds its reason as long as a failure holds its hint`() {
        val r = Reaction.Unavailable("service not bound")
        assertEquals("service not bound", frame(reaction = r, ageMs = 1_000).line)
        assertFalse(
            frame(reaction = r, ageMs = BitStateMachine.UNAVAILABLE_TOTAL_MS).reactionActive,
        )
        assertEquals(
            BitStateMachine.FAILED_TOTAL_MS,
            BitStateMachine.UNAVAILABLE_TOTAL_MS,
        )
    }

    @Test
    fun `all three command reactions are visually distinct`() {
        val faces = listOf(
            frame(reaction = Reaction.Confirm("ok"), ageMs = 300).face,
            frame(reaction = Reaction.Failed("bad"), ageMs = 300).face,
            frame(reaction = Reaction.Unavailable("nope"), ageMs = 300).face,
        )
        assertEquals("two reactions share a face", 3, faces.distinct().size)
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
        val f = frame(mood = Mood.BATTERY_CRITICAL, reaction = Reaction.None, ageMs = 999_999)
        assertEquals(BitStateMachine.BATTERY_CRITICAL, f.face)
        assertEquals(BitStateMachine.BAT_CRIT, f.line)
        // A condition, not a reaction: there is nothing to expire, and as a
        // reaction that never expired it would have blocked every other
        // reaction behind it. That is why nothing ever constructed it.
        assertFalse(BitStateMachine.isExpired(Reaction.None, 999_999))
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
            BitStateMachine.FLAT,
        )
        for (f in faces) {
            assertTrue("$f has a non-ascii char", f.all { it.code in 32..126 })
        }
    }

    // ------------------------------------------------------------- the blink

    @Test
    fun `the eyes stay shut for at least three frames`() {
        // The spasm. At 75 ms the host's 40 ms sampling caught the closure
        // once or twice depending on where the two phases lined up, so the
        // eyes shut for a single frame, or for two, and which one drifted. A
        // single frame is a flicker, not a blink.
        assertTrue(
            "BLINK_HALF_MS must survive sampling at TICK_MS",
            BitStateMachine.BLINK_HALF_MS >= 3 * BitStateMachine.TICK_MS,
        )
    }

    @Test
    fun `a blink is caught by the same number of frames at every phase`() {
        // The inconsistency was the tell, more than the brevity. Sweep the
        // sampling phase across a whole cycle and require the count never to
        // vary by more than one frame.
        val counts = (0 until 40).map { offset ->
            var cycle = BitStateMachine.BlinkCycle()
            var closed = 0
            var t = offset.toLong()
            while (t < 30_000L) {
                cycle = BitStateMachine.advanceBlink(cycle, t)
                if (BitStateMachine.isBlinking(cycle, t)) closed += 1
                t += BitStateMachine.TICK_MS
            }
            closed
        }
        assertTrue("phase-dependent blink count: $counts", counts.max() - counts.min() <= 1)
    }

    @Test
    fun `the carried cycle agrees with the derivation it replaces`() {
        // The walk is correct and is what every other test here exercises;
        // this is the cheap one the host uses. They must not disagree.
        var cycle = BitStateMachine.BlinkCycle()
        var t = 0L
        while (t < 60_000L) {
            cycle = BitStateMachine.advanceBlink(cycle, t)
            assertEquals(
                "at $t",
                BitStateMachine.blinkPhaseMs(t) < BitStateMachine.BLINK_HALF_MS,
                BitStateMachine.isBlinking(cycle, t),
            )
            t += 13L
        }
    }

    @Test
    fun `advancing is one step in the steady state`() {
        var cycle = BitStateMachine.advanceBlink(BitStateMachine.BlinkCycle(), 20_000L)
        val before = cycle.index
        cycle = BitStateMachine.advanceBlink(cycle, 20_040L)
        assertTrue("a single frame must not skip cycles", cycle.index - before <= 1)
    }

    @Test
    fun `a host that resets its clock starts over rather than looping`() {
        // The pager disposes this page on every swipe to the ledger, which
        // used to restart the tick origin underneath a schedule that assumed
        // it only ever grew.
        val cycle = BitStateMachine.advanceBlink(BitStateMachine.BlinkCycle(), 50_000L)
        val reset = BitStateMachine.advanceBlink(cycle, 0L)
        assertEquals(0L, reset.index)
        assertEquals(0L, reset.startedAtMs)
    }

    @Test
    fun `a long jump forward terminates`() {
        // A launcher resumed after a day does not walk a day of cycles.
        val cycle = BitStateMachine.advanceBlink(BitStateMachine.BlinkCycle(), 24 * 60 * 60_000L)
        assertTrue(cycle.startedAtMs >= 0L)
    }

    @Test
    fun `intervals stay inside the specced band`() {
        for (i in 0L until 500L) {
            val interval = BitStateMachine.blinkIntervalMs(i)
            assertTrue("$i -> $interval", interval >= BitStateMachine.BLINK_MIN_INTERVAL_MS)
            assertTrue("$i -> $interval", interval <= BitStateMachine.BLINK_MAX_INTERVAL_MS)
        }
    }
}
