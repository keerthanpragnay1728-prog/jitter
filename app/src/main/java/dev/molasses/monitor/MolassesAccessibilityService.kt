package dev.molasses.monitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.BuildConfig
import dev.molasses.core.console.ConsoleIds
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.diag.ServiceHealthPolicy
import dev.molasses.core.latency.LatencyRegistry
import dev.molasses.core.lease.GateHandover
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.lease.LaunchGate
import dev.molasses.core.lease.LeaseExpiryCheck
import dev.molasses.core.lease.LeaseManager
import dev.molasses.core.lock.LockEnforcement
import dev.molasses.core.lock.GateBlock
import dev.molasses.core.lock.LockOpensAt
import dev.molasses.core.lock.LockRequest
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.latency.Segment
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.FrictionDecision
import dev.molasses.core.safety.HomeFirst
import dev.molasses.core.safety.HomeFirstWatch
import dev.molasses.core.safety.PauseWindow
import dev.molasses.core.safety.SensitivePackages
import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.ForegroundEventRouter
import dev.molasses.core.session.ForegroundSessionTracker
import dev.molasses.core.session.ScrollProxy
import dev.molasses.core.session.TargetScope
import dev.molasses.core.session.WindowEvent
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.NextScroll
import dev.molasses.core.stats.DayUsage
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.time.WallClock
import dev.molasses.core.ui.CycleLine
import dev.molasses.core.ui.FontScale
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
import dev.molasses.overlay.CallDetector
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
     * The granted leases. **Owned here, persisted to the store, never read
     * back from it after connect.**
     *
     * Volatile because it is written on the accessibility callback thread and
     * read there too, with the connect-time seed arriving from a coroutine.
     *
     * ## Why the observer no longer assigns this
     * It used to, on every store emission, and that was wrong twice over.
     *
     * The first version was wrong because a grant reached the store through a
     * DataStore write, so for the length of that write `maybeLaunchGate` saw a
     * package with no lease and one more lease taken than before, and gated
     * again one escalation step higher. The field carried a note saying a
     * stale read cost at most one extra gate, which was the direction that
     * failed safely. An extra gate is not free: it ends in an extra lease
     * taken, and `GateCountdown` counts leases taken.
     *
     * The fix for that applied the grant in memory and persisted behind it,
     * and it did not work, because the note on it was wrong in the same shape
     * as the note it replaced. It said the observer's later assignment was a
     * no-op, since applying the same grant twice is idempotent. That holds
     * only for the emission carrying that write. `FrictionEngine.publish()`
     * writes a snapshot too, `onLeaseGranted` calls it, and its write is
     * issued from a parked channel receiver while the lease write still has a
     * coroutine to schedule. So the engine's write lands first, its emission
     * carries the lease list as it stands on disk, and the observer replaced
     * the whole registry with a copy that predates the grant. The grant erased
     * itself, through the engine, on its own call stack.
     *
     * Idempotency cannot save that, because the observer does not apply a
     * grant. It assigns a registry wholesale, and a wholesale assignment from
     * any snapshot loses whatever is newer than that snapshot.
     *
     * ## So ownership rather than ordering
     * This service is the only writer of stored leases: [grantLease] and the
     * rollover clear, both below. Nothing outside it grants, clears or prunes.
     * So the store is durability and this field is the truth, seeded once at
     * connect and mutated only here. There is then no snapshot that can be
     * stale with respect to it, because nothing reads a snapshot.
     *
     * The cost is real and is the reason the seed's placement is load bearing:
     * a reseed on every emission also healed anything that went wrong, and
     * that safety net is gone. If the connect-time seed does not run, leases
     * do not survive a process death at all.
     */
    @Volatile
    private var leases: LeaseManager = LeaseManager()

    /**
     * The one pending lease-expiry check, or null. Main thread only: it is
     * armed from the grant and the enter path and fires into
     * `maybeLaunchGate`, all of which run there. A Handler rather than
     * AlarmManager: it only matters while this process is alive and the
     * target is open, and it re-reads the lease on elapsed time when it
     * fires. See [LeaseExpiryCheck].
     */
    private val leaseExpiryHandler = Handler(Looper.getMainLooper())
    private var leaseExpiryCheck: Runnable? = null

    /** The configured gate mode. Only in force at the terminal tier. */
    @Volatile
    private var gateMode: GatePolicy.GateMode = GatePolicy.GateMode.COUNTDOWN

    /**
     * The chosen text size, for the windows this service owns.
     *
     * Held here because the overlay managers are constructed once and shown
     * many times, so each one reads this through a lambda at the moment it
     * composes rather than capturing it. Seeded at the default so a window
     * shown before the first store emission is ordinary rather than absent.
     */
    private var fontScaleMultiplier: Float = FontScale.DEFAULT.multiplier

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

    /** The call precondition for attaching a gate. See `LaunchGate.decide`. */
    private val calls by lazy { CallDetector(this) }

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
        // Before any overlay exists. The music-stream mute is gone, but a
        // tester on the build that had it may have a stream still muted by
        // us. Expiring: see LegacyMuteRestore.
        LegacyMuteRestore.restoreOnConnect(this)

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
            //
            // The walking gate sent the app home, so this panel runs over the
            // launcher too, and a lease taken on it relaunches the app:
            // homeFirst = true.
            onCleared = { pkg -> if (ready) showLeaseGate(pkg, countdownMs = 0, expired = false, homeFirst = true) },
            // Nothing. No lease was taken, so the next scroll in this package
            // gates again, which is the whole reason the launch check also
            // runs on scroll.
            onAbandoned = { },
            goHome = ::goHomeQuietly,
            fontScale = { fontScaleMultiplier },
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
            fontScale = { fontScaleMultiplier },
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
            onBlock = ::blockFromGate,
            relaunch = ::relaunchTarget,
            fontScale = { fontScaleMultiplier },
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
                // The only time leases are read off the store. See [leases].
                //
                // Here and not earlier because reconciliation may write, and
                // here and not later because the next line builds the engine
                // and the one after that opens the event gate. A lease that
                // survived a process death has to be in hand before the first
                // event is accepted, or the first launch after a restart gates
                // an app the user already paid for, which is the promise
                // LeaseManager's doc makes about surviving process death.
                leases = LeaseManager.of(it.leasesList.map { entry -> entry.toLease() })
            }
            // On main, with the ready flag, because the engine is confined
            // there (see [engineConfinement]) and because `ready` is read by
            // onAccessibilityEvent on main with no other barrier.
            withContext(Dispatchers.Main.immediate) {
                buildEngine(outcome.snapshot, outcome.bootId)
            }
            observeSettings()
            startCheckpointing()
            withContext(Dispatchers.Main.immediate) {
                ready = true
                // A lease that survived a process death, on an app that is
                // open again, gets its check back. See LeaseExpiryCheck.
                val open = sessions.openPkg
                val remaining = open?.let { leases.remainingMs(it, nowStamped()) } ?: 0L
                applyLeaseExpiryPlan(LeaseExpiryCheck.onConnect(open, remaining), "connect")
            }
            ServiceDiagnostics.onReady()
            Log.i(TAG, "ready: accepting events")
            // $ rem. Alarms do not survive a reboot or a force stop, and the
            // service connecting is the one moment this app reliably runs
            // after either. Past-due reminders fire now, marked late.
            ReminderAlarms.rescheduleAll(this@MolassesAccessibilityService, cycleStore, nowStamped())
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

    /**
     * The engine's thread confinement. Every call into [engine] is made on the
     * main thread: the accessibility callbacks are already there, and the four
     * callers that were not (construction at connect, the debug rebuild, the
     * horizon re-apply in the settings observer and the checkpoint tick) hop
     * there with `withContext(Dispatchers.Main.immediate)`. Confinement rather
     * than a lock, because [FrictionEngine.onScroll] is on the stall's latency
     * path and none of the hops are.
     *
     * Debug builds assert it on every engine call, so a new off-thread caller
     * crashes in testing instead of racing on a device. Release checks
     * nothing, since a check there could only crash a user's phone.
     * `EngineConfinementWiringTest` reads every engine call site.
     */
    private val engineConfinement: () -> Unit =
        if (BuildConfig.DEBUG) {
            {
                check(Looper.getMainLooper().isCurrentThread) {
                    "FrictionEngine called off the main thread, on ${Thread.currentThread().name}"
                }
            }
        } else {
            {}
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
            confinement = engineConfinement,
            // A rollover resets every counter the engine holds. The leases
            // live in the store, so they have to be told: a lease outliving
            // the cycle it was escalating against would hold the first gate
            // of the new cycle off until it expired.
            //
            // In memory first, disk behind it, for the same reason [leases]
            // gives. The engine clears `leasesTaken` in this call stack, so
            // between here and the store's emission the registry would hold a
            // lease the new cycle never issued, and `LaunchGate.decide` would
            // read it as "lease active" and pass. That is the mirror of the
            // double gate and the worse half of it: that one charged a rung
            // too many, this one skips the first gate of a cycle.
            //
            // Reached from the accessibility callback thread, through
            // `onScroll` and `onForegroundEnter`, so the assignment is
            // ordinary in-memory work on the thread that reads it.
            onCycleRolled = {
                leases = LeaseManager()
                cancelLeaseExpiry("rollover")
                scope.launch { cycleStore.clearLeases() }
            },
        )
    }

    private fun observeSettings() {
        scope.launch {
            cycleStore.data.collect { state ->
                val selection = TargetScope.Selection(
                    stored = state.targetPackagesList.toList(),
                    chosen = state.targetsChosen,
                )
                if (TargetScope.usedFallback(selection)) {
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
                val next = TargetScope.resolve(selection, DEFAULT_TARGETS)
                if (next != targets) {
                    targets = next
                    applyTargets(next)
                }

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
                    withContext(Dispatchers.Main.immediate) {
                        for ((pkg, horizonMs) in state.appHorizonMsMap) {
                            engine.setHorizon(pkg, horizonMs)
                        }
                    }
                }
                // Leases are deliberately absent here. The service owns them
                // and the store only persists them; see [leases] for what
                // assigning them from a snapshot cost.
                gateMode = gateModeFromOrdinal(state.gateModeOrdinal)
                // Off the same emission as everything else here. The overlays
                // are the surface where this matters most: a user cannot
                // pinch, scroll or dismiss a gate to cope with text that is
                // too small for them.
                fontScaleMultiplier = FontScale.fromOrdinal(state.fontScaleOrdinal).multiplier

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

                // Debug state editor. The engine holds per-app state in memory
                // and writes it back at each checkpoint, so an edit to the
                // store alone would be overwritten within 15 s. Rebuilding the
                // engine from the edited snapshot is what makes the edit stick.
                // This drops any open session, which is acceptable for a debug
                // path and would not be for anything else.
                if (state.debugOverrideNonce != debugOverrideNonce) {
                    debugOverrideNonce = state.debugOverrideNonce
                    if (ready) {
                        Log.i(TAG, "debug state override, rebuilding engine")
                        withContext(Dispatchers.Main.immediate) {
                            buildEngine(state.toEngineSnapshot(), bootId)
                        }
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
        val names = TargetScope.packageNames(packages, packageName)
        info.packageNames = names
        ServiceDiagnostics.appliedPackageNames = names.toList()
        // TYPE_WINDOW_CONTENT_CHANGED feeds the scroll proxy and nothing
        // else. With canRetrieveWindowContent false no node or content comes
        // with it; the proxy reads its package, time and change-type int.
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
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
                withContext(Dispatchers.Main.immediate) {
                    val pkg = foregroundPkg
                    if (pkg != null && pkg in targets) engine.checkpoint(now())
                }
            }
        }
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Taken first: segment B is measured from here, and anything done
        // before this read is silently excluded from "our code".
        val callbackEntryUptimeMs = SystemClock.uptimeMillis()
        if (!ready) {
            if (event.packageName == DIAG_PACKAGE) Log.i(SERVICE_TAG, "event pkg=$DIAG_PACKAGE dropped: service not ready")
            return
        }
        val pkg = event.packageName?.toString() ?: return

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> WindowEvent.Kind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> WindowEvent.Kind.WINDOWS_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> WindowEvent.Kind.VIEW_SCROLLED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> WindowEvent.Kind.CONTENT_CHANGED
            else -> return
        }

        val windowEvent = WindowEvent(
            packageName = pkg,
            className = event.className?.toString(),
            windowId = event.windowId,
            kind = kind,
        )
        val route = router.route(windowEvent, targets = targets)
        // Counted before dispatch, and counted even when ignored. "No events"
        // and "every event ignored" look identical from outside and have
        // completely different fixes.
        ServiceDiagnostics.recordEvent(windowEvent, route)
        // Content changes are too many to log one by one; the proxy logs the
        // ones that pass.
        if (pkg == DIAG_PACKAGE && kind != WindowEvent.Kind.CONTENT_CHANGED) {
            Log.i(SERVICE_TAG, "event pkg=$pkg type=$kind route=${routeLabel(route)}")
        }

        when (route) {
            is EventRoute.Ignore -> Unit

            is EventRoute.EnterTarget -> enterTarget(route.pkg)

            EventRoute.ExitToHome -> {
                val id = sessions.openId ?: return
                leaveTarget(id, "launcher")
            }

            EventRoute.ProbeForeground -> {
                val open = sessions.openPkg
                if (open != null) probeSoon(open, sessions.openId)
            }

            is EventRoute.Scroll -> {
                // A real scroll: the proxy is off for this package this session.
                scrollProxy[route.pkg] = ScrollProxy.onRealScroll(scrollProxy[route.pkg] ?: ScrollProxy.State())
                onScroll(route.pkg, event.eventTime, callbackEntryUptimeMs)
            }

            is EventRoute.ContentChanged -> onContentChanged(route.pkg, event, callbackEntryUptimeMs)
        }
    }

    /** @return the stall the shutter armed, or 0 when nothing armed. */
    private fun onScroll(
        pkg: String,
        eventTime: Long,
        callbackEntryUptimeMs: Long,
        viaProxy: Boolean = false,
    ): Long {
        // Segment A: how long the platform took to deliver this event.
        // Nothing here can shorten it. It is the number that decides whether
        // the concept is viable at all, as distinct from whether this
        // implementation is good.
        //
        // AccessibilityEvent.getEventTime() is on the uptimeMillis clock, the
        // same one MotionEvent.getEventTime() uses, so the two are directly
        // subtractable. currentTimeMillis is not comparable with either.
        if (eventTime > 0 && !viaProxy) {
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
        val diag = pkg == DIAG_PACKAGE
        if (enforceLockIfNeeded(pkg, atEntry = false)) {
            if (diag) logScroll(pkg, decision, armed = false, why = "lock enforced", viaProxy)
            return 0L
        }

        if (overlaysSuppressed()) {
            if (diag) logScroll(pkg, decision, armed = false, why = "overlays suppressed (sensitive foreground or pause)", viaProxy)
            return 0L
        }

        // The launch check runs here too, and this is not belt and braces.
        // The gate can be left unresolved without leaving the app: the
        // walking gate times out, a call takes it down, an addView fails.
        // Without this the user would be inside the app with no lease and no
        // gate until they left and came back, which is a bypass anyone would
        // find within a week. It is a map lookup on a path that already does
        // arithmetic, and it returns immediately once a lease is live.
        if (maybeLaunchGate(pkg)) {
            if (diag) logScroll(pkg, decision, armed = false, why = "the launch gate owns this scroll", viaProxy)
            return 0L
        }

        var armedMs = 0L
        if (decision.stalls) {
            val armed = shutter.arm(
                ms = decision.stallMs,
                // Zero for the proxy: a content change is not a scroll, and
                // its time must not enter the scroll latency tables.
                scrollEventTimeUptimeMs = if (viaProxy) 0L else eventTime,
                callbackEntryUptimeMs = callbackEntryUptimeMs,
                terminal = decision.terminal,
            )
            if (armed) armedMs = decision.stallMs
            if (diag) logScroll(pkg, decision, armed, why = if (armed) "armed" else "shutter refused (not attached, or a call)", viaProxy)
        } else if (diag) {
            logScroll(
                pkg,
                decision,
                armed = false,
                why = if (decision.curveStallMs == 0) "curve: no stall at this point" else "curve stalls, roll missed",
                viaProxy,
            )
        }
        ServiceDiagnostics.gateShowing = gate.isShowing || leaseGate.isShowing
        return armedMs
    }

    /**
     * The scroll proxy. A qualifying content change from a package that has
     * sent no real scroll this session goes through the scroll path, at most
     * once a second and at most once per stall window. See [ScrollProxy].
     * Reads the change-type int on the event and nothing else of it.
     */
    private val scrollProxy = HashMap<String, ScrollProxy.State>()

    private fun onContentChanged(pkg: String, event: AccessibilityEvent, callbackEntryUptimeMs: Long) {
        if (!ScrollProxy.qualifies(event.contentChangeTypes)) return
        val state = scrollProxy[pkg] ?: ScrollProxy.State()
        val nowMs = now()
        if (!ScrollProxy.mayPass(state, nowMs)) return
        val armedMs = onScroll(pkg, event.eventTime, callbackEntryUptimeMs, viaProxy = true)
        scrollProxy[pkg] = ScrollProxy.onPassed(state, nowMs, armedMs)
        Log.i(SERVICE_TAG, "proxy scroll pkg=$pkg armed=${armedMs}ms (content change, no real scroll this session)")
    }

    /**
     * One line per scroll from [DIAG_PACKAGE]: what the curve commanded, and
     * whether the shutter armed, and if not, which branch stopped it.
     */
    private fun logScroll(pkg: String, decision: FrictionDecision, armed: Boolean, why: String, viaProxy: Boolean) {
        Log.i(
            SERVICE_TAG,
            "${if (viaProxy) "proxy " else ""}scroll pkg=$pkg curveStall=${decision.curveStallMs}ms p=${decision.probability} " +
                "rolled=${decision.stallMs}ms terminal=${decision.terminal} armed=${if (armed) "yes" else "no"} ($why)",
        )
    }

    private fun routeLabel(route: EventRoute): String = when (route) {
        is EventRoute.Ignore -> "ignore(${route.reason})"
        is EventRoute.EnterTarget -> "enter"
        EventRoute.ExitToHome -> "exitHome"
        EventRoute.ProbeForeground -> "probe"
        is EventRoute.Scroll -> "scroll"
        is EventRoute.ContentChanged -> "content"
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
        // Per package. A gate on screen counts only when it is this app's;
        // another app's comes down and this one gets its own decision. See
        // GateHandover for how A's gate used to let B through.
        val anyShowing = leaseGate.isShowing || gate.isShowing || lockOverlay.isShowing
        val owner = leaseGate.showingFor ?: gate.showingFor ?: lockOverlay.showingFor
        when (val handover = GateHandover.forGate(anyShowing, owner, pkg)) {
            GateHandover.Action.AlreadyOwn -> return true
            is GateHandover.Action.ReleaseFirst -> dismissGateWindows("replaced by $pkg (was ${handover.owner})")
            GateHandover.Action.Decide -> Unit
        }

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
            // whenUnknown = false, the lease gate's answer and for its
            // reason: a detector that cannot tell must not open every
            // target app free with nothing on screen saying so.
            inCall = calls.inProgress(whenUnknown = false),
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
    private fun showLeaseGate(
        pkg: String,
        countdownMs: Long,
        expired: Boolean,
        homeFirst: Boolean = HomeFirst.sendsHome(HomeFirst.leaseGate(expired)),
    ): Boolean {
        // Nothing of ours behind a full-screen window. An armed sink under a
        // gate absorbs nothing and would still be armed when the gate came
        // down.
        shutter.release("lease gate")
        shutter.detach()
        // Read here rather than in the overlay, because the curve is read
        // against true time plus the ratchet's penalty and only the engine
        // holds both. An overlay given accumulated time alone would quote a
        // price lower than the one the next scroll will actually pay, on the
        // screen that exists to stop the app being vague about its numbers.
        val app = engine.state.value.perApp[pkg]
        val nextScroll = NextScroll.readingAt(
            accumulatedMs = app?.accumulatedMs ?: 0L,
            penaltyMs = app?.penaltyMs ?: 0L,
            horizonMs = app?.horizonMs ?: FrictionCurve.DEFAULT_HORIZON_MS,
        )
        val attached = leaseGate.show(
            pkg = pkg,
            label = labelFor(pkg),
            countdownMs = countdownMs,
            expired = expired,
            stats = GateStats(
                todayMs = null,
                cycleMs = app?.accumulatedMs,
                opensToday = null,
            ),
            nextScroll = nextScroll,
            homeFirst = homeFirst,
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

    // ------------------------------------------------------- lease expiry

    /** Schedule [pkg]'s check if it holds a live lease, replacing any pending one. */
    private fun armLeaseExpiry(pkg: String, why: String) {
        cancelLeaseExpiry("rearm: $why")
        applyLeaseExpiryPlan(LeaseExpiryCheck.plan(pkg, leases.remainingMs(pkg, nowStamped())), why)
    }

    private fun applyLeaseExpiryPlan(plan: LeaseExpiryCheck.Plan, why: String) {
        if (plan is LeaseExpiryCheck.Plan.Schedule) scheduleLeaseExpiry(plan.pkg, plan.delayMs, why)
    }

    private fun scheduleLeaseExpiry(pkg: String, delayMs: Long, why: String) {
        val check = Runnable { onLeaseExpiryCheck(pkg) }
        leaseExpiryCheck = check
        leaseExpiryHandler.postDelayed(check, delayMs)
        Log.i(LEASE_EXPIRY_TAG, "scheduled pkg=$pkg delay=${delayMs}ms ($why)")
    }

    private fun cancelLeaseExpiry(reason: String) {
        val check = leaseExpiryCheck ?: return
        leaseExpiryHandler.removeCallbacks(check)
        leaseExpiryCheck = null
        Log.i(LEASE_EXPIRY_TAG, "cancelled ($reason)")
    }

    /** The check fired. Re-reads the open session and the lease; trusts neither the schedule nor its time. */
    private fun onLeaseExpiryCheck(pkg: String) {
        leaseExpiryCheck = null
        if (!ready) {
            Log.i(LEASE_EXPIRY_TAG, "fired pkg=$pkg stillOpen=unknown gateRaised=no (not ready)")
            return
        }
        val open = sessions.openPkg
        when (val fire = LeaseExpiryCheck.onFire(pkg, open, leases.remainingMs(pkg, nowStamped()))) {
            LeaseExpiryCheck.Fire.RaiseGate -> {
                // The ordinary path, the one a scroll takes, including its
                // suppression check: a sensitive app or a pause still wins.
                val raised = !overlaysSuppressed() && maybeLaunchGate(pkg)
                Log.i(LEASE_EXPIRY_TAG, "fired pkg=$pkg stillOpen=yes gateRaised=${if (raised) "yes" else "no"}")
            }
            is LeaseExpiryCheck.Fire.Reschedule -> {
                Log.i(LEASE_EXPIRY_TAG, "fired pkg=$pkg stillOpen=yes gateRaised=no (lease has ${fire.delayMs}ms left)")
                scheduleLeaseExpiry(pkg, fire.delayMs, "time left at fire")
            }
            is LeaseExpiryCheck.Fire.Skip ->
                Log.i(LEASE_EXPIRY_TAG, "fired pkg=$pkg stillOpen=no gateRaised=no (open=$open)")
        }
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
        val now = nowStamped()
        engine.onLeaseGranted(pkg, durationMs, now())
        // In memory first, disk behind it. The next read of [leases] is the
        // one `maybeLaunchGate` makes on the event that our own gate window
        // produces by closing, and it arrives long before a DataStore write
        // can. Without this line that read sees no lease, gates again, and
        // charges a second rung for one decision. See [leases].
        //
        // No timer and no grace window. A window in which the gate declines
        // to fire is a bypass, and it would paper over an ordering rather
        // than fix it.
        leases = leases.grant(pkg, now, durationMs, accumulated)
        // One check at the deadline, so an expiry is noticed without a
        // scroll. Replaces any check already pending.
        armLeaseExpiry(pkg, "new grant")
        // Re-attach: the user is in the app with permission now, and the
        // shutter came down when the gate went up.
        if (!overlaysSuppressed()) {
            shutter.setCurrentPackage(pkg)
            shutter.attach()
        }
        scope.launch {
            cycleStore.grantLease(pkg, now, durationMs, accumulated)
        }
    }

    /**
     * [ BLOCK THIS APP ] on the LEASE EXPIRED gate.
     *
     * The same evaluation as every other way of arming a lock, then the same
     * registry write: in memory first, so the lock screen that follows can
     * read it, and the store behind it through `armLock`, the call CFG and
     * the console make. Then the lock screen, through the ordinary
     * enforcement path, which is what closes the session and puts it up.
     *
     * TooShort means a longer lock already stands; the user asked to be kept
     * out, and they are, so the lock screen goes up for the lock that is
     * there. Confirm and Invalid cannot come from the rungs this surface
     * offers (see `GateBlock`); if one ever did, nothing is armed unconfirmed
     * and the user is sent home rather than left in the app.
     */
    private fun blockFromGate(pkg: String, durationMs: Long) {
        if (!ready) return
        val now = nowStamped()
        when (val verdict = GateBlock.evaluate(durationMs, locks.remainingMs(pkg, now))) {
            is LockRequest.Verdict.Arm -> {
                locks = locks.arm(pkg, now, verdict.durationMs, LockReason.BLOCK)
                scope.launch { cycleStore.armLock(pkg, now, verdict.durationMs, LockReason.BLOCK) }
                if (!enforceLockIfNeeded(pkg, atEntry = false)) goHomeQuietly()
            }
            is LockRequest.Verdict.TooShort -> if (!enforceLockIfNeeded(pkg, atEntry = false)) goHomeQuietly()
            is LockRequest.Verdict.Confirm, LockRequest.Verdict.Invalid -> goHomeQuietly()
        }
    }

    private fun logSurvived(overlay: String, pkg: String, reason: String) {
        Log.i(HOME_FIRST_TAG, "$overlay survived the launcher exit of $pkg ($reason)")
    }

    /**
     * After a lease is taken on a home-first gate: bring the app back.
     * getLaunchIntentForPackage restores its existing task rather than
     * starting a new one. An accessibility service may start an activity from
     * the background.
     */
    private fun relaunchTarget(pkg: String): Boolean = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.onFailure { Log.w(HOME_FIRST_TAG, "relaunch of $pkg failed", it) }.getOrDefault(false)

    private fun goHomeQuietly() {
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            .onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME refused", it) }
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
     *
     * The one way every window this service owns comes down: pause, lock,
     * disable, a financial app in front, and the three lifecycle exits,
     * [onInterrupt], [onUnbind] and [onDestroy]. Teardown used to have its
     * own list and it had dropped the lease gate, which left a focusable
     * window and its audio focus behind; [onInterrupt] had dropped the lock
     * overlay. One list cannot disagree with itself.
     *
     * Audio focus is released inside each manager's own dismiss, at the
     * choke point every one of its exits runs through, so dismissing here is
     * releasing. Each step is separate and caught: a manager that is not
     * initialised yet, not showing, or whose removeView throws must not stop
     * the next one coming down. `OverlayTeardownWiringTest` holds this list
     * against the manager fields.
     */
    private fun tearDownOverlays(reason: String) {
        overlayStep("shutter release") { if (::shutter.isInitialized) shutter.release(reason) }
        overlayStep("shutter detach") { if (::shutter.isInitialized) shutter.detach() }
        overlayStep("gate") {
            if (::gate.isInitialized) {
                if (gate.isShowing) gate.abandon(reason)
                gate.dismiss()
            }
        }
        overlayStep("lease gate") { if (::leaseGate.isInitialized) leaseGate.dismiss(reason) }
        overlayStep("lock overlay") { if (::lockOverlay.isInitialized) lockOverlay.dismiss(reason) }
    }

    /**
     * The three full-screen windows only, for a gate handed from one package
     * to another. The shutter stays: it follows the current package and is
     * re-pointed by the caller.
     */
    private fun dismissGateWindows(reason: String) {
        overlayStep("gate") {
            if (gate.isShowing) gate.abandon(reason)
            gate.dismiss()
        }
        overlayStep("lease gate") { leaseGate.dismiss(reason) }
        overlayStep("lock overlay") { lockOverlay.dismiss(reason) }
    }

    private inline fun overlayStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "overlay teardown: $name failed", e)
        }
    }

    private fun enterTarget(pkg: String) {
        // A direct switch from another target closes that session first, as
        // a trip through the launcher would have: its accounting, its
        // watchdog and its windows. Without it A's gate stayed up over B.
        val open = sessions.openId
        if (open != null && GateHandover.mustCloseFirst(sessions.openPkg, pkg)) {
            leaveTarget(open, "switched to $pkg")
        }
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
        if (enforceLockIfNeeded(pkg, atEntry = true)) {
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
        // Let through, and possibly on a lease still running from an earlier
        // visit: that lease needs its expiry check again, since leaving
        // cancelled it. A no-op without a live lease.
        armLeaseExpiry(pkg, "enter")

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
    /**
     * @param atEntry the app is opening, rather than already in use. Passed
     *   to the lock screen for the media pause decision only.
     */
    private fun enforceLockIfNeeded(pkg: String, atEntry: Boolean): Boolean {
        val now = nowStamped()
        val decision = LockEnforcement.decide(
            remainingMs = locks.remainingMs(pkg, now),
            sensitiveForeground = SensitivePackages.isSensitive(foregroundPkg, sensitivePrefixes),
            paused = PauseWindow.isActive(pauseStartedAt, now),
        )
        if (decision !is LockEnforcement.Decision.Enforce) return false

        val reason = locks.reasonFor(pkg, now) ?: LockReason.BLOCK
        tearDownOverlays("locked")
        // The session ends here, and it has to end here now that the message
        // is not on a timer.
        //
        // Our overlay is a window rather than an activity, so the locked app
        // is still the foreground package behind it, and the checkpoint loop
        // credits the foreground package every tick. While this screen lived
        // 1.8 s that was at most one tick. Left until the user presses the
        // way out, it would accrue against the app for as long as they sat
        // reading why they cannot use it, which is the opposite of what a
        // lock means.
        //
        // Closing it is also the honest model rather than a workaround: the
        // lock ended the session, so the LOCK_ENFORCED row is the last thing
        // in it rather than something sitting in the middle of one.
        sessions.openId?.let { leaveTarget(it, "locked") }
        lockOverlay.flash(
            pkg = pkg,
            label = labelFor(pkg),
            reason = reason,
            remainingMs = decision.remainingMs,
            // From the stored lock through the restriction clamp, never the
            // requested duration added to now. See LockOpensAt.
            opensAtWallMs = LockOpensAt.wallMs(locks, pkg, now) ?: (now.wallMs + decision.remainingMs),
            atEntry = atEntry,
        )
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
        // A new session starts without knowing whether this app scrolls.
        scrollProxy.remove(pkg)
        cancelLeaseExpiry("left $pkg ($reason)")
        watchdogJob?.cancel()
        watchdogJob = null
        engine.onForegroundExit(pkg, now())
        queueSessionNotice(pkg)
        // Panic path: a leaked armed sink over the launcher is a bricked
        // phone, so releasing comes before anything that could throw.
        shutter.release("left target ($reason)")
        shutter.detach()
        shutter.setCurrentPackage(null)
        // A home-first overlay sent this app home itself, so this exit is
        // its own doing and it stays up over the launcher. Everything above
        // (the session, accumulation, the ledger row) has still happened.
        // See HomeFirst.
        if (gate.isShowing) {
            if (gate.homeFirst) logSurvived("walk gate", pkg, reason) else gate.abandon("left target")
        }
        if (leaseGate.homeFirst) logSurvived("lease gate", pkg, reason) else leaseGate.dismiss("left target")
        // The lock message goes too, and it did not have to before. It used
        // to take itself down 1.8 s after it appeared, so by the time any of
        // this ran it was already gone. It now stays until the user acts, and
        // a user who presses home themselves, or switches to another app,
        // would otherwise arrive at the launcher with a full-screen lock
        // notice still over it. Unless it sent the app home itself.
        if (lockOverlay.homeFirst) logSurvived("lock overlay", pkg, reason) else lockOverlay.dismiss("left target")
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
     * This used to re-read `getWindows()`, and then to clear a learned window
     * id set. Both are gone: the flag that made `getWindows()` work was
     * dropped for banking-app compatibility, and the id set that replaced it
     * could never match a real window under this profile. See
     * `ForegroundEventRouter`.
     *
     * Re-paces the watchdog. While anything of ours is on the glass the
     * cost of being slow to notice a financial app is an overlay sitting over
     * a payment screen, so the poll tightens from 2 s to 400 ms for as long as
     * that lasts.
     */
    private fun onOverlayWindowsChanged() {
        ServiceDiagnostics.gateShowing = gate.isShowing || leaseGate.isShowing
        repaceWatchdog()
        repaceHomeFirstWatch()
    }

    /**
     * The one job the session watchdog did that a home-first overlay still
     * needs after the session has closed: nothing of ours is ever drawn over
     * a sensitive app. The overlay outlives its session by design, and from
     * the launcher Recents can bring a banking app up under it, so while one
     * is showing the foreground is still watched, and a sensitive app takes
     * every overlay down. Stops as soon as no home-first overlay is up.
     */
    private var homeFirstWatchJob: Job? = null

    private fun repaceHomeFirstWatch() {
        val anyHomeFirst = gate.homeFirst || leaseGate.homeFirst || lockOverlay.homeFirst
        if (!anyHomeFirst) {
            homeFirstWatchJob?.cancel()
            homeFirstWatchJob = null
            return
        }
        if (homeFirstWatchJob?.isActive == true) return
        homeFirstWatchJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(OVERLAY_WATCHDOG_INTERVAL_MS)
                val active = probe.currentForegroundPackage() ?: continue
                // Decided and acted on main, where the overlays live.
                val stop = withContext(Dispatchers.Main.immediate) {
                    val gated = homeFirstGatedPkg()
                    val nowMs = now()
                    when (
                        HomeFirstWatch.onForeground(
                            active = active,
                            gatedPkg = gated,
                            sensitive = SensitivePackages.isSensitive(active, sensitivePrefixes),
                            nowMs = nowMs,
                            lastRehomeMs = lastRehomeMs,
                        )
                    ) {
                        HomeFirstWatch.Action.TearDown -> {
                            foregroundPkg = active
                            tearDownOverlays("financial app foreground under a home-first overlay")
                            Log.i(TAG, "overlays suppressed: $active")
                            true
                        }
                        HomeFirstWatch.Action.Rehome -> {
                            lastRehomeMs = nowMs
                            goHomeQuietly()
                            Log.i(HOME_FIRST_TAG, "re-home for $gated: it came back to the front under the overlay")
                            false
                        }
                        HomeFirstWatch.Action.Debounced -> {
                            Log.i(HOME_FIRST_TAG, "re-home for $gated skipped: debounced")
                            false
                        }
                        HomeFirstWatch.Action.Nothing -> false
                    }
                }
                if (stop) return@launch
            }
        }
    }

    /** When the watch last sent a gated app home again. Main thread only. See HomeFirstWatch. */
    private var lastRehomeMs: Long? = null

    /** The package the home-first overlay now up is for, or null. Main thread only. */
    private fun homeFirstGatedPkg(): String? = when {
        leaseGate.homeFirst -> leaseGate.showingFor
        gate.homeFirst -> gate.showingFor
        lockOverlay.homeFirst -> lockOverlay.showingFor
        else -> null
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
        // behind, so the shutter goes first, inside the one shared list.
        tearDownOverlays("onInterrupt")
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
        cancelLeaseExpiry("teardown")
        homeFirstWatchJob?.cancel()
        ServiceDiagnostics.onDestroyed()
        sessions.closeCurrent()
        watchdogJob?.cancel()
        checkpointJob?.cancel()
        // Persist before the scope dies, otherwise the last interval is lost
        // and has to be rebuilt by the reconciler.
        runCatching { if (::engine.isInitialized) engine.checkpoint(now()) }
        // Every window, including the lease gate and the lock screen, which
        // would otherwise outlive the process that can remove them.
        tearDownOverlays("teardown")
        scope.cancel()
    }

    companion object {
        /** `adb logcat -s Molasses.LeaseExpiry`: each check scheduled, fired and cancelled. */
        const val LEASE_EXPIRY_TAG = "Molasses.LeaseExpiry"

        /** `adb logcat -s Molasses.HomeFirst`: overlays that sent their app home, and what followed. */
        const val HOME_FIRST_TAG = "Molasses.HomeFirst"

        /**
         * `adb logcat -s Molasses.Service`: every event from [DIAG_PACKAGE],
         * its route, and for scrolls the curve's answer and the arm verdict.
         *
         * Diagnostics for one open question (why YouTube showed no stall past
         * its horizon), named here rather than applied to every target
         * because a line per scroll event for every app would bury itself.
         * Remove with the question once the device has answered it.
         */
        const val SERVICE_TAG = "Molasses.Service"
        const val DIAG_PACKAGE = "com.google.android.youtube"

        private const val TAG = "Molasses.Service"
        const val CHECKPOINT_INTERVAL_MS = 15_000L
        const val WATCHDOG_INTERVAL_MS = 2_000L

        /**
         * Watchdog interval while any overlay of ours is on the glass. Bounds
         * how long a stall sink can sit over a banking app the user has just
         * switched to.
         */
        const val OVERLAY_WATCHDOG_INTERVAL_MS = 400L

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
