package dev.molasses.probe

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.molasses.core.friction.FrictionCurve
import kotlin.math.roundToLong
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SS11.1 `latency_probe`.
 *
 * Measures the quantity the whole design rests on: how long after a scroll
 * event the touch sink is actually eating touches. If that is more than a
 * frame or two the illusion collapses -- the user perceives a delayed
 * blackout as a separate, unrelated glitch rather than as their own flick
 * failing.
 *
 * ## What this can and cannot measure
 * It measures the *arming* path: flag mutation plus `updateViewLayout` plus
 * the platform's window-update round trip, which is the part under our
 * control. It cannot measure the platform's scroll-event delivery latency
 * (`onAccessibilityEvent` timestamp minus the user's finger moving), because
 * nothing in the app observes the finger. Read the output as a floor on total
 * perceived latency, not the whole of it.
 *
 * ## Requires
 * A connected device or emulator. `TYPE_ACCESSIBILITY_OVERLAY` needs a live
 * accessibility service token, which an instrumentation test does not have, so
 * this probe uses `TYPE_APPLICATION_OVERLAY` and therefore needs
 * `SYSTEM_ALERT_WINDOW` granted:
 *
 * ```
 * adb shell appops set dev.molasses SYSTEM_ALERT_WINDOW allow
 * adb shell am instrument -w -e class dev.molasses.probe.LatencyProbeTest \
 *     dev.molasses.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The window type differs from production; the arming path measured
 * (`params.flags` mutation then `updateViewLayout`) is identical.
 */
@RunWith(AndroidJUnit4::class)
class LatencyProbeTest {

    private data class Sample(val requestedMs: Long, val armLatencyMs: Long)

    @Test
    fun armLatencyHistogram() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wm = context.getSystemService(WindowManager::class.java)
            ?: error("no WindowManager")

        val samples = mutableListOf<Sample>()
        val lock = Any()
        // Captured by the sink below; Kotlin closures capture mutable locals.
        var pendingRequest = 0L

        instrumentation.runOnMainSync {
            val sink = object : View(context) {
                var armed = false
                var armedAt = 0L
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (!armed) return false
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        synchronized(lock) {
                            samples += Sample(
                                requestedMs = pendingRequest,
                                armLatencyMs = SystemClock.elapsedRealtime() - armedAt,
                            )
                        }
                    }
                    return true
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                IDLE_FLAGS,
                android.graphics.PixelFormat.TRANSLUCENT,
            )

            wm.addView(sink, params)

            // Arm and immediately synthesise a touch, ROUNDS times, across
            // three durations the curve actually commands: the floor, the
            // ceiling at the longest horizon, and the ceiling at the default.
            // They used to come from the fixed tier ladder, which no longer
            // decides any stall.
            val durations = listOf(
                FrictionCurve.DEFAULT_FLOOR_MS.toLong(),
                FrictionCurve.terminalStallMs(FrictionCurve.MAX_HORIZON_MS).toLong(),
                FrictionCurve.TERMINAL_STALL_MS.toLong(),
            )
            repeat(ROUNDS) { i ->
                val requested = durations[i % durations.size]
                pendingRequest = requested

                val armStart = SystemClock.elapsedRealtime()
                params.flags = ARMED_FLAGS
                wm.updateViewLayout(sink, params)
                sink.armed = true
                sink.armedAt = armStart

                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, 50f, 400f, 0,
                )
                sink.dispatchTouchEvent(down)
                down.recycle()

                sink.armed = false
                params.flags = IDLE_FLAGS
                wm.updateViewLayout(sink, params)
            }

            wm.removeViewImmediate(sink)
        }

        val latencies = samples.map { it.armLatencyMs }.sorted()
        assertTrue("probe collected no samples", latencies.isNotEmpty())

        fun pct(p: Double) =
            latencies[((latencies.size - 1) * p).roundToLong().toInt().coerceIn(latencies.indices)]

        println(
            buildString {
                appendLine("=== latency_probe: sink arm -> touch consumed ===")
                appendLine("samples ${latencies.size}")
                appendLine("min  ${latencies.first()} ms")
                appendLine("p50  ${pct(0.50)} ms")
                appendLine("p90  ${pct(0.90)} ms")
                appendLine("p99  ${pct(0.99)} ms")
                appendLine("max  ${latencies.last()} ms")
                appendLine()
                appendLine("Histogram (ms bucket -> count):")
                latencies.groupingBy { it }.eachCount().toSortedMap()
                    .forEach { (ms, n) -> appendLine("  $ms -> $n") }
            },
        )

        // Deliberately loose: this asserts the probe ran and the arming path
        // is not pathologically slow. The number to look at is the printed
        // p90, against a 16.7 ms frame.
        assertTrue(
            "p90 arm latency ${pct(0.90)} ms exceeds one frame budget by a wide margin",
            pct(0.90) < 100,
        )
    }

    private companion object {
        const val ROUNDS = 60

        const val IDLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        const val ARMED_FLAGS =
            IDLE_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }
}
