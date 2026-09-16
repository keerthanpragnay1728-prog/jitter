package dev.molasses.monitor

import android.os.SystemClock
import dev.molasses.core.diag.RouteTally
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.core.diag.ServiceHealthPolicy
import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.WindowEvent

/**
 * Live service state, readable from the settings UI.
 *
 * ## Why a singleton and not a binding
 * The accessibility service and the settings Activity run in the same process:
 * nothing in the manifest declares `android:process`, and the whole
 * architecture depends on that (one host process, no secondary service). A
 * plain object is therefore the honest mechanism, and a bound connection or a
 * DataStore round trip would be ceremony around a volatile field.
 *
 * It does mean this survives the service being destroyed. That is deliberate:
 * [connectedAtMs] staying set while the heartbeat stops is exactly how a
 * killed service is distinguished from one that never started, and collapsing
 * the two would lose the diagnosis.
 *
 * ## Why this exists at all
 * "Granted" in the permission checklist reads
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which is a user preference
 * string. A service that is enabled but not running, or running but stuck
 * before `ready`, is invisible there while producing no friction whatsoever.
 * That combination is the single most likely explanation for a phone where
 * nothing happens, and until now it could not be seen from inside the app.
 */
object ServiceDiagnostics {

    @Volatile var connectedAtMs: Long = 0L
        private set

    @Volatile var readyAtMs: Long = 0L
        private set

    @Volatile var lastHeartbeatMs: Long = 0L
        private set

    /** Set when the reconciler failed or timed out. Shown verbatim in debug. */
    @Volatile var startupNote: String? = null

    /**
     * Set while a call-detection path is degraded. Shown verbatim in debug.
     *
     * The call checks are the reason a stall can never survive an incoming
     * call, and both of them can go quiet without anything changing shape: the
     * telephony callback fails to register because `READ_PHONE_STATE` is not
     * requested, and `AudioManager` can be absent or throw. Either way the
     * answer handed back is still an ordinary boolean, so from outside the app
     * a degraded panic path is indistinguishable from a healthy one.
     *
     * Logcat is not a substitute. The only place this matters is a device in
     * someone's hand, which is the one place logcat is not being read.
     */
    @Volatile var panicPathNote: String? = null

    /** Whatever `packageNames` was last handed to `setServiceInfo`. */
    @Volatile var appliedPackageNames: List<String> = emptyList()

    /** True when the stored target list was empty and the defaults stood in. */
    @Volatile var usedTargetFallback: Boolean = false

    /**
     * When the armed stall sink is due to release, on `elapsedRealtime`.
     *
     * Read by Bit, which holds flat eyes for exactly that window. A deadline
     * rather than a boolean so a reader that misses the release still expires
     * it correctly; the sink is already tracking the same number.
     */
    @Volatile var shutterArmedUntilElapsedMs: Long = 0L
        private set

    /**
     * `elapsedRealtime` of the last touch the sink actually swallowed.
     *
     * A timestamp rather than an event, because the reader is a UI polling at
     * frame rate and a flag would have to be cleared by someone. Only set when
     * a touch was really absorbed, never merely because the sink is armed: at
     * a ten percent stall probability, a tell that fired on every armed window
     * would say that something is running rather than that something is
     * broken.
     */
    @Volatile var lastTouchAbsorbedElapsedMs: Long = 0L
        private set

    fun onShutterArmed(untilElapsedMs: Long) {
        shutterArmedUntilElapsedMs = maxOf(shutterArmedUntilElapsedMs, untilElapsedMs)
    }

    fun onShutterReleased() {
        shutterArmedUntilElapsedMs = 0L
    }

    /**
     * A movement gate is on screen.
     *
     * Read by the console before it lets Bit speak. The gate is a
     * TYPE_ACCESSIBILITY_OVERLAY above everything, so the launcher can be
     * composing underneath one and would otherwise deliver a line into a
     * window nobody can see.
     */
    @Volatile var gateShowing: Boolean = false

    fun onTouchAbsorbed() {
        lastTouchAbsorbedElapsedMs = SystemClock.elapsedRealtime()
    }

    /** True while the sink is swallowing touches. */
    fun shutterArmed(): Boolean =
        shutterArmedUntilElapsedMs > SystemClock.elapsedRealtime()

    /**
     * Written only from the accessibility callback thread, which the platform
     * serialises. Read from the UI thread through [tallySnapshot], which
     * copies, so a torn read is the worst case and a stale count is harmless.
     */
    private val tally = RouteTally()

    fun onConnected() {
        connectedAtMs = SystemClock.elapsedRealtime()
        readyAtMs = 0L
        lastHeartbeatMs = 0L
        startupNote = null
        panicPathNote = null
        tally.reset()
    }

    fun onReady() {
        readyAtMs = SystemClock.elapsedRealtime()
        lastHeartbeatMs = readyAtMs
    }

    fun onHeartbeat() {
        lastHeartbeatMs = SystemClock.elapsedRealtime()
    }

    fun onDestroyed() {
        connectedAtMs = 0L
        readyAtMs = 0L
        lastHeartbeatMs = 0L
        shutterArmedUntilElapsedMs = 0L
        gateShowing = false
    }

    fun recordEvent(event: WindowEvent, route: EventRoute) = tally.record(event, route)

    fun tallySnapshot(): List<Pair<String, RouteTally.PackageTally>> = tally.snapshot()

    val overflowedPackages: Long get() = tally.overflowedPackages

    private fun snapshot() = ServiceHealthPolicy.Snapshot(
        connectedAtMs = connectedAtMs,
        readyAtMs = readyAtMs,
        lastHeartbeatMs = lastHeartbeatMs,
        nowMs = SystemClock.elapsedRealtime(),
    )

    fun health(): ServiceHealth = ServiceHealthPolicy.evaluate(snapshot())

    /**
     * Connected, and still not accepting events past the grace period. The
     * reconciler awaits DataStore; if that never emits, the service sits bound
     * and discards every event with nothing in the log.
     */
    fun isStuckStarting(): Boolean = ServiceHealthPolicy.isStuckStarting(snapshot())

    /** Milliseconds since the last heartbeat, or null before the first. */
    fun heartbeatAgeMs(): Long? =
        if (lastHeartbeatMs <= 0L) null else SystemClock.elapsedRealtime() - lastHeartbeatMs
}
