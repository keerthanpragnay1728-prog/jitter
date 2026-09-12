package dev.molasses.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import android.view.Choreographer
import dev.molasses.core.latency.LatencyRegistry
import dev.molasses.core.latency.Segment
import dev.molasses.core.model.EventType
import dev.molasses.engine.FrictionLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Phantom Stall: a full-screen window that swallows touches for a
 * tier-dependent interval so the device feels momentarily broken rather than
 * blocked.
 *
 * ## Why TYPE_ACCESSIBILITY_OVERLAY
 * A `TYPE_APPLICATION_OVERLAY` window is untrusted, so Android 12's
 * untrusted-touch-blocking rules apply: a window that is both invisible-ish
 * and touch-consuming is exactly what that feature exists to stop, and the
 * platform would pass touches through regardless of our flags.
 * `TYPE_ACCESSIBILITY_OVERLAY` comes from the accessibility service's own
 * window token, is trusted, and needs no `SYSTEM_ALERT_WINDOW` grant.
 * `SYSTEM_ALERT_WINDOW` stays in the manifest only for the settings-screen
 * preview, which has no service token to borrow.
 *
 * ## Why the view is added once
 * Creating a window costs 1-3 frames. At tier 1 the whole stall is 1000 ms and
 * the user's perception of "the screen went dead the instant I flicked" is the
 * entire product, so arming has to be a flag mutation on an existing window,
 * not an `addView`.
 */
