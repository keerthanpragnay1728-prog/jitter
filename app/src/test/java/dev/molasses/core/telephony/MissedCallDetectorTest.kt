package dev.molasses.core.telephony

import dev.molasses.core.telephony.MissedCallDetector.Event
import dev.molasses.core.telephony.MissedCallDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallDetectorTest {

    private fun detector() = MissedCallDetector()

    /** Feed a stream, return every event it produced. */
    private fun run(
        vararg states: Pair<State, String?>,
        into: MissedCallDetector = MissedCallDetector(),
    ): List<Event> = states.map { (s, id) -> into.onState(s, id) }

    @Test
    fun `an answered call is not missed`() {
        val events = run(
            State.IDLE to null,
            State.RINGING to "Mum",
            State.OFFHOOK to null,
            State.IDLE to null,
        )
        assertEquals(listOf(Event.None, Event.None, Event.Connected, Event.Ended), events)
    }

    @Test
    fun `a call that rings out is missed and carries the caller`() {
        val events = run(
            State.IDLE to null,
            State.RINGING to "Mum",
            State.IDLE to null,
        )
        assertEquals(Event.Missed("Mum"), events.last())
    }

    @Test
    fun `a rejected call reports as missed`() {
        // Rejecting produces exactly the same RINGING to IDLE transition as
        // letting it ring out. They are indistinguishable from call state
        // alone, and "missed" is the right answer for a launcher notice.
        val events = run(
            State.IDLE to null,
            State.RINGING to "Unknown Number",
            State.IDLE to null,
        )
        assertEquals(Event.Missed("Unknown Number"), events.last())
    }

    @Test
    fun `an outgoing call is never missed`() {
        val events = run(
            State.IDLE to null,
            State.OFFHOOK to null,
            State.IDLE to null,
        )
        assertEquals(listOf(Event.None, Event.Connected, Event.Ended), events)
    }

    @Test
    fun `a missing caller id degrades to a nameless notice`() {
        // Never suppressed. A missed call the user is not told about is worse
        // than one attributed to nobody.
        val events = run(
            State.IDLE to null,
            State.RINGING to null,
            State.IDLE to null,
        )
        assertEquals(Event.Missed(null), events.last())
    }

    @Test
    fun `call waiting does not turn a connected call into a miss`() {
        // RINGING arrives while already OFFHOOK. The original call is still
        // connected, so the eventual IDLE is an end, not a miss.
        val events = run(
            State.IDLE to null,
            State.RINGING to "Mum",
            State.OFFHOOK to null,
            State.RINGING to "Dad",
            State.OFFHOOK to null,
            State.IDLE to null,
        )
        assertEquals(Event.Ended, events.last())
    }

    @Test
    fun `rapid repeat ringing produces one miss per ring`() {
        val d = detector()
        d.onState(State.IDLE)
        assertEquals(Event.None, d.onState(State.RINGING, "Mum"))
        assertEquals(Event.Missed("Mum"), d.onState(State.IDLE))
        assertEquals(Event.None, d.onState(State.RINGING, "Mum"))
        assertEquals(Event.Missed("Mum"), d.onState(State.IDLE))
        assertEquals(Event.None, d.onState(State.RINGING, "Dad"))
        assertEquals(Event.Missed("Dad"), d.onState(State.IDLE))
    }

    @Test
    fun `a second ring does not inherit the first caller`() {
        val d = detector()
        d.onState(State.IDLE)
        d.onState(State.RINGING, "Mum")
        d.onState(State.IDLE)
        d.onState(State.RINGING, null)
        assertEquals(Event.Missed(null), d.onState(State.IDLE))
    }

    @Test
    fun `a stream that starts mid-call reports no miss`() {
        // The process starts while a call is already connected. The first
        // state is a baseline, not a transition, and the IDLE that follows
        // must not read as a missed call.
        val events = run(
            State.OFFHOOK to null,
            State.IDLE to null,
        )
        assertEquals(listOf(Event.None, Event.Ended), events)
    }

    @Test
    fun `a stream that starts mid-ring does report a miss`() {
        // Starting at RINGING, the ring genuinely was not connected while we
        // were watching. Reporting it is the safe direction: the user did
        // miss it.
        val events = run(
            State.RINGING to "Mum",
            State.IDLE to null,
        )
        assertEquals(Event.Missed("Mum"), events.last())
    }

    @Test
    fun `repeated identical states produce nothing`() {
        val d = detector()
        d.onState(State.IDLE)
        assertEquals(Event.None, d.onState(State.IDLE))
        assertEquals(Event.None, d.onState(State.IDLE))
        assertEquals(Event.None, d.onState(State.RINGING, "Mum"))
        assertEquals(Event.None, d.onState(State.RINGING, "Mum"))
        assertEquals(Event.Missed("Mum"), d.onState(State.IDLE))
    }

    @Test
    fun `baseline and state are exposed`() {
        val d = detector()
        assertFalse(d.hasBaseline)
        d.onState(State.IDLE)
        assertTrue(d.hasBaseline)
        assertEquals(State.IDLE, d.state)
        d.reset()
        assertFalse(d.hasBaseline)
    }

    @Test
    fun `reset clears a pending ring rather than reporting it later`() {
        val d = detector()
        d.onState(State.IDLE)
        d.onState(State.RINGING, "Mum")
        d.reset()
        // After a reset the next state is a fresh baseline.
        assertEquals(Event.None, d.onState(State.IDLE))
    }
}
