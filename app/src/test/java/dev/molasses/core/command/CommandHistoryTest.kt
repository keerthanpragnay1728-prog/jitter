package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandHistoryTest {

    @Test
    fun `the newest line is first`() {
        var h = emptyList<String>()
        h = CommandHistory.record(h, "status", confirmation = false)
        h = CommandHistory.record(h, "wifi", confirmation = false)
        assertEquals(listOf("wifi", "status"), h)
    }

    @Test
    fun `it caps at twenty`() {
        var h = emptyList<String>()
        repeat(30) { h = CommandHistory.record(h, "timer ${it}m", confirmation = false) }
        assertEquals(CommandHistory.MAX, h.size)
        assertEquals("timer 29m", h.first())
        assertEquals("timer 10m", h.last())
    }

    @Test
    fun `a repeat moves to the top rather than duplicating`() {
        // Twenty slots holding four distinct commands would be worse than
        // useless in a list the user taps.
        var h = emptyList<String>()
        h = CommandHistory.record(h, "status", confirmation = false)
        h = CommandHistory.record(h, "wifi", confirmation = false)
        h = CommandHistory.record(h, "status", confirmation = false)
        assertEquals(listOf("status", "wifi"), h)
    }

    @Test
    fun `whitespace is trimmed so a stray space is not a second entry`() {
        var h = CommandHistory.record(emptyList(), "status", confirmation = false)
        h = CommandHistory.record(h, "  status  ", confirmation = false)
        assertEquals(listOf("status"), h)
    }

    @Test
    fun `a blank line is not recorded`() {
        assertEquals(emptyList<String>(), CommandHistory.record(emptyList(), "   ", false))
        assertEquals(emptyList<String>(), CommandHistory.record(emptyList(), "", false))
    }

    // ------------------------------------------------- the confirmation rule

    @Test
    fun `a confirmation Enter is never recorded`() {
        // The whole reason this parameter exists. Recording it would put a
        // fully formed 30 day lock into a list the user taps through.
        val before = listOf("status")
        val after = CommandHistory.record(before, "block instagram 30d", confirmation = true)
        assertEquals(before, after)
    }

    @Test
    fun `the line the user typed is recorded and the echo is not`() {
        // The real sequence: type a long lock, get the echo, confirm it.
        var h = emptyList<String>()
        h = CommandHistory.record(h, "block ig 30d", confirmation = false)
        h = CommandHistory.record(h, "block ig 30d", confirmation = true)
        assertEquals(listOf("block ig 30d"), h)
    }

    @Test
    fun `no history entry parses into an armed lock without a second Enter`() {
        // The invariant behind the rule, asserted rather than described: a
        // line that comes back out of history is still subject to the
        // confirmation gate, because the gate keys on the pending state and
        // history never sets it.
        var h = emptyList<String>()
        h = CommandHistory.record(h, "block ig 30d", confirmation = false)
        h = CommandHistory.record(h, CommandRender.render(Command.Block("ig", 30L * 24 * 3600_000)), confirmation = true)
        assertFalse("the canonical echo must not be in history", h.contains("block ig 30d "))
        assertEquals(1, h.size)
    }

    @Test
    fun `recent never returns more than the cap`() {
        val overfull = (1..40).map { "timer ${it}m" }
        assertEquals(CommandHistory.MAX, CommandHistory.recent(overfull).size)
    }
}
