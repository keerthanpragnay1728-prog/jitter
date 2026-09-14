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
import dev.molasses.core.safety.PauseWindow
import dev.molasses.core.safety.SensitivePackages
import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.ForegroundEventRouter
import dev.molasses.core.session.ForegroundSessionTracker
import dev.molasses.core.session.WindowEvent
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.time.WallClock
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.pauseInstant
import dev.molasses.data.datastore.toEngineSnapshot
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

    private var bootId = 0
    private var debugOverrideNonce = 0L
    private var previewStallNonce = 0L

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

    /** When the user last hit "Pause Jitter". [StampedInstant.UNSET] if never. */
    private var pauseStartedAt: StampedInstant = StampedInstant.UNSET

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
            onCleared = { pkg ->
                if (ready) engine.onGateCleared(pkg, now())
            },
            onAbandoned = { pkg ->
                if (ready) engine.onGateAbandoned(pkg, now())
            },
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
            }
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
            // Read once at connect and held for the life of the service. A
            // boot cannot happen while this process is alive, so re-reading
            // Settings.Global on every tick would buy nothing and cost a
            // content-provider round trip on the accessibility thread.
            bootIdProvider = { bootId },
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

                // Debug state editor. The engine holds per-app state in memory
                // and writes it back at each checkpoint, so an edit to the
                // store alone would be overwritten within 15 s. Rebuilding the
                // engine from the edited snapshot is what makes the edit stick.
                // This drops any open session, which is acceptable for a debug
                // path and would not be for anything else.
                pinnedStallMs = state.debugPinnedStallMs.takeIf { it > 0 }

                sensitivePrefixes =
                    SensitivePackages.resolve(state.sensitivePackagePrefixesList)

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
        // Our own package stays in scope alongside the targets. Dropping it
        // would take the launcher exit event with it and leave sessions open.
        info.packageNames = if (packages.isEmpty()) {
            null
        } else {
            (packages + packageName).toTypedArray()
        }
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

        val route = router.route(
            WindowEvent(
                packageName = pkg,
                className = event.className?.toString(),
                windowId = event.windowId,
                kind = kind,
            ),
            targets = targets,
            ownWindowIds = ownWindowIds,
        )

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
        val action = engine.onScroll(pkg, now())
        if (overlaysSuppressed()) return

        when (action) {
            is FrictionAction.Stall -> shutter.arm(
                ms = pinnedStallMs ?: action.ms,
                scrollEventTimeUptimeMs = eventTime,
                callbackEntryUptimeMs = callbackEntryUptimeMs,
            )
            is FrictionAction.Gate -> gate.show(pkg, action.tier, alternativeChallenge)
            FrictionAction.None -> {}
        }
    }

    /**
     * True when nothing may be drawn on screen, for any reason.
     *
     * Two reasons, both non-negotiable:
     *
     * 1. A financial app is in the foreground. An overlay sets
     *    `FLAG_WINDOW_IS_OBSCURED` on that app's touches and a hardened
     *    banking or UPI app is entitled to refuse the transaction. Adding
     *    friction is never worth costing someone a payment at a till.
     * 2. The user hit "Pause Jitter". That is the escape hatch for the case
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
    }

    private fun enterTarget(pkg: String) {
        foregroundPkg = pkg
        val id = sessions.open(pkg)
        // Accounting first and unconditionally. Suppression is about the
        // glass, never about the ledger.
        engine.onForegroundEnter(pkg, now())
        shutter.setCurrentPackage(pkg)
        if (!overlaysSuppressed()) shutter.attach()
        armWatchdog(pkg, id)
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
    private fun armWatchdog(pkg: String, id: ForegroundSessionTracker.SessionId) {
        watchdogJob?.cancel()
        // Tight while something of ours is drawn, because that is the window
        // in which a switch to a banking app puts an overlay over a payment
        // screen. Bounded in cost: the shutter caps its own arm at 8 s and the
        // gate is user-dismissable, so the fast poll is never the steady state.
        val interval = if (shutter.isAttached || gate.isShowing) {
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
        if (!shutter.isAttached && !gate.isShowing) ownWindowIds.clear()
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
        sessions.closeCurrent()
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
    }
}
