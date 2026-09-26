package dev.molasses.data.datastore

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dev.molasses.AppState
import dev.molasses.CycleResetPolicyProto
import dev.molasses.CycleState
import dev.molasses.core.command.CommandHistory
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.console.ConsoleSpeech
import dev.molasses.core.friction.HorizonPolicy
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.lease.LeaseLadder
import dev.molasses.core.lease.LeaseManager
import dev.molasses.core.launch.QuickLaunch
import dev.molasses.core.remind.Reminder
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.lock.PrefixLock
import dev.molasses.core.lock.TargetLock
import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.session.TargetScope
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.ui.FontScale
import dev.molasses.engine.TierPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Hot state. Read once on connect and on every settings change; written on the
 * 15 s checkpoint cadence and whenever the engine's state moves.
 *
 * Every write goes through [DataStore.updateData], which is itself
 * `Dispatchers.IO`-confined and serialised, so nothing here can block the
 * accessibility callback thread. The engine reaches this only through its
 * conflating channel.
 */
class CycleStateStore(context: Context) {

    private val appContext = context.applicationContext

    private val store: DataStore<CycleState> = DataStoreFactory.create(
        serializer = CycleStateSerializer,
        // A corrupt file resets hot state rather than crashing the
        // accessibility service on every connect, which would leave the user
        // with a permanently dead app and no way to see why.
        corruptionHandler = ReplaceFileCorruptionHandler {
            CycleStateSerializer.defaultValue
        },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { appContext.dataStoreFile(CycleStateSerializer.FILE_NAME) },
    )

    val data: Flow<CycleState> = store.data

    val snapshots: Flow<EngineSnapshot> = store.data.map { it.toEngineSnapshot() }

    suspend fun current(): CycleState = store.data.first()

    /**
     * Persist an engine snapshot and stamp the checkpoint clocks at the same
     * instant, so [dev.molasses.monitor.ForegroundReconciler] replays
     * `UsageStats` from exactly the point this snapshot accounts up to.
     */
    suspend fun write(snapshot: EngineSnapshot, bootId: Int, openSessionPkg: String?) {
        store.updateData { old ->
            val b = old.toBuilder()
                .clearPerApp()
                .setCycleAnchorWallMs(snapshot.cycleAnchorWallMs)
                .setCycleAnchorElapsedMs(snapshot.cycleAnchorElapsedMs)
                .setCycleAnchorBootId(snapshot.cycleAnchorBootId)
                .setLastTargetUseWallMs(snapshot.lastTargetUseWallMs)
                .setLastTargetUseElapsedMs(snapshot.lastTargetUseElapsedMs)
                .setLastTargetUseBootId(snapshot.lastTargetUseBootId)
                .setBootId(bootId)
                .setLastSeenWallMs(System.currentTimeMillis())
                .setLastSeenElapsedMs(SystemClock.elapsedRealtime())
                .setOpenSessionPkg(openSessionPkg ?: "")
                // Constant. The engine has one cycle policy; the field is
                // kept, and written, so the file stays well formed for any
                // build that still reads it.
                .setResetPolicy(CycleResetPolicyProto.FIXED_WINDOW_6H)
            if (openSessionPkg == null) {
                b.clearOpenSessionStartWallMs()
                b.clearOpenSessionStartElapsedMs()
            } else if (old.openSessionPkg != openSessionPkg) {
                b.setOpenSessionStartWallMs(System.currentTimeMillis())
                b.setOpenSessionStartElapsedMs(SystemClock.elapsedRealtime())
            }
            for ((pkg, s) in snapshot.perApp) b.putPerApp(pkg, s.toProto())
            b.build()
        }
    }

