package dev.molasses.core.command

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredWaitTest {

    private val answer = DispatchResult.Answered(1)
    private val timeout = DispatchResult.Failed(2)

    @Test
    fun `an answer inside the deadline is delivered, once, and the timer never fires`() = runTest {
        val got = mutableListOf<DispatchResult>()
        var deliver: ((DispatchResult) -> Unit)? = null
        DeferredWait.start(this, DispatchResult.Deferred { deliver = it }, timeout) { got += it }
        advanceTimeBy(1_000)
        deliver!!(answer)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf<DispatchResult>(answer), got)
    }

    @Test
    fun `no answer by three seconds delivers the timeout and releases the prompt`() = runTest {
        val got = mutableListOf<DispatchResult>()
        DeferredWait.start(this, DispatchResult.Deferred { }, timeout) { got += it }
        advanceTimeBy(DeferredWait.TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(emptyList<DispatchResult>(), got)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<DispatchResult>(timeout), got)
    }

    @Test
    fun `an answer after the deadline is dropped and reported as late`() = runTest {
        val got = mutableListOf<DispatchResult>()
        val late = mutableListOf<DispatchResult>()
        var deliver: ((DispatchResult) -> Unit)? = null
        DeferredWait.start(this, DispatchResult.Deferred { deliver = it }, timeout, onLate = { late += it }) { got += it }
        advanceTimeBy(DeferredWait.TIMEOUT_MS + 1)
        runCurrent()
        deliver!!(answer)
        assertEquals(listOf<DispatchResult>(timeout), got)
        assertEquals(listOf<DispatchResult>(answer), late)
    }

    @Test
    fun `an answer delivered synchronously wins before any time passes`() = runTest {
        val got = mutableListOf<DispatchResult>()
        DeferredWait.start(this, DispatchResult.Deferred { it(answer) }, timeout) { got += it }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf<DispatchResult>(answer), got)
    }
}
