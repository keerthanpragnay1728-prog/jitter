package dev.molasses.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.core.latency.LatencyRegistry
import dev.molasses.core.latency.Segment
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.FrictionAction
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.WallClock
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.repo.DataStoreEngineStore
import dev.molasses.data.repo.RoomFrictionLedger
import dev.molasses.engine.FrictionEngine
import dev.molasses.overlay.GateOverlayManager
import dev.molasses.overlay.ShutterOverlayManager
import dev.molasses.sensing.MovementDetector
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only long-lived component. Deliberately not a foreground service: an
 * `AccessibilityService` is already system-bound and persistent, and adding an
 * FGS on API 34+ would force a `specialUse` type plus a Play justification for
 * no extra capability.
 *
 * ## The one hard performance rule
 * [onAccessibilityEvent] runs on this service's main thread and the platform
 * delivers events serially. Every millisecond spent here delays the next
 * event, and with `notificationTimeout = 0` the event rate during a scroll
 * burst is high. So this callback does arithmetic on in-memory state and
 * nothing else: no disk, no allocation beyond a `String`, no `runBlocking`.
 * Persistence goes to [scope]; the ledger and the DataStore both buffer
 * internally.
 */
@AndroidEntryPoint
class MolassesAccessibilityService : AccessibilityService() {

    @Inject lateinit var cycleStore: CycleStateStore
    @Inject lateinit var usageEventDao: UsageEventDao

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    private lateinit var ledger: RoomFrictionLedger
    private lateinit var engine: FrictionEngine
    private lateinit var shutter: ShutterOverlayManager
    private lateinit var gate: GateOverlayManager
    private var ready = false

    private var targets: Set<String> = emptySet()
    private var alternativeChallenge = false

    /** Package we currently believe is in the foreground, target or not. */
    private var foregroundPkg: String? = null

    private var watchdogJob: Job? = null
    private var checkpointJob: Job? = null

    /** SS6. Shared with the shutter, which records B, C and D. */
    private val latency = LatencyRegistry()

    private val probe by lazy { ForegroundProbe(this) }

    private val monotonic = MonotonicClock { SystemClock.elapsedRealtime() }
    private val wall = WallClock { System.currentTimeMillis() }

    private fun now() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()

        val wm = getSystemService(WindowManager::class.java)
        if (wm == null) {
            Log.e(TAG, "no WindowManager; cannot host overlays")
            return
        }

        ledger = RoomFrictionLedger(this, usageEventDao, scope)

        shutter = ShutterOverlayManager(
            service = this,
            windowManager = wm,
            ledger = ledger,
            scope = scope,
            latency = latency,
        )

        val detector = MovementDetector(this) { path ->
            // Stamp the active calibration domain onto every subsequent
            // ledger row.
            ledger.sensorPath = path?.name
        }
        gate = GateOverlayManager(
            service = this,
            windowManager = wm,
            detector = detector,
            ledger = ledger,
            scope = scope,
            onCleared = { pkg ->
                if (ready) engine.onGateCleared(pkg, now())
            },
            onAbandoned = { pkg ->
                if (ready) engine.onGateAbandoned(pkg, now())
            },
        )

