package dev.molasses.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {

    private fun at(hour: Int, minute: Int = 0) = Greeting.bandFor(hour * 60 + minute)

    @Test
    fun `the three bands`() {
        assertEquals(Greeting.Band.MORNING, at(5, 0))
        assertEquals(Greeting.Band.MORNING, at(11, 59))
        assertEquals(Greeting.Band.AFTERNOON, at(12, 0))
        assertEquals(Greeting.Band.AFTERNOON, at(16, 59))
        assertEquals(Greeting.Band.LATE, at(22, 30))
        assertEquals(Greeting.Band.LATE, at(23, 59))
    }

    @Test
    fun `the evening and the small hours are silent`() {
        assertNull(at(17, 0))
        assertNull(at(20, 0))
        assertNull(at(22, 29))
        assertNull(at(0, 0))
        assertNull(at(4, 59))
    }

    @Test
    fun `five o'clock belongs to the silence, not to the afternoon`() {
        // The brief wrote the afternoon as 12:00 to 17:00 and the silence as
        // 17:00 to 22:29, which overlap on the hour. Resolved downward: a
        // boundary that belongs to the quieter side cannot produce a line
        // nobody wanted.
        assertEquals(Greeting.Band.AFTERNOON, at(16, 59))
        assertNull(at(17, 0))
    }

    @Test
    fun `every minute of the day resolves`() {
        for (m in 0 until Greeting.MINUTES_PER_DAY) {
            // Either a band or deliberate silence; never a throw.
            Greeting.bandFor(m)
        }
    }

    @Test
    fun `an out of range minute wraps rather than throwing`() {
        assertEquals(at(6), Greeting.bandFor(24 * 60 + 6 * 60))
        assertEquals(at(23), Greeting.bandFor(-60))
    }

    @Test
    fun `a silent window produces no line at all`() {
        // Which is what makes silence free: there is nothing to charge.
        assertNull(Greeting.lineFor(20 * 60))
        assertNull(Greeting.lineFor(2 * 60))
    }

    @Test
    fun `a greeting is a greeting, not an observation`() {
        val line = Greeting.lineFor(8 * 60)!!
        assertEquals(ConsoleLine.Category.GREETING, line.category)
        assertEquals(ConsoleIds.GREETING_MORNING, line.id)
    }

    @Test
    fun `every band has a declared id`() {
        for (band in Greeting.Band.entries) {
            assertTrue(band.name, band.id in ConsoleIds.ALL)
        }
    }

    @Test
    fun `three a day, and that is its own number`() {
        assertEquals(3, Greeting.MAX_PER_DAY)
        assertTrue(
            "a greeting must not spend an observation",
            Greeting.MAX_PER_DAY != ConsoleSpeech.MAX_PER_DAY,
        )
    }
}
