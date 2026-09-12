package dev.molasses.debug

import androidx.compose.ui.Modifier

/**
 * Release-build implementation of the developer surface. It does nothing, and
 * it is the only version of this file compiled into a release variant.
 *
 * The debug counterpart in `src/debug` holds the gate bypass. Keeping them in
 * separate source sets means the bypass is absent from release by
 * construction, rather than by relying on R8 to remove a dead branch.
 *
 * Keep the two signatures identical. `DebugSurfaceTest` checks that.
 */
object DebugSurface {

    const val ENABLED: Boolean = false

    const val BYPASS_HOLD_MS = 2_000L

    /** No-op. Returns the receiver unchanged. */
    @Suppress("UNUSED_PARAMETER")
    fun Modifier.debugBypassGesture(onBypass: () -> Unit): Modifier = this
}
