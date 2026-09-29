package dev.molasses.core.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Waits on a [DispatchResult.Deferred], with a deadline. Pure.
 *
 * The console ignores Enter while a deferred answer is in flight, so a line
 * cannot run twice. An answer that never lands would leave the prompt dead,
 * so after [TIMEOUT_MS] the wait gives up: [timedOut] is delivered in its
 * place and Enter comes back. Exactly one result is ever delivered, the
 * first of the two. An answer that lands after the deadline is dropped and
 * reported through [onLate], because by then the prompt has moved on; the
 * command itself may still have done its work (a reminder may be saved),
 * which is why the timeout copy says to check.
 */
object DeferredWait {

    const val TIMEOUT_MS = 3_000L

    fun start(
        scope: CoroutineScope,
        deferred: DispatchResult.Deferred,
        timedOut: DispatchResult,
        timeoutMs: Long = TIMEOUT_MS,
        onLate: (DispatchResult) -> Unit = {},
        deliver: (DispatchResult) -> Unit,
    ) {
        val lock = Any()
        var settled = false
        fun settle(): Boolean = synchronized(lock) {
            if (settled) false else {
                settled = true
                true
            }
        }
        val timer = scope.launch {
            delay(timeoutMs)
            if (settle()) deliver(timedOut)
        }
        deferred.await { result ->
            if (settle()) {
                timer.cancel()
                deliver(result)
            } else {
                onLate(result)
            }
        }
    }
}
