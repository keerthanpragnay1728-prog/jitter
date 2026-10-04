package dev.molasses.monitor

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.molasses.core.friction.CycleRollover
import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType
import dev.molasses.core.session.TargetScope
import dev.molasses.core.time.ClockTamperClamp
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.ForegroundIntervals
import dev.molasses.core.time.ForegroundReplay
import dev.molasses.core.time.StampedInstant
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.datastore.anchorInstant
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
        // Before anything reads the policy, including the engine seed below.
        store.migrate()

        val state = store.current()
        val bootId = readBootCount()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val now = StampedInstant(wallMs = nowWall, elapsedMs = nowElapsed, bootId = bootId)

        val bootChanged = state.lastSeenWallMs != 0L && state.bootId != bootId
        // Through resolve, like every reader of the tracked set. The stored
        // list is empty on a fresh install while the defaults are being
        // tracked, and read raw it made this credit nothing at all after a
        // process death there. See CLAUDE.md, "The stored target list is not
        // the tracked set".
        val targets = TargetScope.resolve(
            TargetScope.Selection(stored = state.targetPackagesList, chosen = state.targetsChosen),
            DEFAULT_TARGETS,
        )

        // Nothing to reconcile on a first run.
        if (state.lastSeenWallMs == 0L) {
            // A first run leaves the cycle unanchored on purpose. The anchor
            // is defined as the first target-app foreground, and nothing has
            // been in the foreground yet.
            store.applyReconciliation(
                creditedByPkg = emptyMap(),
                lastTargetUseWallMs = null,
                bootId = bootId,
                anchor = state.anchorInstant(),
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
            // No stream, no credit. The open session would otherwise be
            // credited the whole window with nothing to bound it, which is
            // every minute the process was dead, as friction time that is
            // never refunded.
            val events = queryEvents(windowStart, nowWall)
            if (events == null) {
                Log.w(TAG, "no usage events to replay; crediting nothing for ${state.openSessionPkg}")
            } else {
                replay = ForegroundReplay.replay(
                    events = events,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    targets = targets,
                    interactiveNow = isInteractive(),
                    assumeOpenPkg = state.openSessionPkg,
                )
            }
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
            anchor = state.anchorInstant(),
        )

        val reconciled = store.current().toEngineSnapshot()
        val rolled = maybeRollCycleOnConnect(reconciled, now)

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
     * a six-hour window usually ends while the phone is doing something else. The
     * engine only checks on foreground entry, so the check also has to happen
     * here, using the clamped elapsed time rather than the raw wall delta.
     */
    private fun maybeRollCycleOnConnect(
        snapshot: EngineSnapshot,
        now: StampedInstant,
    ): EngineSnapshot {
        val reference = StampedInstant(
            wallMs = snapshot.cycleAnchorWallMs,
            elapsedMs = snapshot.cycleAnchorElapsedMs,
            bootId = snapshot.cycleAnchorBootId,
        )
        // Same rule as the engine tick, same helper. The clamp lives inside
        // CycleWindow now, so there is no separate tamper suppression here:
        // a wall clock moved forward credits nothing towards the age and the
        // cycle simply is not due.
        if (!CycleWindow.isDue(reference, now)) return snapshot

        ledger.log(
            "",
            EventType.RECONCILED,
            "cycle rollover on connect age=${CycleWindow.ageMs(reference, now)}ms",
        )
        // Unanchored, not anchored at now. Nothing is in the foreground at
        // service-connect time, and the first target-app entry after this is
        // what starts the new cycle.
        //
        // Each app carries over through the same function the engine's
        // rollover uses. This used to empty the map, which dropped a pending
        // horizon widen and made it wait a whole extra cycle.
        return snapshot.copy(
            perApp = CycleRollover.carryOver(snapshot.perApp),
            cycleAnchorWallMs = 0,
            cycleAnchorElapsedMs = 0,
            cycleAnchorBootId = 0,
        )
    }

    /**
     * Every package's events and the device's, through the reader the ledger
     * uses. Every package and not only the targets, because another app's
     * resume is what bounds a target's interval when its own close never
     * arrived. See `ForegroundIntervals`.
     *
     * @return null when the stream could not be read, so the caller credits
     *   nothing rather than an interval with no bound.
     */
    private fun queryEvents(beginWallMs: Long, endWallMs: Long): List<ForegroundIntervals.Event>? {
        val manager = usage ?: return null
        return try {
            manager.foregroundEvents(beginWallMs, endWallMs)
        } catch (e: SecurityException) {
            // PACKAGE_USAGE_STATS not granted. A mid-session death then loses
            // that session's tail, which the 15 s checkpoint bounds.
            Log.w(TAG, "no usage-stats access; skipping replay", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed", e)
            null
        }
    }

    /** Unknown reads as not interactive, so an open interval is not run to now on a guess. */
    private fun isInteractive(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    private fun readBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }.getOrDefault(0)

    private companion object { const val TAG = "Molasses.Reconciler" }
}