    /** Reconciler output: credited time folded in, checkpoint re-stamped. */
    suspend fun applyReconciliation(
        creditedByPkg: Map<String, Long>,
        lastTargetUseWallMs: Long?,
        bootId: Int,
        anchor: StampedInstant,
    ) {
        store.updateData { old ->
            val b = old.toBuilder()
                .setBootId(bootId)
                .setLastSeenWallMs(System.currentTimeMillis())
                .setLastSeenElapsedMs(SystemClock.elapsedRealtime())
                .setCycleAnchorWallMs(anchor.wallMs)
                .setCycleAnchorElapsedMs(anchor.elapsedMs)
                .setCycleAnchorBootId(anchor.bootId)
                .setOpenSessionPkg("")
                .clearOpenSessionStartWallMs()
                .clearOpenSessionStartElapsedMs()
            // A replayed last-use timestamp comes from UsageStats, which
            // reports wall-clock times only. Stamp the monotonic half from
            // this instant and accept that the pair is a lower bound: the
            // clamp can only under-credit from it, never over-credit.
            lastTargetUseWallMs?.let {
                b.setLastTargetUseWallMs(it)
                b.setLastTargetUseElapsedMs(SystemClock.elapsedRealtime())
                b.setLastTargetUseBootId(bootId)
            }
            for ((pkg, credited) in creditedByPkg) {
                if (credited <= 0) continue
                val existing = old.perAppMap[pkg] ?: AppState.getDefaultInstance()
                val total = existing.accumulatedMs + credited
                b.putPerApp(
                    pkg,
                    existing.toBuilder()
                        .setAccumulatedMs(total)
                        // tier_index is monotonic: raise it to match the new
                        // total, never lower it.
                        .setTierIndex(maxOf(existing.tierIndex, TierPolicy.indexFor(total)))
                        // The lease mark is deliberately left alone. It is
                        // where a lease the user took runs out, and replaying
                        // lost foreground time does not hand out a lease. The
                        // old code raised this to one tier width here, which
                        // made sense when the field was a free-usage ceiling
                        // and would now be a lease nobody took.
                        .build(),
                )
            }
            b.build()
        }
    }


    suspend fun setFontScale(scale: FontScale) {
        store.updateData { it.toBuilder().setFontScaleOrdinal(scale.ordinal).build() }
    }

    /**
     * One-shot schema migration, run from the reconciler before the engine is
     * seeded.
     *
     * Version 1 moves the default cycle policy to `FIXED_WINDOW_6H`. It cannot
     * be done by changing the serializer default alone: proto3 pins an enum's
     * zero value as its default, `ABSTINENCE_6H` is 0, and an install created
     * before the flip has a stored 0 that is indistinguishable from "never
     * set". Renumbering the enum would reinterpret every stored file instead,
     * which is worse. A user who has explicitly chosen abstinence after the
     * migration keeps it, because [schemaVersion] is already 1 by then.
     */
    suspend fun migrate() {
        store.updateData { old ->
            if (old.schemaVersion >= SCHEMA_VERSION) return@updateData old
            old.toBuilder()
                .setResetPolicy(CycleResetPolicyProto.FIXED_WINDOW_6H)
                .setSchemaVersion(SCHEMA_VERSION)
                .build()
        }
    }

    /**
     * Track or untrack [pkg], unless a lock stands on it.
     *
     * ## Why the read and the write are in the same block
     * The same reason [armLocks] gives, and it arrived the same way. The
     * guard lived in the view model, which read a `StateFlow` snapshot of the
     * targets and a second snapshot of the locks and then wrote. A lock armed
     * between those reads and that write would have been missed, and the one
     * armed by `$ bedtime` fires on a timer with nobody watching.
     *
     * Inside `updateData` there is no window. The resolved list, the lock's
     * remaining time and the write all read the same state, and DataStore
     * serialises the transform.
     *
     * ## Why the store may ask about locks
     * It already does: `locks` is built from `state.locksList` a few lines
     * up, because both live in the same proto. Nothing new is dragged in
     * here. The resolver is the thing that must stay ignorant of locks, and
     * it does: `TargetScope.resolve` turns a stored list into an effective
     * one and this asks `TargetLock` separately.
     *
     * ## Why there is no general setter any more
     * This replaced `setTargets`, which took a whole list and wrote it
     * unconditionally. One caller ever used it, and the guard on that caller
     * was complete. But a complete guard resting on there being one caller is
     * a guard waiting for the second one, and this repository has now paid for
     * that twice. There is no way to write the target list that does not come
     * through here.
     */
    suspend fun toggleTarget(pkg: String, now: StampedInstant, onlyIfTracked: Boolean = false) {
        store.updateData { state ->
            val selection = TargetScope.Selection(
                stored = state.targetPackagesList,
                chosen = state.targetsChosen,
            )
            val current = TargetScope.resolve(selection, DEFAULT_TARGETS).toList()
            // The untrack cooling-off commits through here with this set. It
            // can only remove: if the list changed while the countdown ran
            // and the app is no longer tracked, a toggle would add it back,
            // which is not what CONFIRM REMOVE means.
            if (onlyIfTracked && pkg !in current) return@updateData state
            val locks = LockRegistry.of(state.locksList.map { it.toLock() })
            val next = TargetLock.toggled(current, pkg, locks.remainingMs(pkg, now))
                ?: return@updateData state
            // Set on every write, including the one that empties the list.
            // That write is the whole point: it is how "I turned everything
            // off" becomes a sentence the store can hold rather than a state
            // indistinguishable from never having been asked.
            state.toBuilder()
                .clearTargetPackages()
                .addAllTargetPackages(next)
                .setTargetsChosen(true)
                .build()
        }
    }

