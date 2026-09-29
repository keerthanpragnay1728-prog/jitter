package dev.molasses.core.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device bug, reduced to its mechanism.
 *
 * On the first device build the gate was unclearable. The progress ring
 * reached 100%, which proved the sustain had completed, but the collector
 * never acted on the pass. Pass was a boolean on the progress value, progress
 * travels on a `StateFlow`, and a `StateFlow` conflates: a value that holds
 * for one sensor sample can be overwritten before the collector is scheduled,
 * and then it is simply gone.
 *
 * The first test reproduces that loss. The second shows the replaying
 * `SharedFlow` surviving the identical sequence. Both run the producer without
 * yielding between emissions, which is what a sensor callback burst at 200 Hz
 * looks like from the collector's point of view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalEventDeliveryTest {

    @Test
    fun `a StateFlow loses a terminal value superseded in the same dispatch`() {
        // This is the old design. It is asserted as a loss, not as a pass,
        // because the point is that the mechanism cannot carry a terminal
        // event.
        runTest {
            val progress = MutableStateFlow(0)
            var sawTerminal = false

            val collector = launch {
                progress.collect { if (it == TERMINAL) sawTerminal = true }
            }
            yield() // let the collector attach and take the initial value

            // Producer burst with no suspension point between emissions,
            // exactly as a sensor callback would produce.
            progress.value = TERMINAL
            progress.value = SUPERSEDING
            yield()

            assertFalse(
                "a StateFlow is expected to drop the superseded terminal value",
                sawTerminal,
            )
            assertEquals(SUPERSEDING, progress.value)
            collector.cancel()
        }
    }

    @Test
    fun `a replaying SharedFlow delivers the terminal value through the same burst`() {
        runTest {
            val progress = MutableStateFlow(0)
            val outcome = MutableSharedFlow<Int>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            var received = -1

            val collector = launch { received = outcome.first() }
            yield()

            // Identical burst. The terminal event is on its own channel.
            outcome.tryEmit(TERMINAL)
            progress.value = SUPERSEDING
            yield()

            assertEquals("the terminal event must survive the burst", TERMINAL, received)
            collector.cancel()
        }
    }

    @Test
    fun `a collector attaching after the emission still sees it`() {
        // The gate's collector is launched just after the detector starts, so
        // the emission can precede the subscription.
        runTest {
            val outcome = MutableSharedFlow<Int>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            outcome.tryEmit(TERMINAL)

            var received = -1
            val collector = launch { received = outcome.first() }
            yield()

            assertEquals(TERMINAL, received)
            collector.cancel()
        }
    }

    @Test
    fun `tryEmit always succeeds so the sensor callback never suspends`() {
        val outcome = MutableSharedFlow<Int>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        // No collector at all, and still no back pressure. The emitter here is
        // the sensor callback on the main thread, which must never block.
        repeat(100) { assertTrue(outcome.tryEmit(it)) }
    }

    @Test
    fun `resetReplayCache stops a previous session leaking into the next`() {
        // Without this the next gate reads the previous gate's pass out of the
        // replay cache and clears itself the instant a collector attaches.
        runTest {
            val outcome = MutableSharedFlow<Int>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            outcome.tryEmit(TERMINAL)
            assertEquals(listOf(TERMINAL), outcome.replayCache)

            outcome.resetReplayCache()
            assertEquals(emptyList<Int>(), outcome.replayCache)
        }
    }

    private companion object {
        const val TERMINAL = 1
        const val SUPERSEDING = 2
    }
}
