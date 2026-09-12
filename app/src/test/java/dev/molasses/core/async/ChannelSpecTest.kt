package dev.molasses.core.async

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SS5: construct every channel the app creates, so a bad capacity/overflow
 * pair fails in CI rather than on a device.
 *
 * These are not reconstructions of the service's arguments -- they are the
 * same [ChannelSpecs] factories the service calls, which is the only version
 * of this test worth having.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSpecTest {

    @Test
    fun `every channel the app constructs is constructible`() {
        // The whole point: this would have thrown before the fix.
        ChannelSpecs.engineCheckpoints<String>().close()
        ChannelSpecs.ledgerRows<String>().close()
    }

    @Test
    fun `the illegal pairing that shipped once still throws`() {
        // Pinned so nobody reintroduces it believing it is merely redundant.
        val e = assertThrows(IllegalArgumentException::class.java) {
            Channel<String>(
                capacity = Channel.CONFLATED,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
        assertTrue(
            "expected the CONFLATED/overflow complaint, got: ${e.message}",
            e.message!!.contains("CONFLATED"),
        )
    }

    @Test
    fun `the legal spellings are legal`() {
        // Both of these are fine and are what ChannelSpecs uses.
        Channel<String>(Channel.CONFLATED).close()
        Channel<String>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).close()
    }

    @Test
    fun `checkpoints conflate and never suspend the producer`() = runTest {
        val c = ChannelSpecs.engineCheckpoints<Int>()
        // trySend must always succeed: the producer is the accessibility
        // callback thread.
        repeat(1_000) { assertTrue("send $it failed", c.trySend(it).isSuccess) }
        assertEquals("only the newest snapshot should survive", 999, c.receive())
        c.close()
    }

    @Test
    fun `ledger rows buffer rather than conflate`() = runTest {
        val c = ChannelSpecs.ledgerRows<Int>(capacity = 4)
        repeat(4) { assertTrue(c.trySend(it).isSuccess) }
        // Distinct facts, kept in order -- unlike checkpoints.
        assertEquals(0, c.receive())
        assertEquals(1, c.receive())
        c.close()
    }

    @Test
    fun `ledger rows shed the oldest under pressure instead of blocking`() = runTest {
        val c = ChannelSpecs.ledgerRows<Int>(capacity = 4)
        repeat(100) { assertTrue("send $it blocked or failed", c.trySend(it).isSuccess) }
        // Oldest dropped, newest kept: a lost ledger row degrades the debug
        // view, whereas blocking the caller delays every subsequent
        // accessibility event.
        val first = c.receive()
        assertTrue("expected recent rows, got $first", first >= 96)
        c.close()
    }

    @Test
    fun `the ledger capacity is what the service uses`() {
        assertEquals(256, ChannelSpecs.LEDGER_CAPACITY)
    }
}
