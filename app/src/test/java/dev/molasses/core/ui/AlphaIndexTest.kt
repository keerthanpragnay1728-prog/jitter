package dev.molasses.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaIndexTest {

    private val labels = listOf("Calculator", "Instagram", "Maps", "X", "YouTube")

    @Test
    fun `a letter is the first character, uppercased`() {
        assertEquals('I', AlphaIndex.bucketOf("Instagram"))
        assertEquals('I', AlphaIndex.bucketOf("instagram"))
    }

    @Test
    fun `anything that is not A to Z goes to one bucket`() {
        // Digits, punctuation, emoji and every non-Latin script at once. The
        // limitation is real and documented: in a non-Latin locale the rail
        // stops being useful, which is why it sits beside the search box
        // rather than replacing it.
        for (label in listOf("1Password", "-hyphen", "हि", "日本", "")) {
            assertEquals(label, AlphaIndex.OTHER, AlphaIndex.bucketOf(label))
        }
    }

    @Test
    fun `leading whitespace is skipped rather than bucketed`() {
        // A label that begins with a space is a packaging accident, and the
        // user still looks for it under its first real letter.
        assertEquals('M', AlphaIndex.bucketOf("  Maps"))
    }

    @Test
    fun `the rail shows only the letters that are there`() {
        // Twenty six targets of which a dozen do nothing teaches the user
        // that the rail does not work.
        assertEquals(listOf('C', 'I', 'M', 'X', 'Y'), AlphaIndex.lettersOf(labels))
    }

    @Test
    fun `letters keep first-appearance order rather than being sorted`() {
        // CFG's list is tracked-first, so it is not alphabetical overall. A
        // rail claiming otherwise scrolls to the wrong place.
        assertEquals(
            listOf('Y', 'C', 'I'),
            AlphaIndex.lettersOf(listOf("YouTube", "Calculator", "Instagram", "Ibis")),
        )
    }

    @Test
    fun `a letter maps to the first row under it`() {
        assertEquals(0, AlphaIndex.firstIndexOf(labels, 'C'))
        assertEquals(4, AlphaIndex.firstIndexOf(labels, 'Y'))
    }

    @Test
    fun `a letter that is not present yields null rather than zero`() {
        // Zero is a perfectly good index and would scroll to the top, which
        // reads as a broken rail rather than as an empty bucket.
        assertNull(AlphaIndex.firstIndexOf(labels, 'Q'))
    }

    @Test
    fun `a drag off the rail stops scrolling rather than pinning to an end`() {
        val letters = AlphaIndex.lettersOf(labels)
        assertNull(AlphaIndex.letterAt(-0.2f, letters))
        assertNull(AlphaIndex.letterAt(1.4f, letters))
        assertNull(AlphaIndex.letterAt(0.5f, emptyList()))
    }

    @Test
    fun `the bottom of the rail is reachable`() {
        // The off-by-one that makes the last entry unhittable: a fraction of
        // exactly 1.0 is the last letter, not one past the end.
        val letters = AlphaIndex.lettersOf(labels)
        assertEquals('Y', AlphaIndex.letterAt(1.0f, letters))
        assertEquals('C', AlphaIndex.letterAt(0.0f, letters))
    }

    @Test
    fun `every fraction on the rail lands on a letter that exists`() {
        val letters = AlphaIndex.lettersOf(labels)
        var f = 0f
        while (f <= 1f) {
            val hit = AlphaIndex.letterAt(f, letters)
            assertTrue("$f gave $hit", hit != null && hit in letters)
            f += 0.01f
        }
    }

    @Test
    fun `every letter on the rail is reachable from some fraction`() {
        // The other direction, and the one that fails quietly: a rail that
        // renders a letter no drag can reach is a target that does nothing.
        val letters = AlphaIndex.lettersOf(labels)
        val reached = mutableSetOf<Char>()
        var f = 0f
        while (f <= 1f) {
            AlphaIndex.letterAt(f, letters)?.let { reached += it }
            f += 0.005f
        }
        assertEquals(letters.toSet(), reached)
    }

    @Test
    fun `every letter the rail shows has a row to scroll to`() {
        for (letter in AlphaIndex.lettersOf(labels)) {
            assertTrue(letter.toString(), AlphaIndex.firstIndexOf(labels, letter) != null)
        }
    }
}
