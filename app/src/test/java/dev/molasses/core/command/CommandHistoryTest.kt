package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandHistoryTest {

    @Test
    fun `the newest line is first`() {
        var h = emptyList<String>()
        h = CommandHistory.record(h, "status")
        h = CommandHistory.record(h, "wifi")
        assertEquals(listOf("wifi", "status"), h)
    }

    @Test
    fun `it caps at twenty`() {
        var h = emptyList<String>()
        repeat(30) { h = CommandHistory.record(h, "timer ${it}m") }
        assertEquals(CommandHistory.MAX, h.size)
        assertEquals("timer 29m", h.first())
        assertEquals("timer 10m", h.last())
    }

    @Test
    fun `a repeat moves to the top rather than duplicating`() {
        // Twenty slots holding four distinct commands would be worse than
        // useless in a list the user taps.
        var h = emptyList<String>()
        h = CommandHistory.record(h, "status")
        h = CommandHistory.record(h, "wifi")
        h = CommandHistory.record(h, "status")
        assertEquals(listOf("status", "wifi"), h)
    }

    @Test
    fun `whitespace is trimmed so a stray space is not a second entry`() {
        var h = CommandHistory.record(emptyList(), "status")
        h = CommandHistory.record(h, "  status  ")
        assertEquals(listOf("status"), h)
    }

    @Test
    fun `a blank line is not recorded`() {
        assertEquals(emptyList<String>(), CommandHistory.record(emptyList(), "   "))
        assertEquals(emptyList<String>(), CommandHistory.record(emptyList(), ""))
    }

    @Test
    fun `recent never returns more than the cap`() {
        val overfull = (1..40).map { "timer ${it}m" }
        assertEquals(CommandHistory.MAX, CommandHistory.recent(overfull).size)
    }
}
