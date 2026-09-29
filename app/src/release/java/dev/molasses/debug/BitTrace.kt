package dev.molasses.debug

import dev.molasses.core.bit.BitStateMachine

/**
 * Release implementation of the blink trace. It does nothing, and it is the
 * only version of this file compiled into a release variant.
 *
 * The debug counterpart in `src/debug` holds the instrumentation. Keeping them
 * in separate source sets means a 25 line per second trace is absent from
 * release by construction rather than by relying on R8 to remove a dead
 * branch, which is the same split `DebugSurface` uses.
 *
 * Keep the two signatures identical. `BitTraceTest` checks that.
 */
object BitTrace {

    const val ENABLED: Boolean = false

    const val TAG = "Molasses.BitTrace"

    /** No-op. */
    @Suppress("UNUSED_PARAMETER")
    fun tick(
        originMs: Long,
        tickMs: Long,
        cycle: BitStateMachine.BlinkCycle,
        blinking: Boolean,
    ) = Unit

    /** No-op. */
    @Suppress("UNUSED_PARAMETER")
    fun drew(face: String) = Unit
}
