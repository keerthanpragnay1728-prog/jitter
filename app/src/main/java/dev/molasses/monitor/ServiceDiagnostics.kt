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

    /** Whatever `packageNames` was last handed to `setServiceInfo`. */
    @Volatile var appliedPackageNames: List<String> = emptyList()

    /** True when the stored target list was empty and the defaults stood in. */
    @Volatile var usedTargetFallback: Boolean = false

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
