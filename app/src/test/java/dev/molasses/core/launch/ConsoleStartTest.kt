package dev.molasses.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleStartTest {

    private val refused = mutableListOf<RuntimeException>()

    private fun attempt(start: () -> Unit) = ConsoleStart.attempt(
        start = start,
        onRefused = { refused += it },
    )

    @Test
    fun `a start that returns is true and says nothing`() {
        var started = 0
        assertTrue(attempt { started++ })
        assertEquals(1, started)
        assertTrue(refused.isEmpty())
    }

    @Test
    fun `any RuntimeException is caught, reported once, and read as false`() {
        // A stand-in for ActivityNotFoundException, which does not exist off
        // Android, the real SecurityException, and two kinds a start is not
        // documented to throw at all.
        class NotFound : RuntimeException("no activity")
        for (thrown in listOf(NotFound(), SecurityException("denied"), IllegalStateException("odd"), IllegalArgumentException("odd"))) {
            refused.clear()
            assertFalse(attempt { throw thrown })
            assertEquals(1, refused.size)
            assertSame(thrown, refused.single())
        }
    }

    @Test
    fun `an Error is thrown on, not answered as a refusal`() {
        val error = OutOfMemoryError("not a refusal")
        val caught = try {
            attempt { throw error }
            null
        } catch (e: OutOfMemoryError) {
            e
        }
        assertSame(error, caught)
        assertTrue(refused.isEmpty())
    }
}
