package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleExitTest {

    private var fallbacks = 0
    private val refused = mutableListOf<RuntimeException>()

    private fun open(start: () -> Unit) = ConsoleExit.open(
        start = start,
        fallback = { fallbacks++ },
        onRefused = { refused += it },
    )

    @Test
    fun `a start that returns is the console, with no fallback and no log`() {
        var started = 0
        assertEquals(ConsoleExit.Route.CONSOLE, open { started++ })
        assertEquals(1, started)
        assertEquals(0, fallbacks)
        assertTrue(refused.isEmpty())
    }

    @Test
    fun `a start that throws is caught, logged, and falls back to home once`() {
        for (thrown in listOf(SecurityException("denied"), IllegalStateException("no activity"))) {
            fallbacks = 0
            refused.clear()
            assertEquals(ConsoleExit.Route.HOME_FALLBACK, open { throw thrown })
            assertEquals(1, fallbacks)
            assertEquals(1, refused.size)
            assertSame("the log gets the exception itself", thrown, refused.single())
        }
    }

    @Test
    fun `the refusal is logged before the fallback is sent`() {
        val order = mutableListOf<String>()
        ConsoleExit.open(
            start = { throw SecurityException("denied") },
            fallback = { order += "fallback" },
            onRefused = { order += "log" },
        )
        assertEquals(listOf("log", "fallback"), order)
    }

    @Test
    fun `an Error is not a refusal and is not hidden behind the fallback`() {
        val error = OutOfMemoryError("not a refusal")
        val caught = try {
            open { throw error }
            null
        } catch (e: OutOfMemoryError) {
            e
        }
        assertSame(error, caught)
        assertEquals(0, fallbacks)
        assertTrue(refused.isEmpty())
    }
}