    /**
     * Push one submitted line onto the command history.
     *
     * The cap and the dedupe happen inside `updateData` rather than in the
     * caller, so two commands submitted in the same frame cannot both read a
     * nineteen entry list and write a twenty first.
     */
    suspend fun recordCommand(line: String) {
        if (line.isBlank()) return
        store.updateData {
            val next = CommandHistory.record(it.commandHistoryList, line)
            it.toBuilder().clearCommandHistory().addAllCommandHistory(next).build()
        }
    }

    /**
     * Ask the accessibility service to turn itself off.
     *
     * One way. `disableSelf()` cannot be reversed from code, by platform
     * guarantee, and the user must re-enable from Android Settings by hand.
     * That is not a limitation being worked around; it is the mechanism.
     * Paytm blocks on the *presence* of any enabled accessibility service
     * that is not on its allowlist, and never asks what that service
     * observes, so nothing short of the service being gone changes what it
     * sees.
     */
    suspend fun requestDisable() {
        store.updateData {
            it.toBuilder().setDisableRequestNonce(System.currentTimeMillis()).build()
        }
    }

    // ---------------------------------------------------------------- console

    /** The queue, the live prompt and the budget, in one emission. */
    val console: Flow<ConsoleState> = store.data.map { it.toConsoleState() }

    /**
     * Queue one line for the next time the console is foregrounded.
     *
     * Depth one, most recent wins. A backlog delivered all at once is a
     * lecture, and an observation from two sessions ago is not worth the slot
     * the current one wants.
     *
     * Nothing is counted here. The caps count renders, so a line that is
     * queued and never delivered has cost nothing.
     */
    suspend fun enqueueConsoleLine(line: ConsoleLine) {
        store.updateData { it.toBuilder().setConsoleQueued(line.toProto()).build() }
    }

    /**
     * Mark a line as actually drawn.
     *
     * One transform, because the budget and the queue have to move together:
     * counting without clearing would deliver twice, and clearing without
     * counting would make the caps decorative.
     *
     * A prompt moves to the live slot and stays until answered. A notice has
     * no further state; its eight seconds live in the host, which is the only
     * place that knows when it was first drawn.
     */
    suspend fun deliverConsoleLine(line: ConsoleLine, budget: ConsoleSpeech.Budget) {
        store.updateData { state ->
            val b = state.toBuilder()
                .clearConsoleQueued()
                .setConsoleBudget(budget.toProto())
            if (line is ConsoleLine.Prompt) b.setConsoleLive(line.toProto()) else b.clearConsoleLive()
            b.build()
        }
    }

    /** The question was answered or dismissed. There is nothing to undo. */
    suspend fun clearConsolePrompt() {
        store.updateData { it.toBuilder().clearConsoleLive().build() }
    }

    // ------------------------------------------------------------------ locks

    /**
     * The persisted registry, rebuilt on every emission.
     *
     * Rebuilt rather than cached because `LockRegistry` is immutable and the
     * list is at most a handful of entries. A cache would be a second source
     * of truth for the one piece of state in this app that must not be
     * possible to disagree about.
     */
    val locks: Flow<LockRegistry> =
        store.data.map { state -> LockRegistry.of(state.locksList.map { it.toLock() }) }

