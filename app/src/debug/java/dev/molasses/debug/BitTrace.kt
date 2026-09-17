package dev.molasses.debug

import android.os.SystemClock
import android.util.Log
import dev.molasses.core.bit.BitStateMachine

/**
 * Blink instrumentation. Debug variant only; the release counterpart in
 * `src/release` does nothing.
 *
 * ## What this is for
 * The blink has been diagnosed twice and fixed twice and still reads as a
 * twitch. Three causes were found and all three were real: a closure shorter
 * than the sample interval, a tick origin that the pager reset on every swipe,
 * and a phase walk from zero. A fourth guess is not worth making. This
 * measures instead.
 *
 * ## What it separates
 * Two questions, and they have different fixes:
 *
 *  1. **Is the state right?** [tick] logs the origin, the cycle and the phase
 *     every frame, and counts how many consecutive frames each closure was
 *     actually true for. A closure that lasts three frames every time is
 *     correct at 120 ms against a 40 ms tick. One that lands on one frame,
 *     then three, then two is a sampling problem and no amount of hoisting
 *     fixes it.
 *  2. **Did what was drawn follow?** [drew] logs every change of rendered
 *     face with the time it happened. If the state says three frames of
 *     `( -_- )` and the render shows it appearing and vanishing inside one,
 *     the bug is downstream of everything the state machine controls.
 *
 * An origin that changes between frames answers a third question on its own:
 * it is on every line, so a hoisting regression is visible without counting
 * anything.
 *
 * ## Why it logs rather than asserting
 * There is nothing to assert. The pure blink schedule is already covered by
 * `BitStateMachineTest`, and it passes. What is missing is what the device
 * does with it, and the only instrument for that is a timestamped trace off
 * the real frame loop.
 */
object BitTrace {

    const val ENABLED: Boolean = true

    /** One tag, so a single logcat filter captures the whole picture. */
    const val TAG = "Molasses.BitTrace"

    private var blinkFrames = 0
    private var blinkStartedAtMs = 0L
    private var lastFace: String? = null
    private var lastOrigin = Long.MIN_VALUE

    /**
     * One line per frame, from the blink ticker.
     *
     * Called before [blinking] is written to state, so the line describes the
     * value that is about to be published rather than the previous one.
     */
    fun tick(originMs: Long, tickMs: Long, cycle: BitStateMachine.BlinkCycle, blinking: Boolean) {
        // Called out separately, because it is the one failure that needs no
        // counting to interpret and the one the previous fix was aimed at.
        if (originMs != lastOrigin) {
            if (lastOrigin != Long.MIN_VALUE) {
                Log.w(TAG, "ORIGIN MOVED $lastOrigin -> $originMs: the ticker was restarted")
            }
            lastOrigin = originMs
        }

        Log.d(
            TAG,
            "T origin=$originMs tick=$tickMs idx=${cycle.index} " +
                "start=${cycle.startedAtMs} phase=${tickMs - cycle.startedAtMs} blink=$blinking",
        )

        if (blinking) {
            if (blinkFrames == 0) blinkStartedAtMs = SystemClock.elapsedRealtime()
            blinkFrames += 1
        } else if (blinkFrames > 0) {
            // The measurement. Frames is how many times the composition was
            // told the eyes were shut; span is how long that really lasted on
            // the device clock, which is the number a person sees.
            Log.i(
                TAG,
                "BLINK idx=${cycle.index} frames=$blinkFrames " +
                    "span=${SystemClock.elapsedRealtime() - blinkStartedAtMs}ms " +
                    "(expected 3 frames, ${BitStateMachine.BLINK_HALF_MS}ms)",
            )
            blinkFrames = 0
        }
    }

    /**
     * What actually reached the screen.
     *
     * Logged only on a change, so the count of lines between two BLINK
     * records is exactly how many times the face was redrawn during one
     * closure. Two lines per blink is correct: one in, one out. More than two
     * means something is toggling it inside the closure.
     */
    fun drew(face: String) {
        if (face == lastFace) return
        lastFace = face
        Log.d(TAG, "DRAW face=$face at=${SystemClock.elapsedRealtime()}")
    }
}
