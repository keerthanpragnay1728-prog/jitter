package dev.molasses.core.speech

import dev.molasses.core.speech.SpeechBufferQueue.Context
import dev.molasses.core.speech.SpeechBufferQueue.Decision
import dev.molasses.core.speech.SpeechBufferQueue.Priority
import dev.molasses.core.speech.SpeechBufferQueue.Reason
import dev.molasses.core.speech.SpeechBufferQueue.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBufferQueueTest {

    private val q = SpeechBufferQueue()
    private val min = 60_000L
    private val hour = 60 * min

    private fun ambient(id: String) = Utterance(id, Priority.AMBIENT, "body $id")
    private fun call(id: String) = Utterance(id, Priority.TELEPHONY, "missed call")

    private fun spoken(d: Decision): Utterance =
        (d as? Decision.Speak)?.utterance ?: throw AssertionError("expected Speak, got $d")

    private fun dropped(d: Decision): Reason =
        (d as? Decision.Drop)?.reason ?: throw AssertionError("expected Drop, got $d")

    @Test
    fun `nothing pending`() {
        assertEquals(Reason.NOTHING_PENDING, dropped(q.next(emptyList(), Context(nowMs = 0))))
    }

    @Test
    fun `an ambient line is spoken when the budget allows`() {
        val d = q.next(listOf(ambient("a")), Context(nowMs = 0))
        assertEquals("a", spoken(d).id)
    }

    // ------------------------------------------------------------------ priority

    @Test
    fun `telephony outranks ambient regardless of order`() {
        val ctx = Context(nowMs = 0)
        assertEquals("c", spoken(q.next(listOf(ambient("a"), call("c")), ctx)).id)
        assertEquals("c", spoken(q.next(listOf(call("c"), ambient("a")), ctx)).id)
    }

    @Test
    fun `telephony ignores the hourly cap`() {
        // A user who misses a call because Bit spent its budget on commentary
        // would be right to uninstall.
        val ctx = Context(nowMs = 3 * hour, recentMs = listOf(3 * hour - 1, 3 * hour - 2, 3 * hour - 3))
        assertEquals(Reason.HOURLY_CAP, dropped(q.next(listOf(ambient("a")), ctx)))
        assertEquals("c", spoken(q.next(listOf(call("c")), ctx)).id)
    }

    @Test
    fun `telephony ignores the daily cap`() {
        // Spread across the day with none in the last hour, so the daily cap
        // is the one that fires rather than the hourly one.
        val now = 30 * hour
        val recent = (1..8).map { now - (it + 1) * hour }
        val ctx = Context(nowMs = now, recentMs = recent)
        assertEquals(Reason.DAILY_CAP, dropped(q.next(listOf(ambient("a")), ctx)))
        assertEquals("c", spoken(q.next(listOf(call("c")), ctx)).id)
    }

    // ---------------------------------------------------------------- suppression

    @Test
    fun `suppression silences everything including telephony`() {
        val ctx = Context(nowMs = 0, suppressed = true)
        assertEquals(Reason.SUPPRESSED, dropped(q.next(listOf(ambient("a")), ctx)))
        assertEquals(Reason.SUPPRESSED, dropped(q.next(listOf(call("c")), ctx)))
    }

    // ---------------------------------------------------------------------- caps

    @Test
    fun `three ambient lines an hour, then the cap bites`() {
        val now = 10 * hour
        val two = Context(nowMs = now, recentMs = listOf(now - 10 * min, now - 20 * min))
        assertEquals("a", spoken(q.next(listOf(ambient("a")), two)).id)

        val three = Context(
            nowMs = now,
            recentMs = listOf(now - 10 * min, now - 20 * min, now - 30 * min),
        )
        assertEquals(Reason.HOURLY_CAP, dropped(q.next(listOf(ambient("a")), three)))
    }

    @Test
    fun `the hourly window slides`() {
        val now = 10 * hour
        // Three lines, all just over an hour old.
        val old = listOf(now - hour - min, now - hour - 2 * min, now - hour - 3 * min)
        assertEquals("a", spoken(q.next(listOf(ambient("a")), Context(now, old))).id)
    }

    @Test
    fun `eight ambient lines a day, then the cap bites`() {
        val now = 20 * hour
        // Eight in the last day, none in the last hour, so only the daily cap
        // can be the one that fires.
        val recent = (1..8).map { now - (it + 1) * hour }
        assertEquals(Reason.DAILY_CAP, dropped(q.next(listOf(ambient("a")), Context(now, recent))))
    }

    @Test
    fun `the daily window slides`() {
        val now = 48 * hour
        val old = (1..8).map { now - 24 * hour - it * min }
        assertEquals("a", spoken(q.next(listOf(ambient("a")), Context(now, old))).id)
    }

    @Test
    fun `a custom cap is honoured`() {
        val strict = SpeechBufferQueue(maxPerHour = 1, maxPerDay = 2)
        val now = 10 * hour
        assertEquals(
            Reason.HOURLY_CAP,
            dropped(strict.next(listOf(ambient("a")), Context(now, listOf(now - min)))),
        )
    }

    // ------------------------------------------------------------- no repetition

    @Test
    fun `a line already said this cycle is not repeated`() {
        val ctx = Context(nowMs = 0, usedLineIds = setOf("a"))
        assertEquals(Reason.ALREADY_SAID, dropped(q.next(listOf(ambient("a")), ctx)))
    }

    @Test
    fun `a fresh line is chosen over a used one`() {
        val ctx = Context(nowMs = 0, usedLineIds = setOf("a"))
        assertEquals("b", spoken(q.next(listOf(ambient("a"), ambient("b")), ctx)).id)
    }

    @Test
    fun `the no-repeat rule also binds telephony`() {
        val ctx = Context(nowMs = 0, usedLineIds = setOf("c"))
        assertEquals(Reason.ALREADY_SAID, dropped(q.next(listOf(call("c")), ctx)))
    }

    // ------------------------------------------------------------------ recording

    @Test
    fun `speaking an ambient line charges the budget`() {
        val d = q.next(listOf(ambient("a")), Context(nowMs = 5 * hour)) as Decision.Speak
        assertEquals(listOf(5 * hour), q.record(d, emptyList()))
    }

    @Test
    fun `speaking a telephony line does not charge the budget`() {
        // Otherwise a busy afternoon of calls silences Bit for the day.
        val d = q.next(listOf(call("c")), Context(nowMs = 5 * hour)) as Decision.Speak
        assertEquals(emptyList<Long>(), q.record(d, emptyList()))
    }

    @Test
    fun `recording prunes entries older than a day`() {
        val now = 48 * hour
        val d = q.next(listOf(ambient("a")), Context(nowMs = now)) as Decision.Speak
        val kept = q.record(d, listOf(now - 25 * hour, now - 2 * hour))
        assertEquals(listOf(now - 2 * hour, now), kept)
    }

    @Test
    fun `the recorded list never exceeds the daily cap in length`() {
        var recent = emptyList<Long>()
        var now = 0L
        // Speak as often as the cap allows across two days.
        repeat(40) {
            now += 25 * min
            val d = q.next(listOf(ambient("line$it")), Context(nowMs = now, recentMs = recent))
            if (d is Decision.Speak) recent = q.record(d, recent)
        }
        assertTrue("was ${recent.size}", recent.size <= SpeechBufferQueue.MAX_PER_DAY)
    }

    @Test
    fun `the documented caps are three an hour and eight a day`() {
        assertEquals(3, SpeechBufferQueue.MAX_PER_HOUR)
        assertEquals(8, SpeechBufferQueue.MAX_PER_DAY)
    }
}