    /**
     * Arm or extend a lock on [pkg].
     *
     * ## Why the read and the write are in the same block
     * The extend-only rule is the whole value of a lock, and it is only worth
     * anything if it holds against what is on disk rather than against a copy
     * something read earlier. Doing the comparison inside `updateData` means a
     * shorter lock armed after a process death, a reboot, or from a second
     * code path is still a no-op: DataStore serialises the transform, so no
     * caller can read a thirty day lock, be descheduled, and write a one
     * minute one over it.
     *
     * Expired entries are pruned on the way through, which is the only place
     * that happens. `isLocked` already treats an expired lock as absent, so
     * this changes no behaviour and exists to keep the list bounded.
     */
    suspend fun armLock(
        pkg: String,
        now: StampedInstant,
        durationMs: Long,
        reason: LockReason,
    ) = armLocks(listOf(pkg), now, durationMs, reason)

    /** The same duration across several packages, as `$ focus` does. */
    suspend fun armLocks(
        packages: Collection<String>,
        now: StampedInstant,
        durationMs: Long,
        reason: LockReason,
    ) {
        if (packages.isEmpty() || durationMs <= 0L) return
        store.updateData { state ->
            val next = LockRegistry.of(state.locksList.map { it.toLock() })
                .armAll(packages, now, durationMs, reason)
                .prune(now)
            state.toBuilder()
                .clearLocks()
                .addAllLocks(next.snapshot().map { it.toProto() })
                .build()
        }
    }

    /**
     * Drop expired entries. Housekeeping; no lock changes state because of it.
     *
     * There is deliberately no unlock. A lock that can be cleared is a lock
     * that will be cleared, at the exact moment it is working.
     */
    suspend fun pruneLocks(now: StampedInstant) {
        store.updateData { state ->
            val next = LockRegistry.of(state.locksList.map { it.toLock() }).prune(now)
            if (next.snapshot().size == state.locksCount) return@updateData state
            state.toBuilder()
                .clearLocks()
                .addAllLocks(next.snapshot().map { it.toProto() })
                .build()
        }
    }

    /**
     * Grant a lease on [pkg].
     *
     * ## Why the read and the write are in the same block
     * Exactly the reason `armLock` gives, pointed the other way. The rule that
     * makes a lease safe is that a live one is never lengthened, and it is
     * only worth anything if it holds against what is on disk. Doing the
     * comparison inside `updateData` means two taps racing, or a second code
     * path, cannot read an expired lease, be descheduled, and write a fresh
     * fifteen minutes over a live five.
     *
     * Expired entries are pruned on the way through, which is the only place
     * that happens.
     */
    suspend fun grantLease(
        pkg: String,
        now: StampedInstant,
        durationMs: Long,
        accumulatedMs: Long,
    ) {
        if (pkg.isEmpty() || durationMs <= 0L) return
        // A duration the app does not offer can only be a bug or a replayed
        // value from somewhere else. Refusing is the direction that costs a
        // gate rather than hands one out.
        if (!LeaseLadder.isOffered(durationMs)) return
        store.updateData { state ->
            val next = LeaseManager.of(state.leasesList.map { it.toLease() })
                .grant(pkg, now, durationMs, accumulatedMs)
                .prune(now)
            state.toBuilder()
                .clearLeases()
                .addAllLeases(next.snapshot().map { it.toProto() })
                .build()
        }
    }

    /**
     * End every lease. Called on cycle rollover, where everything else about
     * a package starts over too.
     *
     * There is no single-package revoke reachable from the UI, and there must
     * not be: a lease the user can cancel early is a lease that costs nothing,
     * and the gate it bought past has already been paid for.
     */
    suspend fun clearLeases() {
        store.updateData { state ->
            if (state.leasesCount == 0) return@updateData state
            state.toBuilder().clearLeases().build()
        }
    }

    /** Housekeeping. No lease changes state because of it. */
    suspend fun pruneLeases(now: StampedInstant) {
        store.updateData { state ->
            val next = LeaseManager.of(state.leasesList.map { it.toLease() }).prune(now)
            if (next.snapshot().size == state.leasesCount) return@updateData state
            state.toBuilder()
                .clearLeases()
                .addAllLeases(next.snapshot().map { it.toProto() })
                .build()
        }
    }

