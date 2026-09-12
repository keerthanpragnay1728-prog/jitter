package dev.molasses.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType
import dev.molasses.core.time.ClockTamperClamp
import dev.molasses.core.time.ForegroundReplay
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.toEngineSnapshot
import dev.molasses.engine.FrictionLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crash and reboot reconciliation. Runs once in `onServiceConnected()`,
 * before the engine accepts any live event.
 *
 * The problem it solves: the accumulated-time ticker lives in memory. If the
 * process is killed while Instagram is in the foreground -- by the OOM killer,
 * by the user, or deliberately, to farm free minutes -- that time is simply
 * gone unless it is rebuilt from a source the app does not control.
 *
 * Steps, in the order the brief specifies:
 *  1. read `Settings.Global.BOOT_COUNT` as `bootId`
 *  2. if a session was open, replay `UsageStatsManager.queryEvents()` from the
 *     last checkpoint and sum real foreground time
 *  3. if `bootId` changed, `elapsedRealtime` has reset: trust wall clock only,
 *     and mark the row
 *  4. otherwise compare the two clock deltas and clamp on disagreement
 *  5. checkpointing every 15 s (in the service) keeps step 2's scan short
 */
class ForegroundReconciler(
    context: Context,
    private val store: CycleStateStore,
    private val ledger: FrictionLedger,
) {
    private val appContext = context.applicationContext
    private val usage: UsageStatsManager? =
        appContext.getSystemService(UsageStatsManager::class.java)

    data class Outcome(
        val snapshot: EngineSnapshot,
        val bootId: Int,
        val creditedMs: Long,
        val tampered: Boolean,
        val bootChanged: Boolean,
        val note: String,
    )

    suspend fun reconcile(): Outcome = withContext(Dispatchers.IO) {
        val state = store.current()
        val bootId = readBootCount()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val bootChanged = state.lastSeenWallMs != 0L && state.bootId != bootId
        val targets = state.targetPackagesList.toSet()

        // Nothing to reconcile on a first run.
        if (state.lastSeenWallMs == 0L) {
            store.applyReconciliation(
                creditedByPkg = emptyMap(),
                lastTargetUseWallMs = null,
                bootId = bootId,
                cycleAnchorWallMs = if (state.cycleAnchorWallMs == 0L) nowWall else state.cycleAnchorWallMs,
            )
            return@withContext Outcome(
                snapshot = store.current().toEngineSnapshot(),
                bootId = bootId,
                creditedMs = 0,
                tampered = false,
                bootChanged = false,
                note = "first run",
            )
        }

        // Steps 3 and 4.
        val verdict = ClockTamperClamp.evaluate(
            ClockTamperClamp.Gap(
                lastSeenWallMs = state.lastSeenWallMs,
                lastSeenElapsedMs = state.lastSeenElapsedMs,
                nowWallMs = nowWall,
                nowElapsedMs = nowElapsed,
                bootIdChanged = bootChanged,
            ),
        )
        if (verdict.tampered || bootChanged) {
            ledger.log(
                "",
                EventType.CLOCK_WARP,
                "bootChanged=$bootChanged tampered=${verdict.tampered} " +
                    "credited=${verdict.creditedMs} reason=${verdict.reason}",
            )
        }

        // Step 2: only when a session was actually open.
        var replay: ForegroundReplay.Result? = null
        if (state.openSessionPkg.isNotEmpty()) {
            // The window is bounded by the clamp: even if the wall clock says
            // a week passed, never scan (or credit) more than the monotonic
            // clock allows.
            val windowStart = state.lastSeenWallMs
            val windowEnd = minOf(nowWall, windowStart + verdict.creditedMs)
                .coerceAtLeast(windowStart)
            replay = ForegroundReplay.replay(
                transitions = queryTransitions(windowStart, nowWall, targets),
                windowStartMs = windowStart,
                windowEndMs = windowEnd,
                targets = targets,
                assumeOpenPkg = state.openSessionPkg,
            )
        }

        val credited = replay?.foregroundMsByPkg ?: emptyMap()
        if (credited.isNotEmpty()) {
            ledger.log(
                state.openSessionPkg,
                EventType.RECONCILED,
                "credited=$credited window=${verdict.creditedMs}ms reason=${verdict.reason}",
            )
        }

        // Crediting time never moves the cycle anchor; only a rollover does.
        store.applyReconciliation(
            creditedByPkg = credited,
            lastTargetUseWallMs = replay?.lastTargetUseWallMs,
            bootId = bootId,
            cycleAnchorWallMs = if (state.cycleAnchorWallMs == 0L) nowWall else state.cycleAnchorWallMs,
        )

        val reconciled = store.current().toEngineSnapshot()
        val rolled = maybeRollCycleOnConnect(reconciled, nowWall, verdict)

        Outcome(
            snapshot = rolled,
            bootId = bootId,
            creditedMs = credited.values.sum(),
            tampered = verdict.tampered,
            bootChanged = bootChanged,
            note = verdict.reason,
        )
    }

    /**
     * A cycle can come due while the process is dead -- the common case, since
     * six hours of abstinence usually means six hours of not running. The
     * engine only checks on foreground entry, so the check also has to happen
     * here, using the clamped elapsed time rather than the raw wall delta.
     */
    private fun maybeRollCycleOnConnect(
        snapshot: EngineSnapshot,
        nowWall: Long,
        verdict: ClockTamperClamp.Verdict,
    ): EngineSnapshot {
        val due = when (snapshot.resetPolicy) {
            CycleResetPolicy.ABSTINENCE_6H ->
                snapshot.lastTargetUseWallMs > 0 &&
                    nowWall - snapshot.lastTargetUseWallMs >= CycleResetPolicy.WINDOW_MS &&
                    !verdict.tampered
            CycleResetPolicy.FIXED_WINDOW_6H ->
                snapshot.cycleAnchorWallMs > 0 &&
                    nowWall - snapshot.cycleAnchorWallMs >= CycleResetPolicy.WINDOW_MS &&
                    !verdict.tampered
        }
        if (!due) return snapshot

        // Step 4, second half: the anchor may not move forward by more than
        // the clamped monotonic delta in a single pass. A rollover is already
        // suppressed under detected tampering, so this is a second line of
        // defence rather than the main one.
        val anchor = if (snapshot.cycleAnchorWallMs == 0L) {
            nowWall
        } else {
            minOf(nowWall, snapshot.cycleAnchorWallMs + verdict.maxAnchorAdvanceMs)
        }
        ledger.log("", EventType.RECONCILED, "cycle rollover on connect policy=${snapshot.resetPolicy}")
        return snapshot.copy(perApp = emptyMap(), cycleAnchorWallMs = anchor)
    }

    /**
     * Map `UsageEvents` onto the pure [ForegroundReplay.Transition] type. The
     * arithmetic lives in `core/time` so it can be unit-tested; `UsageEvents`
     * cannot be constructed on the JVM.
     */
    private fun queryTransitions(
        beginWallMs: Long,
        endWallMs: Long,
        targets: Set<String>,
    ): List<ForegroundReplay.Transition> {
        val manager = usage ?: return emptyList()
        val out = mutableListOf<ForegroundReplay.Transition>()
        try {
            val events: UsageEvents = manager.queryEvents(beginWallMs, endWallMs)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (pkg !in targets) continue
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundReplay.Kind.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundReplay.Kind.PAUSED
                    else -> null
                } ?: continue
                out += ForegroundReplay.Transition(pkg, kind, event.timeStamp)
            }
        } catch (e: SecurityException) {
            // PACKAGE_USAGE_STATS not granted. Recoverable: without it a
            // mid-session death simply loses that session's tail, which the
            // 15 s checkpoint already bounds.
            Log.w(TAG, "no usage-stats access; skipping replay", e)
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed", e)
        }
        return out
    }

    private fun readBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }.getOrDefault(0)

    private companion object { const val TAG = "Molasses.Reconciler" }
}
