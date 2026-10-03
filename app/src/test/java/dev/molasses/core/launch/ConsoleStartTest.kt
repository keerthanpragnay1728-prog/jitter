package dev.molasses.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleStartTest {

    /** Stand-ins for the two platform refusals, which do not exist off Android. */
    private class NotFound : RuntimeException("no activity")
    private class Denied : SecurityException("denied")

    private val refused = mutableListOf<RuntimeException>()

    private fun attempt(start: () -> Unit) = ConsoleStart.attempt(
        start = start,
        isRefusal = { it is NotFound || it is SecurityException },
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
    fun `both refusals are caught, handed on once, and read as false`() {
        for (thrown in listOf(NotFound(), Denied())) {
            refused.clear()
            assertFalse(attempt { throw thrown })
            assertEquals(1, refused.size)
            assertSame(thrown, refused.single())
        }
    }

    @Test
    fun `anything else is thrown on, not answered as a refusal`() {
        val other = IllegalStateException("a bug, not a refusal")
        val caught = try {
            attempt { throw other }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertSame(other, caught)
        assertTrue(refused.isEmpty())
    }
}