    /**
     * The user's declared horizon per package, snapped to an offered step.
     *
     * The standing preference, not what is in force. Between a widen and the
     * rollover that promotes it those differ, and the engine's per app state
     * is the one that decides friction.
     */
    // ------------------------------------------------------------ reminders

    /** Every reminder not yet dismissed. See [ReminderBook]. */
    val reminders: Flow<List<Reminder>> = store.data.map { s -> s.remindersList.map { it.toReminder() } }

    suspend fun currentReminders(): List<Reminder> = current().remindersList.map { it.toReminder() }

    /**
     * Add a reminder, or refuse it when twenty are pending. The cap is
     * checked inside the transaction, so two adds racing cannot make
     * twenty one. Returns the reminder added, or null when refused.
     */
    suspend fun addReminder(text: String, due: StampedInstant): Reminder? {
        var added: Reminder? = null
        store.updateData { state ->
            val list = state.remindersList.map { it.toReminder() }
            val id = ReminderBook.nextId(list, state.reminderLastId)
            when (val result = ReminderBook.add(list, id, text, due)) {
                ReminderBook.Added.Full -> state
                is ReminderBook.Added.Ok -> {
                    added = result.reminder
                    state.toBuilder()
                        .clearReminders()
                        .addAllReminders(result.list.map { it.toProto() })
                        .setReminderLastId(id)
                        .build()
                }
            }
        }
        return added
    }

    /** Mark [id] fired. It stays in the list, and on the console, until dismissed. */
    suspend fun markReminderFired(id: Long, late: Boolean) {
        store.updateData { state ->
            val next = ReminderBook.fired(state.remindersList.map { it.toReminder() }, id, late)
            state.toBuilder().clearReminders().addAllReminders(next.map { it.toProto() }).build()
        }
    }

    /** The user dismissed [id] on the console. The only thing that removes one. */
    suspend fun dismissReminder(id: Long) {
        store.updateData { state ->
            val next = ReminderBook.dismissed(state.remindersList.map { it.toReminder() }, id)
            state.toBuilder().clearReminders().addAllReminders(next.map { it.toProto() }).build()
        }
    }

    /** The quick-launch rows as stored. Resolve through [QuickLaunch]. */
    val quickLaunch: Flow<QuickLaunch.Selection> = store.data.map {
        QuickLaunch.Selection(stored = it.quickLaunchPackagesList.toList(), chosen = it.quickLaunchChosen)
    }

    /**
     * Edit the quick-launch rows. The only writer of the list.
     *
     * Read-modify-write inside one transaction, resolved from the same state
     * it writes, for the reason `toggleTarget` gives: resolving one end of a
     * read-modify-write and not the other is how the target list inverted a
     * control. [edit] returns null to refuse (full, or a duplicate), which
     * leaves the state untouched. The write prunes apps [isLaunchable] says
     * are gone, and sets the chosen flag every time, including on the write
     * that empties the list.
     */
    suspend fun editQuickLaunch(
        isLaunchable: (String) -> Boolean,
        edit: (List<QuickLaunch.Entry>) -> List<QuickLaunch.Entry>?,
    ) {
        store.updateData { state ->
            val current = QuickLaunch.resolve(
                QuickLaunch.Selection(state.quickLaunchPackagesList.toList(), state.quickLaunchChosen),
            )
            val next = edit(current) ?: return@updateData state
            state.toBuilder()
                .clearQuickLaunchPackages()
                .addAllQuickLaunchPackages(QuickLaunch.pruned(next, isLaunchable))
                .setQuickLaunchChosen(true)
                .build()
        }
    }

    val appHorizons: Flow<Map<String, Long>> = store.data.map { state ->
        state.appHorizonMsMap.mapValues { (_, ms) -> HorizonPolicy.snap(ms) }
    }

