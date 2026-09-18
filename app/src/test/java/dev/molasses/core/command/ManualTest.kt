package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTest {

    private val registry = CommandRegistry(
        CommandRegistry.Keys(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
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

    // ------------------------------------------------------- the way in

    @Test
    fun `help is always available, because it is the way in`() {
        // help sits on STATE, which nothing can make unavailable. It matters
        // more than it did: the prompt placeholder is now a single static
        // line telling the user to type "?", so if help could go unavailable
        // the only advertised route into the app would dead-end.
        val help = registry.specForVerb("help")!!
        assertEquals(Surface.STATE, help.surface)
    }

    @Test
    fun `every row is reachable from the one command the placeholder names`() {
        // The placeholder points at "?" and nothing else, so this page is the
        // whole of discovery. A verb that is in the registry is in here,
        // available or not, which is what makes that a complete answer rather
        // than a partial one.
        val rows = rows { Availability.Available }
        assertEquals(registry.specs.map { it.verb }, rows.map { it.verb })
    }
}
