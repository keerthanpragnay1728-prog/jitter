package dev.molasses.engine

import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EngineState
import dev.molasses.core.model.EventType
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.HorizonPolicy
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
import kotlin.random.Random
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
    var leasesTaken: Int = 0,
    /**
     * Accumulated-time mark where the last lease taken runs out.
     *
     * Zero when none has been taken this cycle, which is the state a fresh
     * app starts in and is not the same as a lease that expired at zero. With
     * none taken nothing is overdue and [penaltyMs] does not run.
     *
     * This field used to be `tierUnlockedUntilMs`, the ceiling paid for by
     * clearing a checkpoint gate. Same units and same role in the ratchet;
     * what changed is what buys it.
     */
    var leaseUntilAccumulatedMs: Long = 0,
    /**
     * Extra time added to the curve lookup for sitting past your lease.
     *
     * A ratchet. It accrues while the lease is overdue and **never
     * decreases**, so taking a new lease stops it growing rather than
     * refunding it. That is what keeps "a lease never lowers the stall
     * duration" true, and it is the same sentence the checkpoint version
     * carried: a plain double-rate model would break the invariant the moment
     * the toll was paid.
     *
     * Deliberately not folded into [accumulatedMs]. The ledger reports true
     * time; this is a separate number and is shown separately.
     */
    var penaltyMs: Long = 0,
    /** See [AppSnapshot.horizonMs]. Never zero: [HorizonPolicy.of] resolves it. */
    var horizonMs: Long = FrictionCurve.DEFAULT_HORIZON_MS,
    var pendingHorizonMs: Long = HorizonPolicy.NONE,
) {
    val tier = MonotonicInt(tierIndex)

    /**
     * Live accumulated at the last penalty evaluation. Persisted, so a
     * restart resumes charging from here rather than from the lease mark;
     * see [AppSnapshot.penaltyAnchorMs] for what a missing one reads as.
     */
    var penaltyAnchorMs: Long = accumulatedMs

    fun snapshot(liveAccumulatedMs: Long = accumulatedMs) = AppSnapshot(
        pkg = pkg,
        accumulatedMs = liveAccumulatedMs,
        tierIndex = tier.value,
        leasesTaken = leasesTaken,
        leaseUntilAccumulatedMs = leaseUntilAccumulatedMs,
        penaltyMs = penaltyMs,
        horizonMs = horizonMs,
        pendingHorizonMs = pendingHorizonMs,
        penaltyAnchorMs = penaltyAnchorMs,
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
 * A lease is a toll, not a refund. [onLeaseGranted] writes exactly two fields,
 * `leaseUntilAccumulatedMs` and `leasesTaken`, and nothing else. It cannot
 * touch `accumulatedMs` (time already spent) or `tier` (which physically
 * refuses to decrease). `FrictionEngineTest` asserts this directly.
 *
 * ## The two systems do not meet here
 * This engine knows nothing about [dev.molasses.core.lease.LeaseManager]. It
 * is told a lease was granted and for how long, and that is the whole of the
 * contact: no call goes the other way, and nothing here reads whether a lease
 * is currently live. Buying time does not buy friction, and the cheapest way
 * to keep that true is for the code that hands out time to have no way to
 * reach the code that charges for it.
 */
class FrictionEngine(
    initial: EngineSnapshot,
    private val store: EngineStore,
    /**
     * Stall floor, in milliseconds. A parameter rather than a constant read
     * inside the curve so it can be retuned per device: segment D p50 decides
     * what is observable, and it differs by hardware.
     */
    private val floorMs: Int = FrictionCurve.DEFAULT_FLOOR_MS,
    /**
     * Bernoulli source, 0.0 to 1.0. Injected so the probability dimension is
     * reproducible in tests; production passes a real random.
     */
    private val roll: () -> Float = { Random.nextFloat() },
    private val ledger: FrictionLedger,
    private val wallClock: WallClock,
    private val monotonicClock: MonotonicClock,
    private val bootIdProvider: BootIdProvider,
    private val scope: CoroutineScope,
    /**
     * Fired after a cycle rollover, on the calling thread.
     *
     * The rollover resets every per-app counter this engine holds, and the
     * leases live in the store where this engine cannot reach them. Without
     * this, a lease taken before the rollover would outlive the cycle it was
     * escalating against, and the first gate of the new cycle would not
     * appear until it expired.
     *
     * A callback rather than a call into the store, because the engine having
     * a way to reach persistence is how it stops being pure.
     */
    private val onCycleRolled: () -> Unit = {},
    /**
     * Called first by every public method. The engine is confined to one
     * thread rather than locked: its per-app map is plain and unsynchronised,
     * and [onScroll] is on the stall's latency path, where a lock contended by
     * the checkpoint tick would be paid inside segment B. So the owner calls
     * it from one thread only, and this is how the owner checks that.
     *
     * The service passes a main-thread assertion in debug builds and nothing
     * in release. Pure code cannot name a thread, which is why it is injected.
     */
    private val confinement: () -> Unit = {},
) {
    private val apps: MutableMap<String, MutableAppState> = initial.perApp
        .mapValues { (pkg, s) ->
            MutableAppState(
                pkg = pkg,
                accumulatedMs = s.accumulatedMs,
                tierIndex = s.tierIndex,
                leasesTaken = s.leasesTaken,
                leaseUntilAccumulatedMs = s.leaseUntilAccumulatedMs,
                penaltyMs = s.penaltyMs,
                horizonMs = s.horizonMs,
                pendingHorizonMs = s.pendingHorizonMs,
            ).also { app ->
                // No stored anchor charges nothing retroactively: anchoring at
                // zero, as this used to, re-billed everything past the lease
                // mark that the ratchet had already charged before the restart.
                app.penaltyAnchorMs = s.penaltyAnchorMs ?: s.accumulatedMs
            }
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

    private val resetPolicy: CycleResetPolicy = initial.resetPolicy

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
        confinement()
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
        confinement()
        if (openPkg != pkg) return
        closeSession(pkg, nowMs)
        ledger.log(pkg, EventType.PAUSED)
        publish()
    }

    fun onScroll(pkg: String, nowMs: Long): FrictionDecision {
        confinement()
        // Some apps emit scroll before any usable window-state transition.
        // Treating that as an implicit enter is strictly better than dropping
        // the time on the floor.
        if (openPkg != pkg) onForegroundEnter(pkg, nowMs)

        val app = appState(pkg)
        val live = liveAccumulatedMs(app, nowMs)
        accruePenalty(app, live)

        // tierIndex runs on TRUE time. The penalty offsets the *stall lookup*
        // and nothing else.
        //
        // Letting it drive tierIndex as well was wrong, and six existing
        // tests caught it: it made the ledger's tier column stop describing
        // time the user had spent. It matters again now that the tier decides
        // whether the walking gate is in force, which is a question about how
        // long they have really been in the app.
        val index = TierPolicy.indexFor(live)
        app.tier.raiseTo(index)

        val effective = FrictionCurve.effectiveMs(live, app.penaltyMs)
        val friction = FrictionCurve.frictionAt(effective, app.horizonMs, floorMs)

        if (!friction.stalls) return FrictionDecision.NONE

        // Ledgered from tier 1 on only. Scroll events arrive in bursts of
        // dozens per second and normal (tier 0) usage is the common case, so
        // logging those would dominate the table while reconstructing nothing
        // the accumulated total does not already say.
        if (index > 0) ledger.log(pkg, EventType.SCROLL, "tier=$index")

        // Probability is Bernoulli per scroll event. A miss is not "no
        // friction": the curve has already been read and the ratchet has
        // already run, so the accounting is identical either way.
        val stallMs = if (FrictionCurve.shouldStall(friction, roll())) {
            friction.stallMs.toLong()
        } else {
            0L
        }

        return FrictionDecision(
            stallMs = stallMs,
            terminal = effective >= FrictionCurve.terminalMs(app.horizonMs),
        )
    }

    /**
     * Has the curve saturated for [pkg].
     *
     * Asked at the launch, where a scroll decision does not exist yet, to
     * decide whether the walking gate is in force. Reads exactly what
     * [onScroll] would read, so the answer cannot drift from the one on the
     * stall marker.
     */
    fun isTerminal(pkg: String, nowMs: Long): Boolean {
        confinement()
        val app = apps[pkg] ?: return false
        return liveAccumulatedMs(app, nowMs) + app.penaltyMs >=
            FrictionCurve.terminalMs(app.horizonMs)
    }

    /** Leases granted on [pkg] in the current cycle. Drives the escalation. */
    fun leasesTakenThisCycle(pkg: String): Int {
        confinement()
        return apps[pkg]?.leasesTaken ?: 0
    }

    /**
     * Apply the user's declared horizon for [pkg].
     *
     * Narrowing lands now, widening waits for the next rollover; see
     * [HorizonPolicy], which holds the rule and the reason. Idempotent, so
     * the caller re-applies the stored preference on every settings emission
     * rather than tracking whether it already did.
     *
     * Touches neither `accumulatedMs` nor `tier`. A horizon is a statement
     * about what a session is for, not a reset, and someone forty minutes
     * into a widened curve stays forty minutes into it.
     */
    fun setHorizon(pkg: String, requestedMs: Long) {
        confinement()
        val app = appState(pkg)
        val before = HorizonPolicy.of(app.horizonMs, app.pendingHorizonMs)
        val after = HorizonPolicy.request(before, requestedMs)
        if (after == before) return
        app.horizonMs = after.horizonMs
        app.pendingHorizonMs = after.pendingHorizonMs
        ledger.log(
            pkg,
            EventType.HORIZON_SET,
            "horizon=${after.horizonMs} pending=${after.pendingHorizonMs}",
        )
        publish()
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
        // No lease taken, nothing overdue. This guard is the whole difference
        // between the ratchet charging for time past a toll the user chose to
        // ignore and charging every user who has the gate suppressed, which
        // is what anchoring to a boundary nothing clears would have meant.
        if (app.leasesTaken > 0) {
            // Charge only the part of this interval that was actually
            // overdue, not the whole interval because it happened to end
            // overdue.
            //
            // The naive version charged from the previous sample whenever the
            // current one was past the mark, which over-charges by however
            // long the interval started before the lease ran out. With a 15 s
            // checkpoint tick that error is bounded and easy to miss; the
            // ladder walk caught it by jumping five minutes between scrolls
            // and landing two tiers high.
            val overdueSince = maxOf(app.penaltyAnchorMs, app.leaseUntilAccumulatedMs)
            if (liveMs > overdueSince) {
                app.penaltyMs += liveMs - overdueSince
            }
        }
        app.penaltyAnchorMs = liveMs
    }

    /**
     * A lease was taken. Moves the overdue mark [durationMs] of *accumulated*
     * time forward from here.
     *
     * Accumulated rather than wall time, deliberately, and this is the one
     * place the two systems are close enough together to get confused. The
     * lease itself is fifteen real minutes and expires on the clock whether
     * the user is in the app or not; the ratchet's mark is where those
     * minutes land if they are all spent inside it. A user who takes fifteen
     * minutes and spends five of them in a different app comes back with the
     * lease nearly gone and the mark barely touched, which is correct: the
     * ratchet charges for time in the app past what was paid for, and they
     * have not spent it.
     *
     * Writes `leaseUntilAccumulatedMs` and `leasesTaken` only. Deliberately
     * does not touch `accumulatedMs` or `tier` -- see the class doc.
     */
    fun onLeaseGranted(pkg: String, durationMs: Long, nowMs: Long) {
        confinement()
        val app = appState(pkg)
        val live = liveAccumulatedMs(app, nowMs)
        // Close out whatever was overdue before moving the mark, or the
        // interval between the lease running out and the new one being taken
        // would be forgiven rather than charged.
        accruePenalty(app, live)
        // Never lengthen a mark that is still ahead of the live total, and
        // never lower one either. The same rule as `LeaseManager.grant`, in
        // the other timebase, and now written the same way on both sides.
        //
        // This used to be max(mark, live + duration), which protected the
        // second half of that and handed away the first. A second grant
        // arriving while the mark was still ahead pushed it a further full
        // duration out, so a duplicate bought relief the user never paid for.
        // The store refused to lengthen the wall clock lease in the same
        // breath, so the two halves of one grant disagreed about what it was
        // worth, and the half that disagreed in the user's favour was the
        // ratchet. Always err toward more friction.
        //
        // The case max() was there for still holds: a short grant landing
        // while a long mark is live leaves the long mark alone rather than
        // revoking accumulated time that was already bought.
        if (app.leaseUntilAccumulatedMs <= live) {
            app.leaseUntilAccumulatedMs = live + durationMs
        }
        app.leasesTaken += 1
        ledger.log(
            pkg,
            EventType.LEASE_TAKEN,
            "duration=${durationMs}ms untilAccum=${app.leaseUntilAccumulatedMs} " +
                "n=${app.leasesTaken}",
        )
        publish()
    }

    // --------------------------------------------------------------- internal

    /**
     * Fold the open session into `accumulatedMs` without ending it. Called on
     * the 15 s checkpoint cadence so a process death loses at most 15 s and the
     * reconciler never has to scan more than a few minutes of `UsageStats`.
     */
    internal fun checkpoint(nowMs: Long) {
        confinement()
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
        confinement()
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
        for (pkg in keys) {
            // The horizon crosses the rollover, and a widen waiting for one
            // lands here. Everything else about the app starts over, which is
            // what makes the rollover the right moment: the accumulated total
            // the new curve is read against is zero, so a wider horizon can
            // never arrive part way up a ramp it did not scale.
            val promoted = HorizonPolicy.promote(
                HorizonPolicy.of(apps.getValue(pkg).horizonMs, apps.getValue(pkg).pendingHorizonMs),
            )
            apps[pkg] = MutableAppState(
                pkg = pkg,
                horizonMs = promoted.horizonMs,
                pendingHorizonMs = promoted.pendingHorizonMs,
            )
        }

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
        onCycleRolled()
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