    /**
     * Declare a horizon for [pkg].
     *
     * Writes the preference only. Whether it lands now or at the next
     * rollover is the engine's to decide, through [HorizonPolicy], because
     * the engine owns the per app state and rewrites it at every checkpoint.
     * A settings screen writing that state directly would be overwritten
     * inside fifteen seconds.
     */
    suspend fun setAppHorizon(pkg: String, horizonMs: Long) {
        if (pkg.isEmpty()) return
        store.updateData {
            it.toBuilder().putAppHorizonMs(pkg, HorizonPolicy.snap(horizonMs)).build()
        }
    }

    val gateMode: Flow<GatePolicy.GateMode> =
        store.data.map { gateModeFromOrdinal(it.gateModeOrdinal) }

    suspend fun setGateMode(mode: GatePolicy.GateMode) {
        store.updateData { it.toBuilder().setGateModeOrdinal(mode.ordinal).build() }
    }

    /**
     * Extra never-draw-over prefixes. The shipped defaults are applied on top
     * of whatever is stored here, so this list can only widen the set.
     *
     * Guarded like [toggleTarget], inside the same transform that reads the
     * locks: a new prefix that would cover a package with a standing lock is
     * dropped, because a covered package is never drawn over and so its lock
     * is never enforced. See [PrefixLock].
     */
    suspend fun setSensitivePrefixes(prefixes: List<String>, now: StampedInstant) {
        store.updateData { state ->
            val locks = LockRegistry.of(state.locksList.map { it.toLock() })
            val admitted = PrefixLock.admitted(
                stored = state.sensitivePackagePrefixesList,
                requested = prefixes,
                lockedPackages = locks.active(now).map { it.pkg },
            )
            state.toBuilder()
                .clearSensitivePackagePrefixes()
                .addAllSensitivePackagePrefixes(admitted.prefixes)
                .build()
        }
    }

    /**
     * Start or clear the 15 minute pause.
     *
     * Stamped on `elapsedRealtime` and `BOOT_COUNT`, never the wall clock:
     * see [dev.molasses.core.safety.PauseWindow] for why the usual clamp is
     * the wrong tool here.
     */
    suspend fun setPaused(active: Boolean, bootId: Int) {
        store.updateData {
            val b = it.toBuilder()
            if (active) {
                b.setPauseArmed(true)
                    .setPauseStartedElapsedMs(SystemClock.elapsedRealtime())
                    .setPauseStartedBootId(bootId)
            } else {
                b.setPauseArmed(false)
                    .clearPauseStartedElapsedMs()
                    .clearPauseStartedBootId()
            }
            b.build()
        }
    }

    /**
     * Debug builds only. Sets one package's accumulated time and tier index
     * directly, bypassing the engine's monotonic guard, so the stall tiers can
     * be exercised without first clearing a gate.
     *
     * Bumping the nonce is the part that makes this work. The engine keeps its
     * per-app state in memory and writes it back on a 15 s checkpoint, so an
     * edit made here alone would be silently overwritten. The service watches
     * the nonce and rebuilds the engine when it moves.
     */
    suspend fun setAppStateForDebug(pkg: String, accumulatedMs: Long, tierIndex: Int) {
        store.updateData { old ->
            val existing = old.perAppMap[pkg] ?: AppState.getDefaultInstance()
            old.toBuilder()
                .putPerApp(
                    pkg,
                    existing.toBuilder()
                        .setAccumulatedMs(accumulatedMs.coerceAtLeast(0))
                        .setTierIndex(tierIndex.coerceAtLeast(0))
                        // Grant a lease mark past the accumulated total being
                        // set, so the editor does not leave the app instantly
                        // overdue and ratcheting. A debug build putting a
                        // package at tier 4 wants to see tier 4's stall, not
                        // tier 4 plus whatever the ratchet adds in the time it
                        // takes to look.
                        .setLeasesTaken(maxOf(existing.leasesTaken, 1))
                        .setLeaseUntilAccumulatedMs(
                            accumulatedMs.coerceAtLeast(0) + LeaseLadder.MAX_MS,
                        )
                        .build(),
                )
                .setDebugOverrideNonce(old.debugOverrideNonce + 1)
                .build()
        }
    }

    /**
     * Ask the accessibility service to arm the touch sink briefly over
     * whatever is on screen. The settings Activity has no service token and so
     * cannot open a trusted overlay itself.
     */
    suspend fun requestStallPreview() {
        store.updateData { it.toBuilder().setPreviewStallNonce(it.previewStallNonce + 1).build() }
    }

