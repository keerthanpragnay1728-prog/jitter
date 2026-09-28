package dev.molasses.core.diag

/**
 * Is the accessibility service actually alive, as distinct from enabled?
 *
 * ## Why these are different questions
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is a user preference
 * string. It says the user once ticked a box. It does not say the process is
 * running, that `onServiceConnected` fired, or that the service got far enough
 * to accept an event. A service that was revoked, crashed, or is stuck part
 * way through startup reads as "Granted" in a checklist built on that string,
 * which is exactly how a phone with no friction at all looks completely
 * healthy in settings.
 *
 * ## The three timestamps
 * [connectedAtMs] is set in `onServiceConnected`. [readyAtMs] is set only
 * after reconciliation completes, which is the point at which
 * `onAccessibilityEvent` stops dropping everything on the floor.
 * [lastHeartbeatMs] is written by the existing 15 s checkpoint.
 *
 * The gap between connected and ready is the failure mode worth naming: the
 * reconciler awaits DataStore, and if DataStore never emits, the service sits
 * there bound and silently discarding every event, with nothing in the log to
 * say so. [ServiceHealth.CONNECTING] past [STARTUP_GRACE_MS] is that state.
 *
 * Pure; no Android imports. Unit-tested in `ServiceHealthTest`.
 */
enum class ServiceHealth {
    /** Never seen. Either not enabled, or enabled and not yet started. */
    NEVER_CONNECTED,

    /** Connected, not ready. Normal for a moment, a fault if it persists. */
    CONNECTING,

    /** Connected and ready, but the heartbeat has stopped. Probably killed. */
    STALE,

    /** Connected, ready, beating. */
    HEALTHY,
    ;

    val acceptingEvents: Boolean get() = this == HEALTHY || this == STALE
}

object ServiceHealthPolicy {

    /**
     * Longer than reconciliation should ever take, short enough that a user
     * staring at the debug screen sees the fault rather than a spinner.
     */
    const val STARTUP_GRACE_MS = 5_000L

    /**
     * Three missed checkpoints. One missed beat is a doze window or a slow
     * write; three in a row means the process is gone.
     */
    const val HEARTBEAT_TIMEOUT_MS = 45_000L

    /**
     * The one rule for "the service is working", shown by CFG's service row
     * and required by the first-run flow. Both call this, so they cannot
     * disagree.
     *
     * HEALTHY and nothing looser. CONNECTING is bound but not yet accepting
     * events. STALE is a service whose heartbeat stopped, probably killed,
     * and it still accepts events in [ServiceHealth.acceptingEvents]'s sense
     * only because that answers a different question.
     *
     * And still switched on in Settings: the diagnostics live in the
     * process, not the service, so after the user switches it off they keep
     * reading HEALTHY until the heartbeat times out, up to
     * [HEARTBEAT_TIMEOUT_MS] later.
     */
    fun working(health: ServiceHealth, enabled: Boolean): Boolean =
        health == ServiceHealth.HEALTHY && enabled

    /**
     * All timestamps are `elapsedRealtime`. Zero means "never happened",
     * which is safe because elapsedRealtime is only zero in the first
     * millisecond after boot and nothing has connected by then.
     */
    data class Snapshot(
        val connectedAtMs: Long,
        val readyAtMs: Long,
        val lastHeartbeatMs: Long,
        val nowMs: Long,
    )

    fun evaluate(s: Snapshot): ServiceHealth {
        if (s.connectedAtMs <= 0L) return ServiceHealth.NEVER_CONNECTED
        if (s.readyAtMs <= 0L) return ServiceHealth.CONNECTING

        // A ready service that has never beaten is not yet stale; the first
        // checkpoint is up to 15 s out. Fall back to the ready time so the
        // window is measured from something real.
        val lastSign = maxOf(s.lastHeartbeatMs, s.readyAtMs)
        return if (s.nowMs - lastSign > HEARTBEAT_TIMEOUT_MS) {
            ServiceHealth.STALE
        } else {
            ServiceHealth.HEALTHY
        }
    }

    /**
     * True when the service has been connected longer than the grace period
     * without becoming ready. This is the silent-drop condition, and it is
     * called out separately from [evaluate] because it needs a log line and a
     * visible warning rather than just a status word.
     */
    fun isStuckStarting(s: Snapshot): Boolean =
        s.connectedAtMs > 0L &&
            s.readyAtMs <= 0L &&
            s.nowMs - s.connectedAtMs > STARTUP_GRACE_MS
}
