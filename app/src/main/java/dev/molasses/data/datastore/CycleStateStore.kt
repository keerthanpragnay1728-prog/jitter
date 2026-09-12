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
import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
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
                .setLastTargetUseWallMs(snapshot.lastTargetUseWallMs)
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
        cycleAnchorWallMs: Long,
    ) {
        store.updateData { old ->
            val b = old.toBuilder()
                .setBootId(bootId)
                .setLastSeenWallMs(System.currentTimeMillis())
                .setLastSeenElapsedMs(SystemClock.elapsedRealtime())
                .setCycleAnchorWallMs(cycleAnchorWallMs)
                .setOpenSessionPkg("")
                .clearOpenSessionStartWallMs()
                .clearOpenSessionStartElapsedMs()
            lastTargetUseWallMs?.let { b.setLastTargetUseWallMs(it) }
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

    suspend fun setTargets(packages: List<String>) {
        store.updateData {
            it.toBuilder().clearTargetPackages().addAllTargetPackages(packages).build()
        }
    }

    suspend fun setAlternativeChallenge(enabled: Boolean) {
        store.updateData { it.toBuilder().setAlternativeChallenge(enabled).build() }
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

    suspend fun clearAll() {
        store.updateData { CycleStateSerializer.defaultValue }
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
        )
    },
    cycleAnchorWallMs = cycleAnchorWallMs,
    lastTargetUseWallMs = lastTargetUseWallMs,
    resetPolicy = resetPolicy.toModel(),
)
