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
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
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
                .setResetPolicy(snapshot.resetPolicy.toProto())
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
                        .setTierUnlockedUntilMs(
                            maxOf(existing.tierUnlockedUntilMs, TierPolicy.TIER_WIDTH_MS),
                        )
                        .build(),
                )
            }
            b.build()
        }
    }

    suspend fun setResetPolicy(policy: CycleResetPolicy) {
        store.updateData { it.toBuilder().setResetPolicy(policy.toProto()).build() }
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

    suspend fun setTargets(packages: List<String>) {
        store.updateData {
            it.toBuilder().clearTargetPackages().addAllTargetPackages(packages).build()
        }
    }

    /**
     * Push one submitted line onto the command history.
     *
     * The cap and the dedupe happen inside `updateData` rather than in the
     * caller, so two commands submitted in the same frame cannot both read a
     * nineteen entry list and write a twenty first.
     *
     * @param confirmation true when this Enter was the second Enter on a long
     *   lock. See [dev.molasses.core.command.CommandHistory].
     */
    suspend fun recordCommand(line: String, confirmation: Boolean) {
        if (confirmation || line.isBlank()) return
        store.updateData {
            val next = CommandHistory.record(it.commandHistoryList, line, confirmation)
            it.toBuilder().clearCommandHistory().addAllCommandHistory(next).build()
        }
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

    suspend fun setAlternativeChallenge(enabled: Boolean) {
        store.updateData { it.toBuilder().setAlternativeChallenge(enabled).build() }
    }

    /**
     * Extra never-draw-over prefixes. The shipped defaults are applied on top
     * of whatever is stored here, so this list can only widen the set.
     */
    suspend fun setSensitivePrefixes(prefixes: List<String>) {
        store.updateData {
            it.toBuilder()
                .clearSensitivePackagePrefixes()
                .addAllSensitivePackagePrefixes(
                    prefixes.map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() },
                )
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
                        // Unlock up to the start of the tier being set, so the
                        // next scroll lands on that tier's stall rather than
                        // immediately re-gating.
                        .setTierUnlockedUntilMs(
                            TierPolicy.entryAtMs(tierIndex.coerceAtLeast(0) + 1),
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

    /** Debug builds only. Zero restores the curve. */
    suspend fun setPinnedStallMs(ms: Long) {
        store.updateData { it.toBuilder().setDebugPinnedStallMs(ms.coerceAtLeast(0)).build() }
    }

    /**
     * Debug builds only. Companion to [setPinnedStallMs]: that one pins the
     * duration, this one pins the probability. Both are needed for a segment D
     * capture, because at a 10% band the sample count is otherwise impractical.
     *
     * Zero restores the curve; 1 to 100 forces that percentage.
     */
    suspend fun setForcedProbabilityPct(pct: Int) {
        store.updateData {
            it.toBuilder().setDebugForcedProbabilityPct(pct.coerceIn(0, 100)).build()
        }
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

fun CycleResetPolicy.toProto(): CycleResetPolicyProto = when (this) {
    CycleResetPolicy.ABSTINENCE_6H -> CycleResetPolicyProto.ABSTINENCE_6H
    CycleResetPolicy.FIXED_WINDOW_6H -> CycleResetPolicyProto.FIXED_WINDOW_6H
}

fun CycleResetPolicyProto.toModel(): CycleResetPolicy = when (this) {
    CycleResetPolicyProto.FIXED_WINDOW_6H -> CycleResetPolicy.FIXED_WINDOW_6H
    else -> CycleResetPolicy.ABSTINENCE_6H
}

fun AppSnapshot.toProto(): AppState = AppState.newBuilder()
    .setAccumulatedMs(accumulatedMs)
    .setTierIndex(tierIndex)
    .setGatesCleared(gatesCleared)
    .setTierUnlockedUntilMs(tierUnlockedUntilMs)
    .setPenaltyMs(penaltyMs)
    .build()

fun CycleState.toEngineSnapshot(): EngineSnapshot = EngineSnapshot(
    perApp = perAppMap.mapValues { (pkg, a) ->
        AppSnapshot(
            pkg = pkg,
            accumulatedMs = a.accumulatedMs,
            tierIndex = a.tierIndex,
            gatesCleared = a.gatesCleared,
            tierUnlockedUntilMs = if (a.tierUnlockedUntilMs == 0L) {
                TierPolicy.TIER_WIDTH_MS
            } else {
                a.tierUnlockedUntilMs
            },
            // Not persisted: a gate owed at the moment of a crash is re-derived
            // on the next scroll, which is both simpler and correct -- the
            // accumulated total is what decides whether a gate is owed.
            gatePending = false,
            penaltyMs = a.penaltyMs,
        )
    },
    cycleAnchorWallMs = cycleAnchorWallMs,
    lastTargetUseWallMs = lastTargetUseWallMs,
    resetPolicy = resetPolicy.toModel(),
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