        scope.launch {
            // Reconcile BEFORE the engine exists, so no live event can be
            // processed against un-reconciled state.
            val outcome = ForegroundReconciler(
                context = this@MolassesAccessibilityService,
                store = cycleStore,
                ledger = ledger,
            ).reconcile()

            Log.i(
                TAG,
                "reconciled: credited=${outcome.creditedMs}ms boot=${outcome.bootId} " +
                    "bootChanged=${outcome.bootChanged} tampered=${outcome.tampered} " +
                    "(${outcome.note})",
            )

            buildEngine(outcome.snapshot, outcome.bootId)
            observeSettings()
            startCheckpointing()
            ready = true
        }
    }

    private fun buildEngine(snapshot: EngineSnapshot, bootId: Int) {
        engine = FrictionEngine(
            initial = snapshot,
            store = DataStoreEngineStore(
                store = cycleStore,
                bootIdProvider = { bootId },
                openSessionPkgProvider = { engine.state.value.openSessionPkg },
            ),
            ledger = ledger,
            wallClock = wall,
            monotonicClock = monotonic,
            scope = scope,
        )
    }

    private fun observeSettings() {
        scope.launch {
            cycleStore.data.collect { state ->
                val next = state.targetPackagesList.toSet()
                alternativeChallenge = state.alternativeChallenge
                if (next != targets) {
                    targets = next
                    applyTargets(next)
                }
            }
        }
    }

    /**
     * Re-scope the event firehose at runtime. The manifest XML seeds
     * `packageNames`, but the user's target list is editable, so it has to be
     * re-pushed via `setServiceInfo()` -- otherwise a newly added app emits no
     * events at all and the feature silently does nothing.
     */
    private fun applyTargets(packages: Set<String>) {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.packageNames = if (packages.isEmpty()) null else packages.toTypedArray()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.notificationTimeout = 0
        runCatching { serviceInfo = info }
            .onFailure { Log.w(TAG, "setServiceInfo failed", it) }
    }

    /**
     * Fold the live session into persisted state every 15 s, so a process
     * death loses at most 15 s and [ForegroundReconciler] never has to scan
     * more than a few minutes of `UsageStats` history.
     */
    private fun startCheckpointing() {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            while (true) {
                delay(CHECKPOINT_INTERVAL_MS)
                val pkg = foregroundPkg
                if (pkg != null && pkg in targets) engine.checkpoint(now())
            }
        }
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Taken first: segment B is measured from here, and anything done
        // before this read is silently excluded from "our code".
        val callbackEntryUptimeMs = SystemClock.uptimeMillis()
        if (!ready) return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg)

            // TYPE_WINDOWS_CHANGED often reports the system or an overlay
            // rather than the app that now owns the screen, so it is only a
            // hint to re-probe -- never an enter or exit decision in itself.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val open = foregroundPkg
                if (open != null && open in targets) probeSoon(open)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg !in targets) return

                // Segment A: how long the platform took to deliver this event.
                // Nothing here can shorten it -- it is the number that decides
                // whether the concept is viable at all, as distinct from
                // whether this implementation is good.
                //
                // AccessibilityEvent.getEventTime() is on the uptimeMillis
                // clock, the same one MotionEvent.getEventTime() uses, so the
                // two are directly subtractable. currentTimeMillis is not
                // comparable with either.
                val eventTime = event.eventTime
                if (eventTime > 0) {
                    latency.forPackage(pkg).record(Segment.A, callbackEntryUptimeMs - eventTime)
                }

                when (val action = engine.onScroll(pkg, now())) {
                    is FrictionAction.Stall -> shutter.arm(
                        ms = action.ms,
                        scrollEventTimeUptimeMs = eventTime,
                        callbackEntryUptimeMs = callbackEntryUptimeMs,
                    )
                    is FrictionAction.Gate -> gate.show(pkg, action.tier, alternativeChallenge)
                    FrictionAction.None -> {}
                }
            }
        }
    }

    /**
     * Foreground *exit* detection.
     *
     * A target app leaving does not reliably produce a clean transition of its
     * own, so exit is inferred from a window-state change attributed to a
     * different package, backed by a 2 s watchdog for the apps that emit
     * nothing at all on the way out (and for launcher transitions that report
     * the launcher's package only intermittently).
     */
    private fun onWindowChanged(pkg: String) {
        val previous = foregroundPkg
        foregroundPkg = pkg

        if (pkg in targets) {
            engine.onForegroundEnter(pkg, now())
            shutter.setCurrentPackage(pkg)
            shutter.attach()
            armWatchdog(pkg)
            return
        }

        // Left the target app.
        if (previous != null && previous in targets) {
            leaveTarget(previous, "window changed to $pkg")
        }
    }

    private fun leaveTarget(pkg: String, reason: String) {
        watchdogJob?.cancel()
        watchdogJob = null
        engine.onForegroundExit(pkg, now())
        // Panic path: a leaked armed sink over the launcher is a bricked
        // phone, so releasing comes before anything that could throw.
        shutter.release("left target ($reason)")
        shutter.detach()
        shutter.setCurrentPackage(null)
        if (gate.isShowing) gate.abandon("left target")
    }

    /**
     * Some apps stop emitting events entirely once backgrounded, so a missed
     * exit would keep accumulating time against an app the user has closed.
     *
     * The watchdog re-checks the real foreground owner every 2 s via
     * [ForegroundProbe] (`UsageStatsManager`, not window content). It runs on
     * `Dispatchers.IO` because each probe is an IPC, and it only runs while a
     * target app is believed to be foreground.
     */
    private fun armWatchdog(pkg: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!checkStillForeground(pkg)) return@launch
            }
        }
    }

    /** One-shot probe, for a TYPE_WINDOWS_CHANGED hint. */
    private fun probeSoon(pkg: String) {
        scope.launch(Dispatchers.IO) { checkStillForeground(pkg) }
    }

    /** @return false when [pkg] has left the foreground. */
    private suspend fun checkStillForeground(pkg: String): Boolean {
        val active = probe.currentForegroundPackage()
        // null means "no information" -- a missing PACKAGE_USAGE_STATS grant
        // must not read as the user leaving every app.
        if (active == null || active == pkg) return true
        withContext(Dispatchers.Main.immediate) {
            if (foregroundPkg == pkg) {
                foregroundPkg = active
                leaveTarget(pkg, "watchdog saw $active")
            }
        }
        return false
    }

    // ------------------------------------------------------------- teardown

    /**
     * `adb shell dumpsys activity service dev.molasses/.monitor.MolassesAccessibilityService`
     *
     * Per-package, per-segment percentile tables. Percentiles rather than a
     * mean: latency distributions are right-skewed and a mean hides exactly
     * the tail that breaks the illusion.
     */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Molasses stall latency (SS6)")
        writer.println("  A pipeline (not optimisable) | B our code | C relayout | D ground truth")
        writer.println()
        writer.print(latency.formatAll())
        writer.println()
        writer.println("targets: $targets")
        writer.println("ready: $ready  foreground: $foregroundPkg")
    }

    override fun onInterrupt() {
        // The system is telling us to stop whatever feedback we are giving.
        // An armed, invisible, touch-eating window is the worst thing to leave
        // behind, so it goes first.
        runCatching { shutter.release("onInterrupt") }
        runCatching { shutter.detach() }
        runCatching { if (gate.isShowing) gate.abandon("onInterrupt") }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        ready = false
        watchdogJob?.cancel()
        checkpointJob?.cancel()
        // Persist before the scope dies, otherwise the last interval is lost
        // and has to be rebuilt by the reconciler.
        runCatching { if (::engine.isInitialized) engine.checkpoint(now()) }
        runCatching { if (::shutter.isInitialized) { shutter.release("teardown"); shutter.detach() } }
        runCatching { if (::gate.isInitialized) gate.dismiss() }
        scope.cancel()
    }

    companion object {
        private const val TAG = "Molasses.Service"
        const val CHECKPOINT_INTERVAL_MS = 15_000L
        const val WATCHDOG_INTERVAL_MS = 2_000L
    }
}
