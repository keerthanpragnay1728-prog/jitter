package dev.molasses.core.remind

import dev.molasses.core.console.ConsoleSpeech
import dev.molasses.core.remind.ReminderBook.Added
import dev.molasses.core.remind.ReminderBook.Step
import dev.molasses.core.remind.ReminderBook.When
import dev.molasses.core.time.StampedInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderBookTest {

    private val min = 60_000L
    private val wall0 = 1_700_000_000_000L
    private fun at(wall: Long, elapsed: Long, boot: Int = 1) = StampedInstant(wall, elapsed, boot)
    private val now = at(wall0, 10 * min)
    private val noDates: (dev.molasses.core.util.DateMath.DateSpec, Int) -> Long? = { _, _ -> error("not dated") }

    @Test
    fun `a duration is from now and a time of day is the next one`() {
        assertEquals(at(wall0 + 45 * min, 55 * min), ReminderBook.dueAt(When.In(45 * min), now, nowMinuteOfDay = 9 * 60, wallOn = noDates))
        // 09:00 now, 18:00 asked: nine hours.
        assertEquals(wall0 + 9 * 60 * min, ReminderBook.dueAt(When.At(18 * 60), now, 9 * 60, noDates)!!.wallMs)
        // The current minute means tomorrow, never zero.
        assertEquals(wall0 + 24 * 60 * min, ReminderBook.dueAt(When.At(9 * 60), now, 9 * 60, noDates)!!.wallMs)
    }

    @Test
    fun `twenty fit and the twenty first is refused, nothing dropped`() {
        var list = emptyList<Reminder>()
        repeat(ReminderBook.MAX) { i ->
            list = (ReminderBook.add(list, i + 1L, "r$i", now) as Added.Ok).list
        }
        assertEquals(20, list.size)
        assertEquals(Added.Full, ReminderBook.add(list, 99, "one more", now))
        assertEquals(20, list.size)
    }

    @Test
    fun `reschedule after a boot arms what is ahead and fires what is due, marked late`() {
        val ahead = Reminder(1, "ahead", at(wall0 + 60 * min, 70 * min, boot = 1))
        val past = Reminder(2, "past", at(wall0 - min, 9 * min, boot = 1))
        val done = Reminder(3, "done", at(wall0 - 2 * min, 8 * min, boot = 1), fired = true)
        // Rebooted: boot 2, elapsed restarted.
        val afterBoot = at(wall0 + 5 * min, 30_000, boot = 2)
        val steps = ReminderBook.reschedule(listOf(ahead, past, done), afterBoot)
        assertEquals(listOf(Step.Schedule(ahead, wall0 + 60 * min), Step.FireLate(past)), steps)
    }

    @Test
    fun `on the same boot the monotonic clock decides, not a moved wall clock`() {
        val r = Reminder(1, "x", at(wall0 + 30 * min, 40 * min))
        // Wall clock pushed a day ahead, only five real minutes gone.
        val jumped = at(wall0 + 24 * 60 * min, 15 * min)
        assertFalse(ReminderBook.isDue(r, jumped))
        val step = ReminderBook.reschedule(listOf(r), jumped).single() as Step.Schedule
        assertEquals("twenty five real minutes from now", jumped.wallMs + 25 * min, step.atWallMs)
    }

    @Test
    fun `fired stays until dismissed, and several show oldest first`() {
        var list = listOf(
            Reminder(1, "b", at(wall0 + 2 * min, 12 * min)),
            Reminder(2, "a", at(wall0 + min, 11 * min)),
        )
        list = ReminderBook.fired(list, 1, late = false)
        list = ReminderBook.fired(list, 2, late = true)
        assertEquals(listOf("a", "b"), ReminderBook.toShow(list).map { it.text })
        assertTrue(ReminderBook.toShow(list).first().late)
        list = ReminderBook.dismissed(list, 2)
        assertEquals(listOf("b"), ReminderBook.toShow(list).map { it.text })
        list = ReminderBook.dismissed(list, 1)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `firing twice changes nothing, and an unknown id is ignored`() {
        val list = listOf(Reminder(1, "x", now))
        val once = ReminderBook.fired(list, 1, late = false)
        assertEquals(once, ReminderBook.fired(once, 1, late = true))
        assertEquals(list, ReminderBook.fired(list, 42, late = false))
    }

    @Test
    fun `reminders are not counted against the speech budget`() {
        // Ten reminders fire in one minute. The speech caps would hold all
        // but three of anything Bit volunteered in an hour; every one of
        // these is still shown, in order, because the list is its own queue
        // and never passes through ConsoleSpeech.
        var list = (1L..10L).map { Reminder(it, "r$it", at(wall0 + it, 10 * min + it)) }
        list.forEach { r -> list = ReminderBook.fired(list, r.id, late = false) }
        assertEquals(10, ReminderBook.toShow(list).size)
        assertTrue(ConsoleSpeech.MAX_PER_HOUR < 10)
    }

    @Test
    fun `ids never repeat`() {
        val list = listOf(Reminder(5, "x", now))
        assertEquals(6, ReminderBook.nextId(list, lastIssued = 3))
        assertEquals(10, ReminderBook.nextId(emptyList(), lastIssued = 9))
    }

    @Test
    fun `a bare rem lists unfired reminders only, soonest first, at most five`() {
        val list = (1L..8L).map { id -> Reminder(id, "r$id", at(wall0 + (10 - id) * min, 0)) }
            .map { if (it.id == 7L) it.copy(fired = true) else it }
        val pending = ReminderBook.pending(list)
        assertEquals(ReminderBook.LIST_MAX, pending.size)
        assertEquals(listOf(8L, 6L, 5L, 4L, 3L), pending.map { it.id })
        assertFalse("never a fired one", pending.any { it.fired })
    }

    @Test
    fun `ties on due time keep id order, and nothing pending is an empty list`() {
        val same = at(wall0 + min, 0)
        assertEquals(listOf(1L, 2L), ReminderBook.pending(listOf(Reminder(2, "b", same), Reminder(1, "a", same))).map { it.id })
        assertEquals(emptyList<Reminder>(), ReminderBook.pending(listOf(Reminder(1, "a", same, fired = true))))
        assertEquals(emptyList<Reminder>(), ReminderBook.pending(emptyList()))
    }

    // ------------------------------------------------------------ dated

    private val oct3 = dev.molasses.core.util.DateMath.DateSpec.DayMonth(10, 3)

    @Test
    fun `a dated reminder is due at the wall time the host resolves, on both clocks`() {
        val due = ReminderBook.dueAt(When.On(oct3, 9 * 60), now, 9 * 60) { _, _ -> wall0 + 60 * min }
        assertEquals(at(wall0 + 60 * min, 70 * min), due)
    }

    @Test
    fun `a dated reminder not in the future is refused, never rolled forward`() {
        assertEquals(null, ReminderBook.dueAt(When.On(oct3, 9 * 60), now, 9 * 60) { _, _ -> wall0 })
        assertEquals(null, ReminderBook.dueAt(When.On(oct3, 9 * 60), now, 9 * 60) { _, _ -> wall0 - min })
    }

    @Test
    fun `a date the host cannot resolve is refused`() {
        assertEquals(null, ReminderBook.dueAt(When.On(oct3, 9 * 60), now, 9 * 60) { _, _ -> null })
    }

    @Test
    fun `the host is asked for exactly the date and minute that were typed`() {
        var asked: Pair<Any, Int>? = null
        ReminderBook.dueAt(When.On(oct3, 18 * 60 + 30), now, 9 * 60) { d, m -> asked = d to m; wall0 + min }
        assertEquals(oct3 to 18 * 60 + 30, asked)
    }
}