class ShutterOverlayManager(
    private val service: Context,
    private val windowManager: WindowManager,
    private val ledger: FrictionLedger,
    private val scope: CoroutineScope,
    /** Shared with the service, which records segment A. */
    val latency: LatencyRegistry = LatencyRegistry(),
) {
    private val sink = SinkView(service)
    private var added = false

    /** Deadline on the monotonic clock; 0 when disarmed. */
    private var armedUntilElapsed = 0L
    private var armedSinceElapsed = 0L
    private var requestedMs = 0L
    private var disarmJob: Job? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        IDLE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // ---------------------------------------------------------- panic paths

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) release("screen off")
        }
    }
    private var receiverRegistered = false

    private val telephony: TelephonyManager? =
        service.getSystemService(TelephonyManager::class.java)

    private var telephonyCallback: Any? = null

    // ------------------------------------------------------------- lifecycle

    /** Called when a target app enters the foreground. Idempotent. */
    fun attach() {
        if (added) return
        try {
            windowManager.addView(sink, params)
            added = true
        } catch (e: Exception) {
            // A duplicate add, a dead token, or a display that went away.
            // Never fatal: without the sink the app simply behaves normally.
            Log.w(TAG, "sink addView failed", e)
            added = false
            return
        }
        registerPanicListeners()
    }

    /** Called when the target app leaves the foreground, or on teardown. */
    fun detach() {
        disarm("detach")
        unregisterPanicListeners()
        if (!added) return
        try {
            windowManager.removeViewImmediate(sink)
        } catch (e: Exception) {
            // WindowManager throws if the view is already detached.
            Log.w(TAG, "sink removeView failed", e)
        } finally {
            added = false
        }
    }

    // ------------------------------------------------------------- arm/disarm

    /**
     * Arm for [ms], extending rather than restacking.
     *
     * A scroll burst produces many calls in quick succession. Taking the max
     * of the existing and the new deadline means the blackout ends [ms] after
     * the *last* scroll, which is what "every scroll blacks out" has to mean;
     * queueing them would multiply a 5 s stall into half a minute.
     */
    @JvmOverloads
    fun arm(
        ms: Long,
        /**
         * `AccessibilityEvent.getEventTime()` of the scroll that triggered
         * this, on the `uptimeMillis` clock. Segment D is measured from here.
         * Zero disables latency accounting (the settings-screen preview).
         */
        scrollEventTimeUptimeMs: Long = 0,
        /** `uptimeMillis` at entry to `onAccessibilityEvent`. Starts segment B. */
        callbackEntryUptimeMs: Long = 0,
    ) {
        if (!added) attach()
        if (!added) return

        val now = SystemClock.elapsedRealtime()
        if (armedUntilElapsed <= now) {
            armedSinceElapsed = now
            requestedMs = ms
            sink.armedAtElapsed = now
        } else {
            requestedMs = maxOf(requestedMs, ms)
        }

        // Hard ceiling: never armed for more than 8 s continuously, whatever
        // the tier or the scroll rate. Without it, continuous scrolling at
        // tier 3 would hold the screen dead indefinitely, which stops being
        // friction and becomes a broken phone.
        val ceiling = armedSinceElapsed + MAX_CONTINUOUS_ARMED_MS
        val target = minOf(now + ms, ceiling)
        armedUntilElapsed = maxOf(armedUntilElapsed, target)

        setFlags(ARMED_FLAGS)
        sink.setArmed(true)

        // SS6 segments B, C and the D start marker. All four timestamps are on
        // uptimeMillis, because MotionEvent.getEventTime() and
        // AccessibilityEvent.getEventTime() are on that clock and can be
        // subtracted directly; currentTimeMillis cannot be compared with
        // either.
        if (scrollEventTimeUptimeMs > 0) {
            val pkg = sink.currentPkg
            sink.pendingScrollEventTimeUptimeMs = scrollEventTimeUptimeMs
            val afterUpdate = SystemClock.uptimeMillis()
            if (callbackEntryUptimeMs > 0 && pkg != null) {
                latency.forPackage(pkg).record(Segment.B, afterUpdate - callbackEntryUptimeMs)
            }
            // Segment C: updateViewLayout returning means only that the change
            // is queued to WindowManagerService. The flag is not live in the
            // input dispatcher until WMS relayouts and InputDispatcher
            // refreshes its window handles, one or more frames later. Stopping
            // at the call's return would under-report absorption latency by a
            // frame or more.
            Choreographer.getInstance().postFrameCallback {
                pkg?.let {
                    latency.forPackage(it)
                        .record(Segment.C, SystemClock.uptimeMillis() - afterUpdate)
                }
            }
        }

        disarmJob?.cancel()
        disarmJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                val remaining = armedUntilElapsed - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                delay(remaining)
            }
            disarm("deadline")
        }
    }

    /** Panic release: immediate, unconditional, logged. */
    fun release(reason: String) {
        if (armedUntilElapsed == 0L) return
        disarm(reason)
    }

    private fun disarm(reason: String) {
        disarmJob?.cancel()
        disarmJob = null
        if (armedUntilElapsed == 0L && !sink.armed) return

        val actual = SystemClock.elapsedRealtime() - armedSinceElapsed
        armedUntilElapsed = 0
        setFlags(IDLE_FLAGS)
        sink.setArmed(false)

        if (requestedMs > 0) {
            // SS6: log requested vs. actual so real latency is measurable
            // rather than assumed.
            ledger.log(
                sink.currentPkg ?: "",
                EventType.STALL_ARMED,
                "requestedMs=$requestedMs actualMs=$actual release=$reason",
            )
            requestedMs = 0
        }
        armedSinceElapsed = 0
    }

    /** Set by the service so ledger rows attribute the stall to an app. */
    fun setCurrentPackage(pkg: String?) { sink.currentPkg = pkg }

    val isArmed: Boolean get() = armedUntilElapsed > SystemClock.elapsedRealtime()

    private fun setFlags(flags: Int) {
        if (!added || params.flags == flags) return
        params.flags = flags
        try {
            windowManager.updateViewLayout(sink, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    // --------------------------------------------------------- panic wiring

    private fun registerPanicListeners() {
        if (!receiverRegistered) {
            runCatching {
                // ACTION_SCREEN_OFF is a protected system broadcast and so is
                // exempt from Android 14's exported-flag requirement, but
                // declaring NOT_EXPORTED keeps the intent explicit and lint
                // quiet.
                ContextCompat.registerReceiver(
                    service,
                    screenOffReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }
        }
        if (telephonyCallback != null) return

        // TelephonyCallback is API 31+. minSdk is 30, so API 30 needs the
        // deprecated PhoneStateListener; both need READ_PHONE_STATE, and both
        // are wrapped because a missing grant throws SecurityException rather
        // than degrading.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state != TelephonyManager.CALL_STATE_IDLE) release("call state $state")
                    }
                }
                telephony?.registerTelephonyCallback(service.mainExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val cb = object : PhoneStateListener() {
                    @Deprecated("Deprecated in API 31, required on API 30")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state != TelephonyManager.CALL_STATE_IDLE) release("call state $state")
                    }
                }
                @Suppress("DEPRECATION")
                telephony?.listen(cb, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = cb
            }
        }.onFailure {
            // Almost always a missing READ_PHONE_STATE grant. The 8 s ceiling
            // and the tap escape still bound the worst case, so this is a
            // degraded panic path, not a dead one.
            Log.w(TAG, "call-state panic path unavailable", it)
        }
    }

    private fun unregisterPanicListeners() {
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(screenOffReceiver) }
            receiverRegistered = false
        }
        val cb = telephonyCallback ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                telephony?.unregisterTelephonyCallback(cb)
            } else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                telephony?.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
    }

    // ------------------------------------------------------------- the sink

    /**
     * A plain [View], deliberately not Compose. Compose in this window would
     * add a composition and a recomposer to the touch path of something whose
     * entire job is to consume a `MotionEvent` in as few microseconds as
     * possible, and the visible tell is two rectangles.
     */
    private inner class SinkView(context: Context) : View(context) {

        var armed = false
            private set
        var currentPkg: String? = null
        var armedAtElapsed = 0L

        /** Scroll eventTime of the arming event; 0 once segment D is recorded. */
        var pendingScrollEventTimeUptimeMs = 0L

        private val tellPaint = Paint().apply {
            color = TELL_COLOR
            alpha = TELL_ALPHA
        }
        private val tapTimesMs = ArrayDeque<Long>()
        private val density = context.resources.displayMetrics.density
        private val tellHeightPx = TELL_DP * density
        private val panicRegionPx = PANIC_REGION_DP * density

        init {
            setBackgroundColor(Color.TRANSPARENT)
            setWillNotDraw(false)
        }

        fun setArmed(value: Boolean) {
            if (armed == value) return
            armed = value
            if (!value) tapTimesMs.clear()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!armed) return false

            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Panic escape: four taps in the top-left 64 dp within 1.5 s.
                // Counted here because the sink is already consuming them.
                if (event.x <= panicRegionPx && event.y <= panicRegionPx) {
                    val now = SystemClock.uptimeMillis()
                    tapTimesMs.addLast(now)
                    while (tapTimesMs.isNotEmpty() && now - tapTimesMs.first() > PANIC_WINDOW_MS) {
                        tapTimesMs.removeFirst()
                    }
                    if (tapTimesMs.size >= PANIC_TAPS) {
                        tapTimesMs.clear()
                        release("panic taps")
                        return true
                    }
                }
                recordGroundTruth(event)
            }
            return true
        }

        /**
         * Segment D: scroll `eventTime` -> `eventTime` of the first touch this
         * sink actually consumed. The number that decides the product.
         */
        private fun recordGroundTruth(event: MotionEvent) {
            val scrollAt = pendingScrollEventTimeUptimeMs
            if (scrollAt <= 0L) return
            val pkg = currentPkg ?: return
            val d = event.eventTime - scrollAt

            // Only count a touch whose eventTime is after the arming scroll.
            // The sink can consume a touch that was already in flight, which
            // would report an absurdly low or negative D and flatter the
            // result. LatencyRing.add discards negatives and counts them.
            val recorded = latency.forPackage(pkg).record(Segment.D, d)
            if (recorded) pendingScrollEventTimeUptimeMs = 0L

            val p = latency.forPackage(pkg)
            Log.d(
                LATENCY_TAG,
                p.formatLine(
                    a = p[Segment.A].last,
                    b = p[Segment.B].last,
                    c = p[Segment.C].last,
                    d = d,
                ),
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (!armed) return
            // The visible tell. Non-negotiable: a user who has forgotten this
            // app is installed must be able to tell a deliberate stall from a
            // failing digitizer. Themeable via R.color.molasses_tell, but
            // there is no code path that removes it.
            canvas.drawRect(0f, 0f, width.toFloat(), tellHeightPx, tellPaint)
        }
    }

    companion object {
        private const val TAG = "Molasses.Shutter"
        const val LATENCY_TAG = "STALL_LATENCY"

        /** Touches pass straight through to the app below. */
        const val IDLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /**
         * Swallows every touch. `FLAG_NOT_TOUCHABLE` removed;
         * `FLAG_NOT_FOCUSABLE` retained so the stall never steals IME focus or
         * the back key from the host app -- a stall that ate the back button
         * would read as a crash, not as lag.
         */
        const val ARMED_FLAGS =
            IDLE_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

        const val MAX_CONTINUOUS_ARMED_MS = 8_000L
        const val PANIC_TAPS = 4
        const val PANIC_WINDOW_MS = 1_500L
        const val PANIC_REGION_DP = 64f
        const val TELL_DP = 2f
        const val TELL_COLOR = 0xFF8C8C96.toInt()
        const val TELL_ALPHA = 89 // ~35%
    }
}
