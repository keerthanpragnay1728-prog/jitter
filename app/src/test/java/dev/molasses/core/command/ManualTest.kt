package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTest {

    private val registry = CommandRegistry(
        CommandRegistry.Keys(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
        ),
    )

    private fun rows(availability: (CommandSpec) -> Availability) =
        Manual.rows(registry, availability)

    @Test
    fun `every registered command appears`() {
        // The point of generating it. A verb that exists and is undocumented
        // is not reachable by anyone who did not read the source.
        val listed = rows { Availability.Available }.map { it.verb }
        assertEquals(registry.specs.map { it.verb }, listed)
    }

    @Test
    fun `an unavailable command is listed, dimmed, with its reason`() {
        val rows = rows {
            if (it.surface == Surface.SUBSYSTEM) Availability.Unavailable(42)
            else Availability.Available
        }
        val block = rows.single { it.verb == "block" }
        assertFalse("block should be dimmed", block.available)
        assertEquals(42, block.reasonKey)

        val status = rows.single { it.verb == "status" }
        assertTrue(status.available)
        assertNull("an available row carries no reason", status.reasonKey)
    }

    @Test
    fun `order is registry order and does not change with availability`() {
        // Sorting available first would reorder the page every time the
        // service died, which is the worst moment to move things.
        val healthy = rows { Availability.Available }.map { it.verb }
        val broken = rows {
            if (it.surface == Surface.SUBSYSTEM) Availability.Unavailable(1)
            else Availability.Available
        }.map { it.verb }
        assertEquals(healthy, broken)
    }

    @Test
    fun `rows carry the registry's own resource ids`() {
        for (row in rows { Availability.Available }) {
            val spec = registry.specForVerb(row.verb)!!
            assertEquals(spec.usageKey, row.usageKey)
            assertEquals(spec.descriptionKey, row.descriptionKey)
        }
    }

    // --------------------------------------------------------- suggestions

    @Test
    fun `only available commands are suggested`() {
        // A placeholder is an invitation. Inviting someone to type a command
        // that then reports it cannot run teaches them to stop reading.
        val rows = rows {
            if (it.verb == "status" || it.verb == "help") Availability.Available
            else Availability.Unavailable(7)
        }
        assertEquals(listOf("status", "help"), Manual.suggestable(rows).map { it.verb })
    }

    @Test
    fun `the suggestion index wraps in both directions`() {
        val rows = Manual.suggestable(rows { Availability.Available })
        val n = rows.size
        assertEquals(rows[0], Manual.at(rows, 0))
        assertEquals(rows[0], Manual.at(rows, n))
        assertEquals(rows[1], Manual.at(rows, n + 1))
        // A counter that lives long enough to overflow still produces a row.
        assertNotNull(Manual.at(rows, -1))
        assertNotNull(Manual.at(rows, Int.MIN_VALUE))
    }

    @Test
    fun `nothing suggestable yields no suggestion rather than throwing`() {
        assertNull(Manual.at(emptyList(), 3))
    }

    @Test
    fun `help is always suggestable, because it is the way in`() {
        // help sits on STATE, which nothing can make unavailable. If that
        // stops being true, discovery has no entry point on a broken device.
        val help = registry.specForVerb("help")!!
        assertEquals(Surface.STATE, help.surface)
    }
}
