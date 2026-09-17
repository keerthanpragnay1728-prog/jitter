package dev.molasses.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.core.console.ConsoleIds
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.diag.ServiceHealthPolicy
import dev.molasses.core.latency.LatencyRegistry
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.lease.LaunchGate
import dev.molasses.core.lease.LeaseManager
import dev.molasses.core.lock.LockEnforcement
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.latency.Segment
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.safety.PauseWindow
import dev.molasses.core.safety.SensitivePackages
import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.ForegroundEventRouter
import dev.molasses.core.session.ForegroundSessionTracker
import dev.molasses.core.session.TargetScope
import dev.molasses.core.session.WindowEvent
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.stats.DayUsage
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.time.WallClock
import dev.molasses.core.ui.CycleLine
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.datastore.gateModeFromOrdinal
import dev.molasses.data.datastore.pauseInstant
import dev.molasses.data.datastore.toEngineSnapshot
import dev.molasses.data.datastore.toLease
import dev.molasses.data.datastore.toLock
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.repo.DataStoreEngineStore
import dev.molasses.data.repo.RoomFrictionLedger
import dev.molasses.engine.FrictionEngine
import dev.molasses.overlay.GateOverlayManager
import dev.molasses.overlay.GateStats
import dev.molasses.overlay.LeaseGateOverlayManager
import dev.molasses.overlay.LockOverlayManager
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
    private lateinit var lockOverlay: LockOverlayManager
    private lateinit var leaseGate: LeaseGateOverlayManager
    private var ready = false

    /**
     * The armed locks, refreshed by the settings observer.
     *
     * Volatile because it is written from a coroutine and read from the
     * accessibility callback thread. The registry itself is immutable, so the
     * worst case is a read that is one emission stale, which costs at most one
     * more scroll before a newly armed lock bites.
     */
    @Volatile
    private var locks: LockRegistry = LockRegistry()

    /**
     * The granted leases, refreshed by the settings observer.
     *
     * Volatile for the same reason as [locks], and rebuilt wholesale on every
     * emission rather than diffed. One stale read costs at most one extra
     * gate, which is the direction that fails safely.
     */
    @Volatile
    private var leases: LeaseManager = LeaseManager()

    /** The configured gate mode. Only in force at the terminal tier. */
    @Volatile
    private var gateMode: GatePolicy.GateMode = GatePolicy.GateMode.COUNTDOWN

    /**
     * Consecutive failures to put a gate window on the glass, this visit.
     *
     * Bounds the retry: a device that refuses one window add refuses the
     * next, and this is reached from the scroll path, so an unbounded retry
     * is an addView per frame of a burst. Reset on leaving the target, so the
     * next visit tries again and a transient cause heals itself rather than
     * needing a restart.
     */
    private var gateAttachFailures = 0

    private var targets: Set<String> = emptySet()

    /** Package we currently believe is in the foreground, target or not. */
    private var foregroundPkg: String? = null

    /** Package to display label, for the lock flash. See [labelFor]. */
    private val labels = mutableMapOf<String, String>()

    private var watchdogJob: Job? = null
    private var checkpointJob: Job? = null

    /** SS6. Shared with the shutter, which records B, C and D. */
    private val latency = LatencyRegistry()

    private var bootId = 0
    private var debugOverrideNonce = 0L
    private var previewStallNonce = 0L
    private var disableRequestNonce = 0L

    /**
     * Built lazily: `Context.getPackageName()` is not usable from a field
     * initialiser, before the service has a base context.
     */
    private val router by lazy {
        ForegroundEventRouter(
            ownPackage = packageName,
            launcherClassName = ForegroundEventRouter.LAUNCHER_CLASS_NAME,
        )
    }
    private val sessions = ForegroundSessionTracker()

    /**
     * Window ids belonging to windows this service added.
     *
     * Not a `Set<IBinder>`: an `AccessibilityEvent` exposes `getWindowId()`,
     * an int, and there is no public route from an event to a window token.
     * `View.getWindowToken()` gives us our own tokens but nothing on the event
     * side to compare them against, so the id is the only key available.
     *
     * This used to be refreshed from `getWindows()`. That needs
     * `flagRetrieveInteractiveWindows`, which is gone for banking-app
     * compatibility, and without the flag `getWindows()` returns an empty
     * list. So the set is now *learned*: any event wearing our own package
     * name has its window id recorded here.
     *
     * That is circular only in appearance. The package check in
     * [ForegroundEventRouter] already drops those events on its own; learning
     * the id adds coverage for a later event from the same window that arrives
     * without a usable package name. The guard degrades to the package check
     * alone rather than to nothing, which is why the flag could be removed at
     * all.
     *
     * Bounded and cleared on teardown so a long-lived service cannot grow it
     * without limit.
     */
    private val ownWindowIds = mutableSetOf<Int>()

    /**
     * Debug builds only. Overrides the commanded stall with a constant.
     *
     * Segment D is measured from a scroll to the first touch the sink eats. A
     * varying stall duration changes how many scrolls extend an existing arm
     * rather than starting a new one, which confounds the sample. Pinning the
     * duration makes arm and disarm transitions countable.
     */
    private var pinnedStallMs: Long? = null

    /**
     * Packages Jitter must never draw over: the shipped financial set plus
     * whatever the user has added in settings.
     */
    private var sensitivePrefixes: Set<String> = SensitivePackages.DEFAULT_PREFIXES

    /** When the user last hit "Pause friction". [StampedInstant.UNSET] if never. */
    private var pauseStartedAt: StampedInstant = StampedInstant.UNSET

    private val probe by lazy { ForegroundProbe(this) }

    private val monotonic = MonotonicClock { SystemClock.elapsedRealtime() }
    private val wall = WallClock { System.currentTimeMillis() }

    private fun now() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceDiagnostics.onConnected()

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
            onWindowsChanged = ::onOverlayWindowsChanged,
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
            // Walking is the toll, not the purchase. Clearing it puts the
            // decision panel up with the countdown already spent, so the
            // duration is still chosen in the one place a duration is ever
            // chosen. Granting a fixed lease here instead would have made
            // the walking mode the only path with a second rule for how long
            // a lease lasts.
            onCleared = { pkg -> if (ready) showLeaseGate(pkg, countdownMs = 0, expired = false) },
            // Nothing. No lease was taken, so the next scroll in this package
            // gates again, which is the whole reason the launch check also
            // runs on scroll.
            onAbandoned = { },
            onWindowsChanged = ::onOverlayWindowsChanged,
        )

        lockOverlay = LockOverlayManager(
            service = this,
            windowManager = wm,
            ledger = ledger,
            scope = scope,
            // GLOBAL_ACTION_HOME is the one privileged thing this service can
            // do, and it is the only mechanism an app has to close another
            // app it does not own. Wrapped so the manager holds no service
            // reference and can be reasoned about without one.
            goHome = {
                runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                    .onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME refused", it) }
            },
            onWindowsChanged = ::onOverlayWindowsChanged,
        )

        leaseGate = LeaseGateOverlayManager(
            service = this,
            windowManager = wm,
            ledger = ledger,
            scope = scope,
            monotonicMs = ::now,
            goHome = {
                runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                    .onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME refused", it) }
            },
            onLeaseTaken = ::grantLease,
            onDeclined = { _, _ -> },
            onWindowsChanged = ::onOverlayWindowsChanged,
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

            bootId = outcome.bootId
            cycleStore.current().let {
                debugOverrideNonce = it.debugOverrideNonce
                previewStallNonce = it.previewStallNonce
                disableRequestNonce = it.disableRequestNonce
            }
            buildEngine(outcome.snapshot, outcome.bootId)
            observeSettings()
            startCheckpointing()
            ready = true
            ServiceDiagnostics.onReady()
            Log.i(TAG, "ready: accepting events")
        }

        // The silent-drop watchdog. Reconciliation awaits DataStore, and if
        // DataStore never emits, this service sits bound and discards every
        // event in onAccessibilityEvent's !ready guard with nothing in the
        // log to say so. Five seconds is far longer than reconciliation
        // should take.
        scope.launch {
            delay(ServiceHealthPolicy.STARTUP_GRACE_MS)
            if (!ready) {
                val note = "still not ready ${ServiceHealthPolicy.STARTUP_GRACE_MS}ms after " +
                    "connect; every accessibility event is being dropped. " +
                    "Reconciliation is most likely blocked awaiting DataStore."
                ServiceDiagnostics.startupNote = note
                Log.e(TAG, note)
            }
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
            // Read once at connect and held for the life of the service. A
            // boot cannot happen while this process is alive, so re-reading
            // Settings.Global on every tick would buy nothing and cost a
            // content-provider round trip on the accessibility thread.
            bootIdProvider = { bootId },
            scope = scope,
            // A rollover resets every counter the engine holds. The leases
            // live in the store, so they have to be told: a lease outliving
            // the cycle it was escalating against would hold the first gate
            // of the new cycle off until it expired.
            onCycleRolled = { scope.launch { cycleStore.clearLeases() } },
        )
    }

    private fun observeSettings() {
        scope.launch {
            cycleStore.data.collect { state ->
                val stored = state.targetPackagesList.toList()
                if (TargetScope.usedFallback(stored)) {
                    ServiceDiagnostics.usedTargetFallback = true
                    Log.w(
                        TAG,
                        "stored target list is empty; falling back to DEFAULT_TARGETS. " +
                            "Without this the service would listen to every package and " +
                            "route none of them.",
                    )
                } else {
                    ServiceDiagnostics.usedTargetFallback = false
                }
                val next = TargetScope.resolve(stored, DEFAULT_TARGETS)
                if (next != targets) {
                    targets = next
                    applyTargets(next)
                }

                // Debug state editor. The engine holds per-app state in memory
                // and writes it back at each checkpoint, so an edit to the
                // store alone would be overwritten within 15 s. Rebuilding the
                // engine from the edited snapshot is what makes the edit stick.
                // This drops any open session, which is acceptable for a debug
                // path and would not be for anything else.
                pinnedStallMs = state.debugPinnedStallMs.takeIf { it > 0 }

                sensitivePrefixes =
                    SensitivePackages.resolve(state.sensitivePackagePrefixesList)

                // Rebuilt on every emission rather than diffed. The list is a
                // handful of entries and the registry is immutable, so a
                // second source of truth would cost more than it saves for the
                // one piece of state in this app that must not be possible to
                // disagree about.
                locks = LockRegistry.of(state.locksList.map { it.toLock() })

                // Re-applied on every emission rather than diffed, because
                // applying a horizon request is idempotent: once it is in
                // force, re-applying it changes nothing. That is what lets the
                // stored value be a standing preference instead of a command
                // something has to remember it consumed.
                if (ready) {
                    for ((pkg, horizonMs) in state.appHorizonMsMap) {
                        engine.setHorizon(pkg, horizonMs)
                    }
                }
                leases = LeaseManager.of(state.leasesList.map { it.toLease() })
                gateMode = gateModeFromOrdinal(state.gateModeOrdinal)

                val wasPaused = PauseWindow.isActive(pauseStartedAt, nowStamped())
                pauseStartedAt = state.pauseInstant()
                val nowPaused = PauseWindow.isActive(pauseStartedAt, nowStamped())
                if (nowPaused && !wasPaused && ready) {
                    // Take everything down the instant the user asks, rather
                    // than waiting for the next event to notice.
                    withContext(Dispatchers.Main.immediate) {
                        tearDownOverlays("paused by user")
                    }
                }

                // Settings asked for a stall preview. The sink is a trusted
                // overlay owned by this service, so the request has to land
                // here rather than in the Activity.
                if (state.previewStallNonce != previewStallNonce) {
                    previewStallNonce = state.previewStallNonce
                    if (ready) {
                        withContext(Dispatchers.Main.immediate) {
                            shutter.setCurrentPackage(null)
                            shutter.arm(PREVIEW_STALL_MS)
                        }
                    }
                }

                // "Disable for payments". The one path out of the whole app,
                // and it is deliberately one way: disableSelf cannot be
                // undone from code, so the user goes back through Android
                // Settings. Checked against the nonce captured at connect so
                // a restart cannot replay an old request.
                if (state.disableRequestNonce != disableRequestNonce) {
                    disableRequestNonce = state.disableRequestNonce
                    if (ready) {
                        Log.i(TAG, "disabling self on user request")
                        withContext(Dispatchers.Main.immediate) {
                            tearDownOverlays("disabling")
                            runCatching { disableSelf() }
                                .onFailure { Log.w(TAG, "disableSelf failed", it) }
                        }
                    }
                }

                if (state.debugOverrideNonce != debugOverrideNonce) {
                    debugOverrideNonce = state.debugOverrideNonce
                    if (ready) {
                        Log.i(TAG, "debug state override, rebuilding engine")
                        buildEngine(state.toEngineSnapshot(), bootId)
                    }
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
        // Never null. Null means "every package on the device", which paired
        // with an empty `targets` set gives the worst possible combination:
        // the platform delivers everything and the router ignores all of it.
        // Maximum battery cost, zero behaviour, nothing in the log.
        val names = TargetScope.packageNames(packages, packageName, DEFAULT_TARGETS)
        info.packageNames = names
        ServiceDiagnostics.appliedPackageNames = names.toList()
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
                ServiceDiagnostics.onHeartbeat()
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

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> WindowEvent.Kind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> WindowEvent.Kind.WINDOWS_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> WindowEvent.Kind.VIEW_SCROLLED
            else -> return
        }

        // Learn our own window ids. The router drops these by package anyway;
        // recording the id covers a later event from the same window that
        // arrives without one. Bounded so the set cannot grow unbounded on a
        // service that lives for weeks.
        if (pkg == packageName && ownWindowIds.size < MAX_OWN_WINDOW_IDS) {
            ownWindowIds += event.windowId
        }

        val windowEvent = WindowEvent(
            packageName = pkg,
            className = event.className?.toString(),
            windowId = event.windowId,
            kind = kind,
        )
        val route = router.route(
            windowEvent,
            targets = targets,
            ownWindowIds = ownWindowIds,
        )
        // Counted before dispatch, and counted even when ignored. "No events"
        // and "every event ignored" look identical from outside and have
        // completely different fixes.
        ServiceDiagnostics.recordEvent(windowEvent, route)

        when (route) {
            EventRoute.Ignore -> Unit

            is EventRoute.EnterTarget -> enterTarget(route.pkg)

            EventRoute.ExitToHome -> {
                val id = sessions.openId ?: return
                leaveTarget(id, "launcher")
            }

            EventRoute.ProbeForeground -> {
                val open = sessions.openPkg
                if (open != null) probeSoon(open, sessions.openId)
            }

            is EventRoute.Scroll -> onScroll(route.pkg, event, callbackEntryUptimeMs)
        }
    }

    private fun onScroll(
        pkg: String,
        event: AccessibilityEvent,
        callbackEntryUptimeMs: Long,
    ) {
        // Segment A: how long the platform took to deliver this event.
        // Nothing here can shorten it. It is the number that decides whether
        // the concept is viable at all, as distinct from whether this
        // implementation is good.
        //
        // AccessibilityEvent.getEventTime() is on the uptimeMillis clock, the
        // same one MotionEvent.getEventTime() uses, so the two are directly
        // subtractable. currentTimeMillis is not comparable with either.
        val eventTime = event.eventTime
        if (eventTime > 0) {
            latency.forPackage(pkg).record(Segment.A, callbackEntryUptimeMs - eventTime)
        }

        // The engine is asked either way: accumulated time and tierIndex must
        // advance even while overlays are suppressed, or a pause would be a
        // friction holiday and suppression over a bank would be a free ride.
        // Only the drawing is withheld.
        val decision = engine.onScroll(pkg, now())

        // Reactive backstop for the lock. A lock armed while its app was
        // already in the foreground has no enter event to fire on, and a
        // refused home action would otherwise leave the user scrolling. This
        // polls nothing: it is a map lookup on a path that already does
        // arithmetic, and it only does work when the package is actually
        // locked.
        if (enforceLockIfNeeded(pkg)) return

        if (overlaysSuppressed()) return

        // The launch check runs here too, and this is not belt and braces.
        // The gate can be left unresolved without leaving the app: the
        // walking gate times out, a call takes it down, an addView fails.
        // Without this the user would be inside the app with no lease and no
        // gate until they left and came back, which is a bypass anyone would
        // find within a week. It is a map lookup on a path that already does
        // arithmetic, and it returns immediately once a lease is live.
        if (maybeLaunchGate(pkg)) return

        if (decision.stalls) {
            shutter.arm(
                ms = pinnedStallMs ?: decision.stallMs,
                scrollEventTimeUptimeMs = eventTime,
                callbackEntryUptimeMs = callbackEntryUptimeMs,
                terminal = decision.terminal,
            )
        }
        ServiceDiagnostics.gateShowing = gate.isShowing || leaseGate.isShowing
    }

    /**
     * Show the launch gate if this package is owed one.
     *
     * @return true only when a gate is genuinely on the glass and the caller
     *   should stop. **A gate that could not be drawn returns false**, so the
     *   caller falls through to ordinary friction.
     *
     * That distinction is the whole method. It used to return true as soon as
     * it dispatched, and one failed `addView` then disabled the gate and every
     * stall behind it, permanently, on a healthy service with a correct
     * ledger: the caller returned before arming the shutter, and every later
     * scroll re-entered, re-failed and returned the same way. The rule now
     * lives in `LaunchGate.Outcome`, where it is asserted rather than implied.
     *
     * Reactive, like the lock. Nothing polls for ungated apps and nothing
     * fires without the user having just opened or just scrolled something.
     */
    private fun maybeLaunchGate(pkg: String): Boolean {
        if (leaseGate.isShowing || gate.isShowing || lockOverlay.isShowing) return true

        val now = nowStamped()
        val decision = LaunchGate.decide(
            isTarget = pkg in targets,
            leaseRemainingMs = leases.remainingMs(pkg, now),
            sensitiveForeground = SensitivePackages.isSensitive(foregroundPkg, sensitivePrefixes),
            locked = locks.isLocked(pkg, now),
            paused = PauseWindow.isActive(pauseStartedAt, now),
            leasesTakenThisCycle = engine.leasesTakenThisCycle(pkg),
            configuredMode = gateMode,
            terminal = engine.isTerminal(pkg, now()),
        )
        if (decision !is LaunchGate.Decision.Intercept) return false

        // Stop trying after a few consecutive failures for this visit. Each
        // attempt is a window add, and a device that refuses one refuses the
        // next; retrying per scroll event would be an addView per frame of a
        // burst. Reset on leaving the app, so the next visit tries again and
        // a transient cause heals itself.
        if (gateAttachFailures >= MAX_GATE_ATTACH_FAILURES) return false

        val attached = when (decision.mode) {
            GatePolicy.GateMode.COUNTDOWN ->
                showLeaseGate(pkg, decision.countdownMs, decision.expired)
            // The movement gate is the toll in these two, and the panel
            // follows it. Both paths end in showLeaseGate via onCleared.
            GatePolicy.GateMode.WALK ->
                gate.show(pkg, engine.state.value.perApp[pkg]?.tierIndex ?: 0, false)
            GatePolicy.GateMode.TYPING_ONLY ->
                gate.show(pkg, engine.state.value.perApp[pkg]?.tierIndex ?: 0, true)
        }

        val outcome = LaunchGate.outcome(decision, attached)
        if (outcome is LaunchGate.Outcome.NotDrawn) {
            gateAttachFailures += 1
            // Loud, and only at the boundaries: once when it starts and once
            // when it gives up. A line per scroll event would bury itself.
            if (gateAttachFailures == 1 || gateAttachFailures == MAX_GATE_ATTACH_FAILURES) {
                val note = "gate did not draw for $pkg (${outcome.why}), " +
                    "attempt $gateAttachFailures of $MAX_GATE_ATTACH_FAILURES. " +
                    "Friction is falling through to stalls only."
                ServiceDiagnostics.overlayFailureNote = note
                Log.e(TAG, note)
            }
        } else {
            gateAttachFailures = 0
            ServiceDiagnostics.overlayFailureNote = null
        }

        ServiceDiagnostics.gateShowing = gate.isShowing || leaseGate.isShowing
        return outcome.suppressesFriction
    }

    /**
     * Put the gate up now, and fill in the two numbers the system owns when
     * it answers.
     *
     * THIS CYCLE comes from the engine and is on screen in the first frame.
     * TODAY and OPENS TODAY come from `UsageStatsManager`, which is an IPC and
     * a replay of a day of events, and this method is reached from the
     * accessibility callback thread where that is not allowed. So the gate
     * opens reading `--` for those two and they land a moment later.
     *
     * The alternative was to query first and show second, which would leave
     * the target app on screen and scrollable for however long the query
     * took. A gate that is late is a gate that does not work; a number that
     * is late is a number that says so while it waits.
     */
    private fun showLeaseGate(pkg: String, countdownMs: Long, expired: Boolean): Boolean {
        // Nothing of ours behind a full-screen window. An armed sink under a
        // gate absorbs nothing and would still be armed when the gate came
        // down.
        shutter.release("lease gate")
        shutter.detach()
        val attached = leaseGate.show(
            pkg = pkg,
            label = labelFor(pkg),
            countdownMs = countdownMs,
            expired = expired,
            stats = GateStats(
                todayMs = null,
                cycleMs = engine.state.value.perApp[pkg]?.accumulatedMs,
                opensToday = null,
            ),
        )
        if (!attached) {
            // The app is uncovered. Put the sink back, or the fall-through to
            // ordinary friction would have nothing to arm.
            shutter.setCurrentPackage(pkg)
            if (!overlaysSuppressed()) shutter.attach()
            return false
        }
        scope.launch(Dispatchers.IO) {
            val day = dayUsageFor(pkg)
            withContext(Dispatchers.Main.immediate) {
                leaseGate.updateStats(
                    pkg,
                    GateStats(
                        todayMs = day?.foregroundMs,
                        cycleMs = engine.state.value.perApp[pkg]?.accumulatedMs,
                        opensToday = day?.opens,
                    ),
                )
            }
        }
        return true
    }

    /**
     * A lease was chosen. Two writes that must both happen and are
     * deliberately not one.
     *
     * The store holds the lease, which is wall-clock permission to be here.
     * The engine is told a lease was taken, which moves the ratchet's mark in
     * accumulated time. Neither reads the other, and this method is the only
     * place in the app where both are named in the same breath.
     */
    private fun grantLease(pkg: String, durationMs: Long) {
        if (!ready) return
        val accumulated = engine.state.value.perApp[pkg]?.accumulatedMs ?: 0L
        engine.onLeaseGranted(pkg, durationMs, now())
        // Re-attach: the user is in the app with permission now, and the
        // shutter came down when the gate went up.
        if (!overlaysSuppressed()) {
            shutter.setCurrentPackage(pkg)
            shutter.attach()
        }
        scope.launch {
            cycleStore.grantLease(pkg, nowStamped(), durationMs, accumulated)
        }
    }

    /**
     * Today's foreground total and visit count for one package.
     *
     * Runs on IO: this is an IPC followed by a replay of a day of events. See
     * [DayUsage] for why the raw stream and not the aggregate buckets.
     */
    private fun dayUsageFor(pkg: String): DayUsage.Entry? = runCatching {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
            ?: return null
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endMs = wall.wallMs()
        val events = usm.queryEvents(startOfDay, endMs)
        val event = android.app.usage.UsageEvents.Event()
        val transitions = mutableListOf<DayUsage.Transition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != pkg) continue
            val kind = when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> DayUsage.Kind.RESUMED
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> DayUsage.Kind.PAUSED
                else -> null
            } ?: continue
            transitions += DayUsage.Transition(pkg, kind, event.timeStamp)
        }
        DayUsage.replay(transitions, startOfDay, endMs).entry(pkg)
    }.getOrNull()

    /**
     * True when nothing may be drawn on screen, for any reason.
     *
     * Two reasons, both non-negotiable:
     *
     * 1. A financial app is in the foreground. An overlay sets
     *    `FLAG_WINDOW_IS_OBSCURED` on that app's touches and a hardened
     *    banking or UPI app is entitled to refuse the transaction. Adding
     *    friction is never worth costing someone a payment at a till.
     * 2. The user hit "Pause friction". That is the escape hatch for the case
     *    where a bank warns anyway and they need us quiet right now.
     *
     * Read on the accessibility callback thread, so both halves are plain
     * in-memory reads. [foregroundPkg] is maintained by the watchdog, and
     * [pauseStartedAt] by the settings observer.
     */
    private fun overlaysSuppressed(): Boolean =
        SensitivePackages.isSensitive(foregroundPkg, sensitivePrefixes) ||
            PauseWindow.isActive(pauseStartedAt, nowStamped())

    private fun nowStamped() = StampedInstant(
        wallMs = wall.wallMs(),
        elapsedMs = now(),
        bootId = bootId,
    )

    /**
     * Tear every overlay down immediately, without touching accounting.
     *
     * Distinct from [leaveTarget]: the session stays open and time keeps
     * accruing. This is only about what is on the glass.
     */
    private fun tearDownOverlays(reason: String) {
        shutter.release(reason)
        shutter.detach()
        if (gate.isShowing) gate.abandon(reason)
        leaseGate.dismiss(reason)
    }

    private fun enterTarget(pkg: String) {
        foregroundPkg = pkg
        val id = sessions.open(pkg)
        // Accounting first and unconditionally. Suppression is about the
        // glass, never about the ledger. That holds for a locked app too: the
        // session did open, and a ledger that hid enforced attempts would
        // make the most interesting rows the ones it does not have.
        engine.onForegroundEnter(pkg, now())

        // A locked app never gets a shutter. The user is about to be sent
        // home, and attaching a sink that is torn down in the same breath is
        // churn at best and a leaked armed sink at worst. The watchdog is
        // still armed, because if the home action is refused it is the only
        // thing that will ever close this session.
        if (enforceLockIfNeeded(pkg)) {
            armWatchdog(pkg, id)
            return
        }

        // The gate goes up before anything else of ours, and before the user
        // has scrolled once. That is the whole change: a toll paid at the
        // launch is a toll paid while the answer can still be no, and a gate
        // eleven minutes into a session arrives after the decision it was
        // meant to inform.
        if (maybeLaunchGate(pkg)) {
            armWatchdog(pkg, id)
            return
        }

        shutter.setCurrentPackage(pkg)
        if (!overlaysSuppressed()) shutter.attach()
        armWatchdog(pkg, id)
    }

    /**
     * Queue Bit's one observation, for the next time the console is open.
     *
     * Bit speaks only on the launcher, so an observation made here cannot be
     * delivered here. It goes in the queue and waits, which is why the copy
     * is past tense: by the time anyone reads it the scrolling has stopped.
     *
     * Only past the curve onset, because before that nothing has happened
     * worth remarking on, and only ever once per cycle, because the id is the
     * dedupe key and `ConsoleSpeech` refuses a line it has already said.
     */
    private fun queueSessionNotice(pkg: String) {
        val app = engine.state.value.perApp[pkg] ?: return
        if (app.accumulatedMs < FrictionCurve.onsetMs(app.horizonMs)) return
        val line = ConsoleLine.Notice(
            id = ConsoleIds.SCROLLED,
            args = listOf(CycleLine.duration(app.accumulatedMs), labelFor(pkg)),
        )
        scope.launch { cycleStore.enqueueConsoleLine(line) }
    }

    /**
     * Show the lock message and send the user home, if [pkg] is locked.
     *
     * @return true when the caller should stop. Nothing else of ours belongs
     *   on screen behind a terminal message.
     *
     * Reactive only. There is no poller looking for locked apps, and there
     * deliberately is not one: enforcement that can fire without the user
     * having just done something is a service that closes apps on its own.
     */
    private fun enforceLockIfNeeded(pkg: String): Boolean {
        val now = nowStamped()
        val decision = LockEnforcement.decide(
            remainingMs = locks.remainingMs(pkg, now),
            sensitiveForeground = SensitivePackages.isSensitive(foregroundPkg, sensitivePrefixes),
            paused = PauseWindow.isActive(pauseStartedAt, now),
        )
        if (decision !is LockEnforcement.Decision.Enforce) return false

        val reason = locks.reasonFor(pkg, now) ?: LockReason.BLOCK
        tearDownOverlays("locked")
        lockOverlay.flash(pkg, labelFor(pkg), reason, decision.remainingMs)
        return true
    }

    /**
     * The user-visible name of a package, falling back to the package itself.
     *
     * Cached because this runs on the accessibility callback thread. It is
     * only reached when a lock actually fires, so the lookup never touches the
     * hot path, and the cache is bounded by the target list.
     */
    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrDefault(pkg)
    }

    /**
     * Close one session. Both exit paths call this with the id they saw, so a
     * stale or duplicate call names a session that is already gone and does
     * nothing. Deduplicating on id rather than on timestamp proximity matters
     * because leaving an app and returning inside the watchdog interval is two
     * real sessions, and a proximity window would swallow one of them.
     */
    private fun leaveTarget(id: ForegroundSessionTracker.SessionId, reason: String) {
        val pkg = sessions.close(id) ?: return
        watchdogJob?.cancel()
        watchdogJob = null
        engine.onForegroundExit(pkg, now())
        queueSessionNotice(pkg)
        // Panic path: a leaked armed sink over the launcher is a bricked
        // phone, so releasing comes before anything that could throw.
        shutter.release("left target ($reason)")
        shutter.detach()
        shutter.setCurrentPackage(null)
        if (gate.isShowing) gate.abandon("left target")
        leaseGate.dismiss("left target")
        // A new visit gets a fresh set of attempts. See gateAttachFailures.
        gateAttachFailures = 0
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
    private fun armWatchdog(pkg: String, id: ForegroundSessionTracker.SessionId) {
        watchdogJob?.cancel()
        // Tight while something of ours is drawn, because that is the window
        // in which a switch to a banking app puts an overlay over a payment
        // screen. Bounded in cost: the shutter caps its own arm at 8 s and the
        // gate is user-dismissable, so the fast poll is never the steady state.
        val interval = if (
            shutter.isAttached || gate.isShowing || lockOverlay.isShowing || leaseGate.isShowing
        ) {
            OVERLAY_WATCHDOG_INTERVAL_MS
        } else {
            WATCHDOG_INTERVAL_MS
        }
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(interval)
                if (!checkStillForeground(pkg, id)) return@launch
            }
        }
    }

    /** One-shot probe, for a TYPE_WINDOWS_CHANGED hint. */
    private fun probeSoon(pkg: String, id: ForegroundSessionTracker.SessionId?) {
        if (id == null) return
        scope.launch(Dispatchers.IO) { checkStillForeground(pkg, id) }
    }

    /** @return false when [pkg] has left the foreground. */
    private suspend fun checkStillForeground(
        pkg: String,
        id: ForegroundSessionTracker.SessionId,
    ): Boolean {
        val active = probe.currentForegroundPackage()
        // null means "no information". A missing PACKAGE_USAGE_STATS grant
        // must not read as the user leaving every app.
        if (active == null) return true

        if (active == pkg) {
            // Still in the target app, but the foreground reading is also how
            // a financial app first becomes visible to us: those packages are
            // deliberately absent from packageNames, so no accessibility event
            // ever names them and UsageStats is the only witness.
            return true
        }

        withContext(Dispatchers.Main.immediate) {
            foregroundPkg = active
            if (SensitivePackages.isSensitive(active, sensitivePrefixes)) {
                // Before anything that could throw. An overlay left over a UPI
                // PIN screen is the failure this whole section exists to
                // prevent, and it must come down even if the accounting below
                // fails.
                tearDownOverlays("financial app foreground")
                Log.i(TAG, "overlays suppressed: $active")
            }
            leaveTarget(id, "watchdog saw $active")
        }
        return false
    }

    /**
     * An overlay was added or removed.
     *
     * This used to re-read `getWindows()`. Without
     * `flagRetrieveInteractiveWindows` that returns an empty list, so there is
     * nothing to re-read and the learned [ownWindowIds] set is cleared instead
     * when the screen goes quiet. Clearing on the way down rather than on the
     * way up matters: a window id is reused by the platform, so a stale id
     * held after our overlay is gone would drop a real event from whatever
     * inherits it.
     *
     * Also re-paces the watchdog. While anything of ours is on the glass the
     * cost of being slow to notice a financial app is an overlay sitting over
     * a payment screen, so the poll tightens from 2 s to 400 ms for as long as
     * that lasts.
     */
    private fun onOverlayWindowsChanged() {
        ServiceDiagnostics.gateShowing = gate.isShowing || leaseGate.isShowing
        // The lock flash is one of our windows too. Clearing the id set while
        // it is up would make its own events look like a foreign package.
        if (
            !shutter.isAttached && !gate.isShowing && !lockOverlay.isShowing &&
            !leaseGate.isShowing
        ) {
            ownWindowIds.clear()
        }
        repaceWatchdog()
    }

    /**
     * Restart the watchdog at the interval the current screen state calls for.
     * No-op when nothing is being watched.
     */
    private fun repaceWatchdog() {
        val pkg = sessions.openPkg ?: return
        val id = sessions.openId ?: return
        armWatchdog(pkg, id)
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
        runCatching { leaseGate.dismiss("onInterrupt") }
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
        ServiceDiagnostics.onDestroyed()
        sessions.closeCurrent()
        watchdogJob?.cancel()
        checkpointJob?.cancel()
        // Persist before the scope dies, otherwise the last interval is lost
        // and has to be rebuilt by the reconciler.
        runCatching { if (::engine.isInitialized) engine.checkpoint(now()) }
        runCatching { if (::shutter.isInitialized) { shutter.release("teardown"); shutter.detach() } }
        runCatching { if (::gate.isInitialized) gate.dismiss() }
        // The flash dismisses itself after its hold, so this only matters when
        // the service dies mid-hold. Without it that window outlives the
        // process that can remove it.
        runCatching { if (::lockOverlay.isInitialized) lockOverlay.dismiss("teardown") }
        scope.cancel()
    }

    companion object {
        private const val TAG = "Molasses.Service"
        const val CHECKPOINT_INTERVAL_MS = 15_000L
        const val WATCHDOG_INTERVAL_MS = 2_000L

        /**
         * Watchdog interval while any overlay of ours is on the glass. Bounds
         * how long a stall sink can sit over a banking app the user has just
         * switched to.
         */
        const val OVERLAY_WATCHDOG_INTERVAL_MS = 400L

        /**
         * Cap on the learned own-window set. We show at most a handful of
         * windows; anything beyond this is a leak, and dropping the surplus is
         * safe because the package check carries the guard regardless.
         */
        const val MAX_OWN_WINDOW_IDS = 32

        /** Length of the settings-screen stall preview. */
        const val PREVIEW_STALL_MS = 1_000L

        /**
         * Window adds to attempt per visit before falling through to stalls
         * alone.
         *
         * Three rather than one, because a single failure can be a token that
         * went stale in the moment between the decision and the add. Three
         * rather than forever, because this is reached from the scroll path
         * and a device that refuses a window add refuses the next one too.
         */
        const val MAX_GATE_ATTACH_FAILURES = 3
    }
}
