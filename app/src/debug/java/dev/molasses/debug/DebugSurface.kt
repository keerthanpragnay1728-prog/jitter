package dev.molasses.debug

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug-build implementation of the developer surface.
 *
 * There is a matching file in `src/release` that does nothing. Splitting by
 * source set rather than guarding with `if (BuildConfig.DEBUG)` means the
 * bypass code is not compiled into a release variant at all, so its absence is
 * structural instead of dependent on R8 stripping a branch.
 *
 * `DebugSurfaceTest` asserts the two files stay in step.
 */
object DebugSurface {

    /** True only in a debug variant. */
    const val ENABLED: Boolean = true

    /** How long the progress ring must be held to bypass the gate. */
    const val BYPASS_HOLD_MS = 2_000L

    /**
     * Long-press to bypass the movement gate.
     *
     * Deliberately not `detectTapGestures(onLongPress = ...)`, whose timeout is
     * the platform default of about 500 ms. Two seconds is long enough that it
     * cannot be hit while trying to read the screen.
     */
    fun Modifier.debugBypassGesture(onBypass: () -> Unit): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // A null result means the timeout elapsed with the finger still
            // down, which is the hold we are looking for.
            val liftedEarly = withTimeoutOrNull(BYPASS_HOLD_MS) { waitForUpOrCancellation() }
            if (liftedEarly == null) {
                onBypass()
                // Swallow the eventual lift so it cannot also register as a tap.
                waitForUpOrCancellation()
            }
        }
    }
}
