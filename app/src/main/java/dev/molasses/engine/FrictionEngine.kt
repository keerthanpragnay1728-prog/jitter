package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EngineState
import dev.molasses.core.model.EventType
import dev.molasses.core.model.FrictionAction
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.WallClock
import kotlinx.coroutines.CoroutineScope
import dev.molasses.core.async.ChannelSpecs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mutable per-package state. Not exposed; [snapshot] is the only way out.
 *
 * `tierIndex` is a [MonotonicInt] rather than an `Int` so a decrease is a
 * crash at the assignment site, not a behavioural regression discovered later.
 */
internal class MutableAppState(
    val pkg: String,
    var accumulatedMs: Long = 0,
    tierIndex: Int = 0,
    var gatesCleared: Int = 0,
    /**
     * Accumulated-time ceiling the user has already paid for. Starts at one
     * tier width: the first five minutes are free.
     */
    var tierUnlockedUntilMs: Long = TierPolicy.TIER_WIDTH_MS,
    var gatePending: Boolean = false,
) {
    val tier = MonotonicInt(tierIndex)

    fun snapshot(liveAccumulatedMs: Long = accumulatedMs) = AppSnapshot(
        pkg = pkg,
        accumulatedMs = liveAccumulatedMs,
        tierIndex = tier.value,
        gatesCleared = gatesCleared,
        tierUnlockedUntilMs = tierUnlockedUntilMs,
        gatePending = gatePending,
    )
}

/**
 * The friction ladder's decision-maker. Pure: no Android imports, no I/O, no
 * blocking. Every public method is synchronous and allocation-light because
 * [onScroll] is called from `onAccessibilityEvent` and must stay under ~1 ms;
 * persistence is handed to [scope] through a conflating channel.
 *
 * ## Timebase
 * `nowMs` on every method is a **monotonic** timestamp
 * (`SystemClock.elapsedRealtime()`). The brief does not say which clock, and
 * this is the only safe reading: durations measured on the wall clock are
 * user-settable, and the whole point of the ladder is that accumulated time
 * cannot be argued with. Wall-clock concerns -- the cycle anchor and the
 * abstinence window -- go through [wallClock] and are clamped by
 * `ClockTamperClamp` before they ever reach here.
 *
 * ## The invariant
 * Clearing a gate is a toll, not a refund. [onGateCleared] writes exactly two
 * fields, `tierUnlockedUntilMs` and `gatesCleared`, and nothing else. It cannot
 * touch `accumulatedMs` (time already spent) or `tier` (which physically
 * refuses to decrease). `FrictionEngineTest` asserts this directly.
 */