    suspend fun clearAll() {
        store.updateData { CycleStateSerializer.defaultValue }
    }

    companion object {
        /** Bump alongside a new branch in [migrate]. */
        const val SCHEMA_VERSION = 1
    }
}

// ------------------------------------------------------------------ mapping

/**
 * Stored as an ordinal, resolved here and nowhere else.
 *
 * An out of range value is COUNTDOWN rather than a throw: a file written by a
 * newer build must still open, and the default is the mode that asks least, so
 * falling back to it cannot lock a user out of an app by accident.
 */
fun gateModeFromOrdinal(ordinal: Int): GatePolicy.GateMode =
    GatePolicy.GateMode.entries.getOrElse(ordinal) { GatePolicy.GateMode.COUNTDOWN }

fun AppSnapshot.toProto(): AppState = AppState.newBuilder()
    .setAccumulatedMs(accumulatedMs)
    .setTierIndex(tierIndex)
    .setLeasesTaken(leasesTaken)
    .setLeaseUntilAccumulatedMs(leaseUntilAccumulatedMs)
    .setHorizonMs(horizonMs)
    .setPendingHorizonMs(pendingHorizonMs)
    .setPenaltyMs(penaltyMs)
    .apply { penaltyAnchorMs?.let { setPenaltyAnchorMs(it) } }
    .build()

fun CycleState.toEngineSnapshot(): EngineSnapshot = EngineSnapshot(
    perApp = perAppMap.mapValues { (pkg, a) ->
        AppSnapshot(
            pkg = pkg,
            accumulatedMs = a.accumulatedMs,
            tierIndex = a.tierIndex,
            leasesTaken = a.leasesTaken,
            // Zero is read as zero here, unlike the field it replaced. A
            // stored 0 now means "no lease taken this cycle", which is the
            // correct reading for a fresh app, and substituting a tier width
            // would hand every restored package a lease it never took.
            leaseUntilAccumulatedMs = a.leaseUntilAccumulatedMs,
            penaltyMs = a.penaltyMs,
            // Absent on every file written before the anchor was persisted.
            // Null is "no anchor", not zero: see AppSnapshot.penaltyAnchorMs.
            penaltyAnchorMs = if (a.hasPenaltyAnchorMs()) a.penaltyAnchorMs else null,
            // Zero is the migration: every install written before the horizon
            // existed reads it, and it has to mean the default rather than
            // the minimum clamped up from nothing.
            horizonMs = HorizonPolicy.of(a.horizonMs, a.pendingHorizonMs).horizonMs,
            pendingHorizonMs = HorizonPolicy.of(a.horizonMs, a.pendingHorizonMs).pendingHorizonMs,
        )
    },
    cycleAnchorWallMs = cycleAnchorWallMs,
    lastTargetUseWallMs = lastTargetUseWallMs,
    cycleAnchorElapsedMs = cycleAnchorElapsedMs,
    cycleAnchorBootId = cycleAnchorBootId,
    lastTargetUseElapsedMs = lastTargetUseElapsedMs,
    lastTargetUseBootId = lastTargetUseBootId,
)

/**
 * The pause stamp as [dev.molasses.core.safety.PauseWindow] wants it.
 *
 * `wallMs` is set to 1 purely as the "is set" marker; nothing reads its value,
 * because a pause is measured on the monotonic clock alone. Using the real
 * wall time here would invite a future caller to compare it, which is the bug
 * this deliberately makes impossible.
 */
fun CycleState.pauseInstant(): StampedInstant =
    if (!pauseArmed) {
        StampedInstant.UNSET
    } else {
        StampedInstant(wallMs = 1L, elapsedMs = pauseStartedElapsedMs, bootId = pauseStartedBootId)
    }

/** The cycle anchor as the engine and [CycleWindow] want it. */
fun CycleState.anchorInstant(): StampedInstant = StampedInstant(
    wallMs = cycleAnchorWallMs,
    elapsedMs = cycleAnchorElapsedMs,
    bootId = cycleAnchorBootId,
)
