package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EngineState
import dev.molasses.core.model.EventType
import dev.molasses.core.model.FrictionDecision
import dev.molasses.core.time.BootIdProvider
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.StampedInstant
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
    /**
     * Extra time added to the curve lookup for ignoring a checkpoint.
     *
     * A ratchet. It accrues while a checkpoint is overdue and **never
     * decreases**, so clearing a gate stops it growing rather than refunding
     * it. That is what keeps "clearing a gate never lowers the stall
     * duration" true: a plain double-rate model would break that invariant
     * the moment the toll was paid.
     *
     * Deliberately not folded into [accumulatedMs]. The ledger reports true
     * time; this is a separate number and is shown separately.
     */
    var penaltyMs: Long = 0,
) {
    val tier = MonotonicInt(tierIndex)

    /** Live accumulated at the last penalty evaluation. */
    var penaltyAnchorMs: Long = 0

    fun snapshot(liveAccumulatedMs: Long = accumulatedMs) = AppSnapshot(
        pkg = pkg,
        accumulatedMs = liveAccumulatedMs,
        tierIndex = tier.value,
        gatesCleared = gatesCleared,
        tierUnlockedUntilMs = tierUnlockedUntilMs,
        gatePending = gatePending,
        penaltyMs = penaltyMs,
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
 * cannot be argued with.
 *
 * The cycle anchor and the abstinence window do have to survive a reboot, so
 * they carry a wall-clock stamp as well. They are held as `StampedInstant`
 * and every deadline question about them goes through `CycleWindow`, which
 * applies `ClockTamperClamp`. Setting the system clock forward six hours
 * therefore credits nothing, which matters because it would otherwise be the
 * cheapest bypass in the app.
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
    private val bootIdProvider: BootIdProvider,
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
                penaltyMs = s.penaltyMs,
            )
        }
        .toMutableMap()

    /**
     * Start of the current cycle, or [StampedInstant.UNSET] between cycles.
     * Under `FIXED_WINDOW_6H` the deadline is this plus six hours; under
     * `ABSTINENCE_6H` it is only provenance and [lastTargetUse] decides.
     */
    private var anchor: StampedInstant = StampedInstant(
        wallMs = initial.cycleAnchorWallMs,
        elapsedMs = initial.cycleAnchorElapsedMs,
        bootId = initial.cycleAnchorBootId,
    )

    private var lastTargetUse: StampedInstant = StampedInstant(
        wallMs = initial.lastTargetUseWallMs,
        elapsedMs = initial.lastTargetUseElapsedMs,
        bootId = initial.lastTargetUseBootId,
    )

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

        val now = now(nowMs)
        openPkg?.let { closeSession(it, nowMs) }

        maybeRollCycle(now, nowMs)

        openPkg = pkg
        openStartMonotonicMs = nowMs
        appState(pkg)
        // The anchor is set here and only here on the entry path: this is the
        // "first target-app foreground after a completed cycle or a clean
        // state" the brief anchors on. A rollover leaves it unset precisely so
        // that the next entry re-anchors rather than the cycle running from an
        // instant the user was not in the app.
        if (!anchor.isSet) anchor = now
        lastTargetUse = now
        ledger.log(pkg, EventType.RESUMED)
        publish()
    }

    fun onForegroundExit(pkg: String, nowMs: Long) {
        if (openPkg != pkg) return
        closeSession(pkg, nowMs)
        ledger.log(pkg, EventType.PAUSED)
        publish()
    }

    fun onScroll(pkg: String, nowMs: Long): FrictionDecision {
        // Some apps emit scroll before any usable window-state transition.
        // Treating that as an implicit enter is strictly better than dropping
        // the time on the floor.
        if (openPkg != pkg) onForegroundEnter(pkg, nowMs)

        val app = appState(pkg)
        val live = liveAccumulatedMs(app, nowMs)
        accruePenalty(app, live)

        // tierIndex and the checkpoint schedule both run on TRUE time. The
        // penalty offsets the *stall lookup* and nothing else.
        //
        // Letting it drive tierIndex as well was wrong, and six existing
        // tests caught it: it would have pushed the checkpoint schedule
        // forward too, so ignoring a gate would have made the next gate
        // arrive later. That is the opposite of the intent, and it also made
        // the ledger's tier column stop describing time the user had spent.
        val index = TierPolicy.indexFor(live)
        app.tier.raiseTo(index)

        val effective = live + app.penaltyMs
        val effectiveIndex = TierPolicy.indexFor(effective)

        val checkpointDue = live >= app.tierUnlockedUntilMs
        if (checkpointDue && !app.gatePending) {
            app.gatePending = true
            ledger.log(pkg, EventType.GATE_SHOWN, "tier=$index live=$live")
            publish()
        }

        if (index == 0 && !checkpointDue) return FrictionDecision.NONE

        // Ledgered from tier 1 on only. Scroll events arrive in bursts of
        // dozens per second and normal (tier 0) usage is the common case, so
        // logging those would dominate the table while reconstructing nothing
        // the accumulated total does not already say.
        if (index > 0) ledger.log(pkg, EventType.SCROLL, "tier=$index")

        // The stall is unconditional. An unresolved checkpoint adds friction
        // through the penalty above; it never suspends it. That inversion is
        // the whole point of this design: a gate a user walks away from used
        // to switch friction off, which rewarded ignoring it.
        return FrictionDecision(
            // The one place the penalty is read. maxOf against the true tier
            // so a stall can never come out below what true time alone earns.
            stallMs = TierPolicy.stallMsFor(maxOf(app.tier.value, effectiveIndex)),
            gate = if (checkpointDue) index else null,
        )
    }

    /**
     * Grow [MutableAppState.penaltyMs] while a checkpoint is overdue.
     *
     * Accrues against *accumulated* time rather than wall time, at 1.0x, so
     * overdue minutes count double toward the curve and time spent outside
     * the app costs nothing. Ignoring the five minute checkpoint entirely
     * therefore reaches the terminal tier at fifteen real minutes instead of
     * twenty five.
     *
     * Monotonic by construction: the delta is floored at zero and the field
     * is only ever added to.
     */
    private fun accruePenalty(app: MutableAppState, liveMs: Long) {
        // Charge only the part of this interval that was actually overdue,
        // not the whole interval because it happened to end overdue.
        //
        // The naive version charged from the previous sample whenever the
        // current one was past the boundary, which over-charges by however
        // long the interval started before the checkpoint came due. With a
        // 15 s checkpoint tick that error is bounded and easy to miss; the
        // ladder walk caught it by jumping five minutes between scrolls and
        // landing two tiers high.
        val overdueSince = maxOf(app.penaltyAnchorMs, app.tierUnlockedUntilMs)
        if (liveMs > overdueSince) {
            app.penaltyMs += liveMs - overdueSince
        }
        app.penaltyAnchorMs = liveMs
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
        val now = now(nowMs)
        openPkg?.let { pkg ->
            val app = appState(pkg)
            app.accumulatedMs = liveAccumulatedMs(app, nowMs)
            // Accrued here too. A user who stops scrolling while a checkpoint
            // is overdue is still sitting in the app, and the penalty is for
            // the time, not for the gesture.
            accruePenalty(app, app.accumulatedMs)
            openStartMonotonicMs = nowMs
            lastTargetUse = now
        }
        // Fold first, then roll. Folding banks the time spent up to this
        // instant; rolling then discards it, which is the right order because
        // a session that spans the deadline must not carry its pre-deadline
        // minutes into the new cycle.
        //
        // Evaluating rollover here, and not only on foreground entry, is the
        // whole point of the tick. With a launch-only check a user who simply
        // stays in the app past six hours never rolls: the countdown runs
        // negative and the stall stays pinned at its ceiling for as long as
        // the session lasts.
        maybeRollCycle(now, nowMs)
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
            cycleAnchorWallMs = anchor.wallMs,
            lastTargetUseWallMs = lastTargetUse.wallMs,
            resetPolicy = resetPolicy,
            cycleAnchorElapsedMs = anchor.elapsedMs,
            cycleAnchorBootId = anchor.bootId,
            lastTargetUseElapsedMs = lastTargetUse.elapsedMs,
            lastTargetUseBootId = lastTargetUse.bootId,
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
        lastTargetUse = now(nowMs)
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
    private fun maybeRollCycle(now: StampedInstant, nowMs: Long) {
        val reference = when (resetPolicy) {
            CycleResetPolicy.ABSTINENCE_6H -> lastTargetUse
            CycleResetPolicy.FIXED_WINDOW_6H -> anchor
        }
        if (!CycleWindow.isDue(reference, now)) return

        val ageMs = CycleWindow.ageMs(reference, now)
        val keys = apps.keys.toList()
        for (pkg in keys) apps[pkg] = MutableAppState(pkg)

        if (openPkg != null) {
            // Rolling over in place, mid-session. The new cycle starts now,
            // because the user is in a target app at this instant, which is
            // exactly the condition the anchor is defined by. The session
            // start has to move with it or liveAccumulatedMs would re-credit
            // the whole pre-deadline session against the fresh AppState.
            anchor = now
            openStartMonotonicMs = nowMs
        } else {
            // Nothing open: leave the cycle unanchored so the next foreground
            // entry starts it. Anchoring at this instant instead would burn
            // window on a user who is not using anything.
            anchor = StampedInstant.UNSET
        }
        ledger.log(
            "",
            EventType.RECONCILED,
            "cycle rollover policy=$resetPolicy age=${ageMs}ms open=${openPkg ?: "-"}",
        )
    }

    /**
     * Both clocks and the boot count, read at one instant.
     *
     * [nowMs] rather than a fresh [MonotonicClock] read, so the monotonic half
     * is identical to the value the caller is doing its own arithmetic with.
     * A second read here would be a few microseconds later and would make the
     * session accounting and the deadline accounting disagree by that much.
     */
    private fun now(nowMs: Long) = StampedInstant(
        wallMs = wallClock.wallMs(),
        elapsedMs = nowMs,
        bootId = bootIdProvider.bootId(),
    )

    private fun buildState(): EngineState {
        val nowMs = monotonicClock.elapsedMs()
        val now = now(nowMs)
        return EngineState(
            perApp = apps.mapValues { (_, a) -> a.snapshot(liveAccumulatedMs(a, nowMs)) },
            openSessionPkg = openPkg,
            cycleAnchorWallMs = anchor.wallMs,
            lastTargetUseWallMs = lastTargetUse.wallMs,
            resetPolicy = resetPolicy,
            cycleRemainingMs = CycleWindow.remainingMs(
                anchor = when (resetPolicy) {
                    CycleResetPolicy.ABSTINENCE_6H -> lastTargetUse
                    CycleResetPolicy.FIXED_WINDOW_6H -> anchor
                },
                now = now,
            ),
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