class FrictionEngine(
    initial: EngineSnapshot,
    private val store: EngineStore,
    private val ledger: FrictionLedger,
    private val wallClock: WallClock,
    private val monotonicClock: MonotonicClock,
    private val scope: CoroutineScope,
) {
    private val apps: MutableMap<String, MutableAppState> = initial.perApp
        .mapValues { (pkg, s) ->
            MutableAppState(
                pkg = pkg,
                accumulatedMs = s.accumulatedMs,
                tierIndex = s.tierIndex,
                gatesCleared = s.gatesCleared,
                tierUnlockedUntilMs = s.tierUnlockedUntilMs,
                gatePending = s.gatePending,
            )
        }
        .toMutableMap()

    private var cycleAnchorWallMs: Long = initial.cycleAnchorWallMs
    private var lastTargetUseWallMs: Long = initial.lastTargetUseWallMs
    private var resetPolicy: CycleResetPolicy = initial.resetPolicy

    private var openPkg: String? = null
    private var openStartMonotonicMs: Long = 0

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Checkpoint requests. Conflated: under a scroll burst we want the latest
     * snapshot written, not every intermediate one, and we must never suspend
     * the caller -- [Channel.trySend] on a conflated channel always succeeds.
     *
     * Constructed through [ChannelSpecs] rather than inline: `CONFLATED`
     * already implies `DROP_OLDEST` and passing both throws at construction,
     * which shipped once and took the service down on connect. Centralising
     * the spelling lets `ChannelSpecTest` exercise the real construction.
     */
    private val writes = ChannelSpecs.engineCheckpoints<EngineSnapshot>()

    init {
        scope.launch {
            for (snapshot in writes) store.persist(snapshot)
        }
    }

    // ---------------------------------------------------------------- public

    fun onForegroundEnter(pkg: String, nowMs: Long) {
        // TYPE_WINDOW_STATE_CHANGED fires repeatedly inside a single app
        // (dialogs, tab switches, video overlays). Re-entering the app that is
        // already open must be a no-op or the session start would keep
        // resetting and accumulate nothing at all.
        if (openPkg == pkg) return

        openPkg?.let { closeSession(it, nowMs) }

        maybeRollCycle()

        openPkg = pkg
        openStartMonotonicMs = nowMs
        appState(pkg)
        lastTargetUseWallMs = wallClock.wallMs()
        ledger.log(pkg, EventType.RESUMED)
        publish()
    }

    fun onForegroundExit(pkg: String, nowMs: Long) {
        if (openPkg != pkg) return
        closeSession(pkg, nowMs)
        ledger.log(pkg, EventType.PAUSED)
        publish()
    }

    fun onScroll(pkg: String, nowMs: Long): FrictionAction {
        // Some apps emit scroll before any usable window-state transition.
        // Treating that as an implicit enter is strictly better than dropping
        // the time on the floor.
        if (openPkg != pkg) onForegroundEnter(pkg, nowMs)

        val app = appState(pkg)
        val live = liveAccumulatedMs(app, nowMs)
        val index = TierPolicy.indexFor(live)
        app.tier.raiseTo(index)

        if (index == 0) return FrictionAction.None

        // Ledgered from tier 1 on only. Scroll events arrive in bursts of
        // dozens per second and normal (tier 0) usage is the common case, so
        // logging those would dominate the table while reconstructing nothing
        // the accumulated total does not already say.
        ledger.log(pkg, EventType.SCROLL, "tier=$index")

        if (live >= app.tierUnlockedUntilMs) {
            // The tier has been entered but not paid for.
            if (!app.gatePending) {
                app.gatePending = true
                ledger.log(pkg, EventType.GATE_SHOWN, "tier=$index live=$live")
                publish()
            }
            return FrictionAction.Gate(index)
        }

        // Paid for. Stall at the tier reached, which is >= the tier entered,
        // never below it.
        return FrictionAction.Stall(TierPolicy.stallMsFor(app.tier.value))
    }

    /**
     * The toll was paid. Unlocks usage up to the *next* tier boundary.
     *
     * Writes `tierUnlockedUntilMs` and `gatesCleared` only. Deliberately does
     * not touch `accumulatedMs` or `tier` -- see the class doc.
     */
    fun onGateCleared(pkg: String, nowMs: Long) {
        val app = appState(pkg)
        val nextBoundary = TierPolicy.entryAtMs(app.tier.value + 1)
        // max() so a late or duplicated clear can never lower the ceiling.
        app.tierUnlockedUntilMs = maxOf(app.tierUnlockedUntilMs, nextBoundary)
        app.gatesCleared += 1
        app.gatePending = false
        ledger.log(
            pkg,
            EventType.GATE_PASSED,
            "tier=${app.tier.value} unlockedUntil=${app.tierUnlockedUntilMs}",
        )
        publish()
    }

    /**
     * The gate was left unresolved (user went HOME, screen off, timeout).
     * Changes no accounting at all: `gatePending` stays true, so the next
     * scroll in the target app re-gates at the same tier.
     */
    fun onGateAbandoned(pkg: String, nowMs: Long) {
        val app = appState(pkg)
        ledger.log(pkg, EventType.GATE_ABANDONED, "tier=${app.tier.value}")
        publish()
    }

    // --------------------------------------------------------------- internal

    /**
     * Fold the open session into `accumulatedMs` without ending it. Called on
     * the 15 s checkpoint cadence so a process death loses at most 15 s and the
     * reconciler never has to scan more than a few minutes of `UsageStats`.
     */
    internal fun checkpoint(nowMs: Long) {
        openPkg?.let { pkg ->
            val app = appState(pkg)
            app.accumulatedMs = liveAccumulatedMs(app, nowMs)
            openStartMonotonicMs = nowMs
            lastTargetUseWallMs = wallClock.wallMs()
        }
        publish()
    }

    /**
     * The form that gets persisted.
     *
     * It folds the live open session into `accumulatedMs`, so the snapshot
     * always means "everything up to this instant, nothing outstanding". The
     * writer stamps `last_seen_wall_ms` / `last_seen_elapsed_ms` at the same
     * instant, and the reconciler replays `UsageStats` only from
     * `last_seen_wall_ms` forward -- so the interval is credited exactly once.
     *
     * The alternative (persist only closed time and let the reconciler replay
     * the whole session from `open_session_start_wall_ms`) loses the entire
     * session if `UsageStats` is unavailable, and double-counts if it is not
     * careful about which start it replays from. `open_session_start_*` is
     * still persisted, but only as debug provenance.
     */
    internal fun snapshot(): EngineSnapshot {
        val now = monotonicClock.elapsedMs()
        return EngineSnapshot(
            perApp = apps.mapValues { (_, a) -> a.snapshot(liveAccumulatedMs(a, now)) },
            cycleAnchorWallMs = cycleAnchorWallMs,
            lastTargetUseWallMs = lastTargetUseWallMs,
            resetPolicy = resetPolicy,
        )
    }

    internal fun setResetPolicy(policy: CycleResetPolicy) {
        resetPolicy = policy
        publish()
    }

    private fun appState(pkg: String): MutableAppState =
        apps.getOrPut(pkg) { MutableAppState(pkg) }

    private fun closeSession(pkg: String, nowMs: Long) {
        val app = appState(pkg)
        app.accumulatedMs = liveAccumulatedMs(app, nowMs)
        lastTargetUseWallMs = wallClock.wallMs()
        openPkg = null
        openStartMonotonicMs = 0
    }

    /**
     * `accumulatedMs` plus the live open session.
     *
     * The delta is floored at zero and capped: a monotonic clock cannot run
     * backwards, so a negative delta means a boot-reset value leaked past the
     * reconciler, and a delta larger than [MAX_SESSION_MS] means the same. In
     * both cases crediting the raw number would jump a user straight to the
     * terminal tier on a bug, which is the one failure mode that would make
     * the app feel malicious rather than annoying.
     */
    private fun liveAccumulatedMs(app: MutableAppState, nowMs: Long): Long {
        if (openPkg != app.pkg) return app.accumulatedMs
        val delta = (nowMs - openStartMonotonicMs).coerceIn(0, MAX_SESSION_MS)
        return app.accumulatedMs + delta
    }

    /**
     * Cycle rollover.
     *
     * This is the one legitimate discontinuity in `tierIndex`, and it is
     * implemented by *replacing* each [MutableAppState] with a fresh one rather
     * than assigning 0 to the existing [MonotonicInt] -- which would throw. The
     * distinction is not pedantry: it keeps "never decremented" true for the
     * lifetime of a counter, so the guard stays meaningful inside a cycle,
     * which is the scope the invariant is actually about.
     */
    private fun maybeRollCycle() {
        val nowWall = wallClock.wallMs()
        val due = when (resetPolicy) {
            CycleResetPolicy.ABSTINENCE_6H ->
                lastTargetUseWallMs > 0 &&
                    nowWall - lastTargetUseWallMs >= CycleResetPolicy.WINDOW_MS
            CycleResetPolicy.FIXED_WINDOW_6H ->
                cycleAnchorWallMs > 0 &&
                    nowWall - cycleAnchorWallMs >= CycleResetPolicy.WINDOW_MS
        }
        if (!due) {
            if (cycleAnchorWallMs == 0L) cycleAnchorWallMs = nowWall
            return
        }

        val keys = apps.keys.toList()
        for (pkg in keys) apps[pkg] = MutableAppState(pkg)
        cycleAnchorWallMs = nowWall
        ledger.log("", EventType.RECONCILED, "cycle rollover policy=$resetPolicy")
    }

    private fun buildState(): EngineState {
        val now = monotonicClock.elapsedMs()
        return EngineState(
            perApp = apps.mapValues { (_, a) -> a.snapshot(liveAccumulatedMs(a, now)) },
            openSessionPkg = openPkg,
            cycleAnchorWallMs = cycleAnchorWallMs,
            lastTargetUseWallMs = lastTargetUseWallMs,
            resetPolicy = resetPolicy,
        )
    }

    private fun publish() {
        _state.value = buildState()
        writes.trySend(snapshot())
    }

    private companion object {
        /** No single foreground session is plausibly longer than this. */
        const val MAX_SESSION_MS = 12L * 60 * 60 * 1000
    }
}
