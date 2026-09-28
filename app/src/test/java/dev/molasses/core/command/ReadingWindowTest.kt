package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingWindowTest {

    private fun words(n: Int) = List(n) { "w" }.joinToString(" ")

    @Test
    fun `2000 plus 250 a word`() {
        assertEquals(4_000L, ReadingWindow.holdMs(words(8)))
        assertEquals(5_250L, ReadingWindow.holdMs(words(13)))
    }

    @Test
    fun `never under 3000`() {
        assertEquals(3_000L, ReadingWindow.holdMs(""))
        assertEquals(3_000L, ReadingWindow.holdMs(words(4)))
        assertEquals(3_000L, ReadingWindow.holdMs(words(3)))
    }

    @Test
    fun `never over 10000`() {
        assertEquals(10_000L, ReadingWindow.holdMs(words(32)))
        assertEquals(10_000L, ReadingWindow.holdMs(words(200)))
    }

    @Test
    fun `words are runs of non-space, however spaced`() {
        assertEquals(3, ReadingWindow.words("  REMINDER\nSET   FOR "))
        assertEquals(0, ReadingWindow.words("   "))
    }

    @Test
    fun `the reminder acknowledgements land inside the window`() {
        // "REMINDER SET FOR Sun 28 Sep 6:00 PM. EXACT." is nine words.
        assertEquals(4_250L, ReadingWindow.holdMs("REMINDER SET FOR Sun 28 Sep 6:00 PM. EXACT."))
    }
}
