package dev.molasses.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSpeechTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val notice = ConsoleLine.Notice("scrolled", listOf("27m", "Instagram"))
    private val prompt = ConsoleLine.Prompt("scrolled", listOf("27m"), action = "focus")
    private val anchor = t0 - 3 * ConsoleSpeech.HOUR_MS

    private fun evaluate(
        queued: ConsoleLine? = notice,
        budget: ConsoleSpeech.Budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor),
        gate: ConsoleSpeech.Gate = ConsoleSpeech.Gate(),
        nowWallMs: Long = t0,
        cycleAnchorWallMs: Long = anchor,
    ) = ConsoleSpeech.evaluate(queued, budget, gate, cycleAnchorWallMs, nowWallMs)

    private fun held(v: ConsoleSpeech.Verdict) = (v as ConsoleSpeech.Verdict.Held).reason

    // ------------------------------------------------------- the two lifetimes

    @Test
    fun `a notice expires after eight seconds`() {
        assertFalse(ConsoleSpeech.noticeExpired(0))
        assertFalse(ConsoleSpeech.noticeExpired(ConsoleSpeech.NOTICE_TIMEOUT_MS - 1))
        assertTrue(ConsoleSpeech.noticeExpired(ConsoleSpeech.NOTICE_TIMEOUT_MS))
    }

    @Test
    fun `a negative age reads as expired rather than as a fresh notice`() {
        assertTrue(ConsoleSpeech.noticeExpired(-1))
    }

    @Test
    fun `a prompt is never dismissed by a clock tick`() {
        // The lifetime that matters. There is no call that can expire a
        // prompt: noticeExpired takes an age and a prompt has none, and the
        // resolver holds it until the host clears it. A question that expired
        // on a clock would be a question asked into an empty room.
        val rendered = evaluate(queued = prompt) as ConsoleSpeech.Verdict.Render
        assertEquals(prompt, rendered.line)
        // Its own id is now seen, so re-evaluating cannot re-deliver it, but
        // nothing in this module removes a live prompt on time passing.
        for (ageMs in listOf(0L, 8_000L, 60_000L, 24 * 60 * 60_000L)) {
            val again = evaluate(queued = prompt, budget = rendered.budget, nowWallMs = t0 + ageMs)
            assertEquals("age=$ageMs", ConsoleSpeech.Hold.ALREADY_SEEN, held(again))
        }
    }

    // ------------------------------------------------ counted on render only

    @Test
    fun `rendering is the only thing that spends budget`() {
        val r = evaluate() as ConsoleSpeech.Verdict.Render
        assertEquals(listOf(t0), r.budget.deliveredAtWallMs)
        assertEquals(setOf("scrolled"), r.budget.seenIds)
    }

    @Test
    fun `every hold spends nothing`() {
        // The rule the whole file exists for. A line counted but never seen
        // costs the user one of three an hour and gives them nothing, and
        // from the outside it is indistinguishable from a line that was
        // shown.
        val gates = listOf(
            ConsoleSpeech.Gate(callInProgress = true),
            ConsoleSpeech.Gate(sensitiveForeground = true),
            ConsoleSpeech.Gate(gateActive = true),
            ConsoleSpeech.Gate(outranked = true),
        )
        for (gate in gates) {
            assertTrue("$gate", evaluate(gate = gate) is ConsoleSpeech.Verdict.Held)
        }
    }

    @Test
    fun `a held line is still there on the next visit`() {
        // Queue, do not drop. Suppressed once is not spent.
        val blocked = evaluate(gate = ConsoleSpeech.Gate(callInProgress = true))
        assertEquals(ConsoleSpeech.Hold.CALL, held(blocked))
        val later = evaluate(nowWallMs = t0 + minute)
        assertTrue(later is ConsoleSpeech.Verdict.Render)
    }

    // ------------------------------------------------- delivery suppression

    @Test
    fun `silent during a call`() {
        assertEquals(
            ConsoleSpeech.Hold.CALL,
            held(evaluate(gate = ConsoleSpeech.Gate(callInProgress = true))),
        )
    }

    @Test
    fun `silent over a financial package`() {
        assertEquals(
            ConsoleSpeech.Hold.SENSITIVE,
            held(evaluate(gate = ConsoleSpeech.Gate(sensitiveForeground = true))),
        )
    }

    @Test
    fun `silent while a gate is up`() {
        assertEquals(
            ConsoleSpeech.Hold.GATE,
            held(evaluate(gate = ConsoleSpeech.Gate(gateActive = true))),
        )
    }

    @Test
    fun `never the same line twice in a cycle`() {
        val r = evaluate() as ConsoleSpeech.Verdict.Render
        assertEquals(ConsoleSpeech.Hold.ALREADY_SEEN, held(evaluate(budget = r.budget)))
    }

    @Test
    fun `a new cycle clears what has been said`() {
        // The set expires itself: a different anchor is a different cycle, so
        // nothing has to remember to reset anything.
        val r = evaluate() as ConsoleSpeech.Verdict.Render
        val next = evaluate(budget = r.budget, cycleAnchorWallMs = t0)
        assertTrue(next is ConsoleSpeech.Verdict.Render)
    }

    @Test
    fun `an empty queue is not an error`() {
        assertEquals(ConsoleSpeech.Hold.NOTHING_QUEUED, held(evaluate(queued = null)))
    }

    // -------------------------------------------------------------- the caps

    @Test
    fun `three an hour`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..ConsoleSpeech.MAX_PER_HOUR) {
            val line = ConsoleLine.Notice("line$i")
            val r = evaluate(queued = line, budget = budget, nowWallMs = t0 + i * minute)
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        val fourth = evaluate(queued = ConsoleLine.Notice("line4"), budget = budget, nowWallMs = t0 + 4 * minute)
        assertEquals(ConsoleSpeech.Hold.HOURLY_CAP, held(fourth))
    }

    @Test
    fun `the hour is a rolling window, not a clock hour`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..ConsoleSpeech.MAX_PER_HOUR) {
            val r = evaluate(queued = ConsoleLine.Notice("line$i"), budget = budget, nowWallMs = t0 + i * minute)
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        // One hour after the first, the first has aged out and there is room.
        val later = evaluate(
            queued = ConsoleLine.Notice("line4"),
            budget = budget,
            nowWallMs = t0 + minute + ConsoleSpeech.HOUR_MS,
        )
        assertTrue(later is ConsoleSpeech.Verdict.Render)
    }

    @Test
    fun `eight a day`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        // Spaced beyond the hourly window so only the daily cap can bite.
        for (i in 1..ConsoleSpeech.MAX_PER_DAY) {
            val at = t0 + i * 2 * ConsoleSpeech.HOUR_MS
            val r = evaluate(queued = ConsoleLine.Notice("line$i"), budget = budget, nowWallMs = at)
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        val ninth = evaluate(
            queued = ConsoleLine.Notice("line9"),
            budget = budget,
            nowWallMs = t0 + 17 * ConsoleSpeech.HOUR_MS,
        )
        assertEquals(ConsoleSpeech.Hold.DAILY_CAP, held(ninth))
    }

    @Test
    fun `the budget never grows without bound`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..50) {
            val at = t0 + i * 2 * ConsoleSpeech.HOUR_MS
            val v = evaluate(queued = ConsoleLine.Notice("line$i"), budget = budget, nowWallMs = at)
            if (v is ConsoleSpeech.Verdict.Render) budget = v.budget
            assertTrue(budget.deliveredAtWallMs.size <= ConsoleSpeech.MAX_PER_DAY)
        }
    }

    @Test
    fun `a backwards clock cannot resurrect old deliveries`() {
        // Deliveries in the future of "now" are dropped rather than counted,
        // so a wound-back clock frees the budget rather than locking it.
        // That is the safe direction here: the failure is Bit speaking more,
        // and nobody winds their clock to be nagged.
        val r = evaluate() as ConsoleSpeech.Verdict.Render
        val wound = evaluate(
            queued = ConsoleLine.Notice("other"),
            budget = r.budget,
            nowWallMs = t0 - ConsoleSpeech.DAY_MS,
        )
        assertTrue(wound is ConsoleSpeech.Verdict.Render)
    }

    @Test
    fun `the caps apply to prompts as well as notices`() {
        // Both are unprompted. A prompt is not a free line because it has
        // buttons on it.
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..ConsoleSpeech.MAX_PER_HOUR) {
            val r = evaluate(
                queued = ConsoleLine.Prompt("p$i", action = "x"),
                budget = budget,
                nowWallMs = t0 + i * minute,
            )
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        assertEquals(
            ConsoleSpeech.Hold.HOURLY_CAP,
            held(
                evaluate(
                    queued = ConsoleLine.Prompt("p4", action = "x"),
                    budget = budget,
                    nowWallMs = t0 + 4 * minute,
                ),
            ),
        )
    }

    // ------------------------------------------------- the greeting counter

    private val greeting = ConsoleLine.Notice(
        ConsoleIds.GREETING_MORNING,
        category = ConsoleLine.Category.GREETING,
    )

    @Test
    fun `a greeting does not spend an observation`() {
        // Sharing the budget would mean a morning greeting costing the day
        // one of its three remarks.
        val r = evaluate(queued = greeting) as ConsoleSpeech.Verdict.Render
        assertTrue(r.budget.deliveredAtWallMs.isEmpty())
        assertEquals(listOf(t0), r.budget.greetedAtWallMs)
    }

    @Test
    fun `an observation does not spend a greeting`() {
        val r = evaluate() as ConsoleSpeech.Verdict.Render
        assertTrue(r.budget.greetedAtWallMs.isEmpty())
    }

    @Test
    fun `a day spent scrolling does not silence the greeting`() {
        // The other half, and the one that matters: the observation caps are
        // the ones that fill up.
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..ConsoleSpeech.MAX_PER_DAY) {
            val at = t0 + i * 2 * ConsoleSpeech.HOUR_MS
            val r = evaluate(queued = ConsoleLine.Notice("line$i"), budget = budget, nowWallMs = at)
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        val hello = evaluate(
            queued = greeting,
            budget = budget,
            nowWallMs = t0 + 17 * ConsoleSpeech.HOUR_MS,
        )
        assertTrue(hello is ConsoleSpeech.Verdict.Render)
    }

    @Test
    fun `three greetings a day`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..Greeting.MAX_PER_DAY) {
            val r = evaluate(queued = greeting, budget = budget, nowWallMs = t0 + i * minute)
            budget = (r as ConsoleSpeech.Verdict.Render).budget
        }
        assertEquals(
            ConsoleSpeech.Hold.GREETING_CAP,
            held(evaluate(queued = greeting, budget = budget, nowWallMs = t0 + 4 * minute)),
        )
    }

    @Test
    fun `the same greeting twice in a cycle is allowed`() {
        // A cycle is six hours and the morning line is the same line every
        // morning. Keying it on the id would silence the second greeting of
        // most days.
        val r = evaluate(queued = greeting) as ConsoleSpeech.Verdict.Render
        val again = evaluate(queued = greeting, budget = r.budget, nowWallMs = t0 + minute)
        assertTrue(again is ConsoleSpeech.Verdict.Render)
    }

    @Test
    fun `a greeting still obeys every suppression rule`() {
        // It is Bit commenting, so silence during a call, over a payment app,
        // under a gate, and behind anything that outranks it.
        val gates = listOf(
            ConsoleSpeech.Gate(callInProgress = true),
            ConsoleSpeech.Gate(sensitiveForeground = true),
            ConsoleSpeech.Gate(gateActive = true),
            ConsoleSpeech.Gate(outranked = true),
        )
        for (gate in gates) {
            val v = evaluate(queued = greeting, gate = gate)
            assertTrue("$gate", v is ConsoleSpeech.Verdict.Held)
        }
    }

    @Test
    fun `a suppressed greeting spends nothing either`() {
        val v = evaluate(queued = greeting, gate = ConsoleSpeech.Gate(gateActive = true))
        assertTrue(v is ConsoleSpeech.Verdict.Held)
        val later = evaluate(queued = greeting)
        assertEquals(
            listOf(t0),
            (later as ConsoleSpeech.Verdict.Render).budget.greetedAtWallMs,
        )
    }

    @Test
    fun `the greeting counter never grows without bound`() {
        var budget = ConsoleSpeech.Budget(cycleAnchorWallMs = anchor)
        for (i in 1..40) {
            val at = t0 + i * 3 * ConsoleSpeech.HOUR_MS
            val v = evaluate(queued = greeting, budget = budget, nowWallMs = at)
            if (v is ConsoleSpeech.Verdict.Render) budget = v.budget
            assertTrue(budget.greetedAtWallMs.size <= Greeting.MAX_PER_DAY)
        }
    }
}
