package dev.molasses.ui.settings

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.molasses.core.diag.RouteTally
import dev.molasses.core.diag.ServiceHealth
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.friction.HorizonPolicy
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.ui.FontScale
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.toEngineSnapshot
import dev.molasses.data.datastore.toModel
import dev.molasses.data.db.GateOutcomeRow
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.db.UsageEventEntity
import dev.molasses.data.repo.InstalledApp
import dev.molasses.data.repo.PermissionState
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.monitor.ServiceDiagnostics
import dev.molasses.sensing.Thresholds
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live engine and service state, for the debug screen.
 *
 * Deliberately separate from the ledger's UsageStats figures. Those come from
 * the platform and say nothing about whether this app's engine accumulated
 * anything: a ledger showing two hours of Instagram alongside an
 * accumulatedMs of zero is the exact signature of a broken event path, and
 * the two numbers have to be visible side by side for that to be readable.
 */
data class EngineDiagnostics(
    val health: ServiceHealth,
    val stuckStarting: Boolean,
    val startupNote: String?,
    /** Non-null while a call-detection path is degraded. See ServiceDiagnostics. */
    val panicPathNote: String?,
    /** Informational, not a fault. See ServiceDiagnostics.panicSecondaryNote. */
    val panicSecondaryNote: String?,
    /** Non-null when a gate window could not be added. See ServiceDiagnostics. */
    val overlayFailureNote: String?,
    val heartbeatAgeMs: Long?,
    val accessibilityEnabled: Boolean,
    val openSessionPkg: String?,
    val cycleAnchorWallMs: Long,
    val cycleRemainingMs: Long,
    val resetPolicy: CycleResetPolicy,
    val appliedPackageNames: List<String>,
    val usedTargetFallback: Boolean,
    val routes: List<Pair<String, RouteTally.PackageTally>>,
    val overflowedPackages: Long,
)

data class LadderRow(
    val pkg: String,
    val accumulatedMs: Long,
    val tierIndex: Int,
    val leasesTaken: Int,
    val leaseUntilAccumulatedMs: Long,
    /** Never added to [accumulatedMs]. True time and effective time are two numbers. */
    val penaltyMs: Long,
)

/** Requested vs. actual armed duration, parsed back out of the ledger. */
data class StallLatency(
    val requestedMs: Long,
    val actualMs: Long,
    val release: String,
) {
    val overshootMs: Long get() = actualMs - requestedMs
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val store: CycleStateStore,
    private val dao: UsageEventDao,
) : ViewModel() {

    private val _permissions = MutableStateFlow(repo.permissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installed: StateFlow<List<InstalledApp>> = _installed.asStateFlow()

    val targets: StateFlow<List<String>> = repo.targets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resetPolicy: StateFlow<CycleResetPolicy> = repo.resetPolicy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleResetPolicy.DEFAULT)

    /**
     * What each tracked app's horizon will be once the standing preference is
     * applied to the state actually in force.
     *
     * Composed with [HorizonPolicy.request], which is the same function the
     * engine uses, so the screen and the engine cannot disagree about whether
     * a change lands now or waits. Between a widen and the rollover that
     * promotes it, this is a state with something pending, which is exactly
     * what the row has to show.
     */
    val horizons: StateFlow<Map<String, HorizonPolicy.State>> = combine(
        store.data,
        repo.appHorizons,
    ) { state, preferences ->
        val packages = state.perAppMap.keys + preferences.keys
        packages.associateWith { pkg ->
            val inForce = state.perAppMap[pkg]
                ?.let { HorizonPolicy.of(it.horizonMs, it.pendingHorizonMs) }
                ?: HorizonPolicy.State(FrictionCurve.DEFAULT_HORIZON_MS)
            preferences[pkg]?.let { HorizonPolicy.request(inForce, it) } ?: inForce
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val gateMode: StateFlow<GatePolicy.GateMode> = repo.gateMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GatePolicy.GateMode.COUNTDOWN,
        )

    /** The user's additions only. The shipped defaults are not editable. */
    val sensitivePrefixes: StateFlow<List<String>> = repo.sensitivePrefixes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fontScale: StateFlow<FontScale> = repo.fontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontScale.DEFAULT)

    val pauseRemainingMs: StateFlow<Long> = repo.pauseRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * The armed locks.
     *
     * The target list renders locked apps from this. There is deliberately no
     * unlock on the view model either: a lock that can be cleared from the
     * screen that arms it is a lock that will be cleared, at the exact moment
     * it is working.
     */
    val locks: StateFlow<LockRegistry> = repo.locks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockRegistry())

    /** Milliseconds left on [pkg], evaluated against a fresh stamp. */
    fun lockRemainingMs(pkg: String): Long =
        locks.value.remainingMs(pkg, repo.nowStamped())

    /**
     * Arm or extend a lock from the scrubber.
     *
     * The caller has already run [dev.molasses.core.lock.LockRequest], which
     * is the same evaluation the typed path uses, so the confirmation step
     * cannot be skipped by coming through here. The store is still
     * authoritative: its extend-only compare runs inside the transform.
     */
    fun armLock(pkg: String, durationMs: Long) {
        viewModelScope.launch { repo.armLock(pkg, durationMs, LockReason.BLOCK) }
    }

    /**
     * Polled rather than pushed. ServiceDiagnostics is a plain object written
     * from the accessibility callback thread; a Flow over it would need a
     * change signal the service does not have, and a one second poll on a
     * debug screen nobody leaves open is cheaper than inventing one.
     */
    val engineDiagnostics: StateFlow<EngineDiagnostics> = combine(
        store.data,
        flow {
            while (true) {
                emit(Unit)
                delay(1_000L)
            }
        },
    ) { state, _ ->
        val anchor = StampedInstant(
            wallMs = state.cycleAnchorWallMs,
            elapsedMs = state.cycleAnchorElapsedMs,
            bootId = state.cycleAnchorBootId,
        )
        EngineDiagnostics(
            health = ServiceDiagnostics.health(),
            stuckStarting = ServiceDiagnostics.isStuckStarting(),
            startupNote = ServiceDiagnostics.startupNote,
            panicPathNote = ServiceDiagnostics.panicPathNote,
            panicSecondaryNote = ServiceDiagnostics.panicSecondaryNote,
            overlayFailureNote = ServiceDiagnostics.overlayFailureNote,
            heartbeatAgeMs = ServiceDiagnostics.heartbeatAgeMs(),
            accessibilityEnabled = repo.permissionState().accessibility,
            openSessionPkg = state.openSessionPkg.ifEmpty { null },
            cycleAnchorWallMs = state.cycleAnchorWallMs,
            cycleRemainingMs = CycleWindow.remainingMs(
                anchor = anchor,
                now = StampedInstant(
                    wallMs = System.currentTimeMillis(),
                    elapsedMs = SystemClock.elapsedRealtime(),
                    bootId = state.bootId,
                ),
            ),
            resetPolicy = state.resetPolicy.toModel(),
            appliedPackageNames = ServiceDiagnostics.appliedPackageNames,
            usedTargetFallback = ServiceDiagnostics.usedTargetFallback,
            routes = ServiceDiagnostics.tallySnapshot(),
            overflowedPackages = ServiceDiagnostics.overflowedPackages,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EngineDiagnostics(
            health = ServiceHealth.NEVER_CONNECTED,
            stuckStarting = false,
            startupNote = null,
            panicPathNote = null,
            panicSecondaryNote = null,
            overlayFailureNote = null,
            heartbeatAgeMs = null,
            accessibilityEnabled = false,
            openSessionPkg = null,
            cycleAnchorWallMs = 0,
            cycleRemainingMs = 0,
            resetPolicy = CycleResetPolicy.DEFAULT,
            appliedPackageNames = emptyList(),
            usedTargetFallback = false,
            routes = emptyList(),
            overflowedPackages = 0,
        ),
    )

    val ladder: StateFlow<List<LadderRow>> = store.data
        .map { state ->
            state.toEngineSnapshot().perApp.values
                .map {
                    LadderRow(
                        pkg = it.pkg,
                        accumulatedMs = it.accumulatedMs,
                        tierIndex = it.tierIndex,
                        leasesTaken = it.leasesTaken,
                        leaseUntilAccumulatedMs = it.leaseUntilAccumulatedMs,
                        penaltyMs = it.penaltyMs,
                    )
                }
                .sortedByDescending { it.accumulatedMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ledger: StateFlow<List<UsageEventEntity>> = dao.recent(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _latency = MutableStateFlow<List<StallLatency>>(emptyList())
    val latency: StateFlow<List<StallLatency>> = _latency.asStateFlow()

    private val _gateOutcomes = MutableStateFlow<List<GateOutcomeRow>>(emptyList())
    val gateOutcomes: StateFlow<List<GateOutcomeRow>> = _gateOutcomes.asStateFlow()

    /**
     * Both threshold sets, so the debug screen can show which pipeline is
     * running against numbers that were actually measured for it.
     */
    val thresholdSets: List<Thresholds> = listOf(Thresholds.IIR, Thresholds.FUSED)

    fun refresh() {
        _permissions.value = repo.permissionState()
        viewModelScope.launch {
            _installed.value = withContext(Dispatchers.IO) { repo.installedApps() }
            _latency.value = withContext(Dispatchers.IO) { parseLatencies() }
            _gateOutcomes.value = withContext(Dispatchers.IO) { dao.gateOutcomesByPath() }
        }
    }

    /**
     * The whole feasibility question is whether the observed stall latency is
     * tolerable, so this parses the STALL_ARMED rows back out rather than
     * relying on a claim about what was requested.
     */
    private suspend fun parseLatencies(): List<StallLatency> =
        dao.stallMetas(500).mapNotNull { meta ->
            meta ?: return@mapNotNull null
            val requested = Regex("requestedMs=(\\d+)").find(meta)?.groupValues?.get(1)?.toLongOrNull()
            val actual = Regex("actualMs=(-?\\d+)").find(meta)?.groupValues?.get(1)?.toLongOrNull()
            val release = Regex("release=(\\S+)").find(meta)?.groupValues?.get(1) ?: "?"
            if (requested == null || actual == null) null
            else StallLatency(requested, actual, release)
        }

    fun toggleTarget(pkg: String) {
        viewModelScope.launch {
            val current = targets.value.toMutableList()
            if (!current.remove(pkg)) current += pkg
            repo.setTargets(current)
        }
    }

    fun setResetPolicy(policy: CycleResetPolicy) {
        viewModelScope.launch { repo.setResetPolicy(policy) }
    }

    /**
     * Declare a horizon. The engine decides whether it lands now or waits.
     *
     * Stepped from whatever the row is showing, which is the pending value
     * when there is one: pressing minus twice after widening to sixty has to
     * walk back down from sixty, not from the eighteen still in force.
     */
    fun setAppHorizon(pkg: String, horizonMs: Long) {
        viewModelScope.launch { repo.setAppHorizon(pkg, horizonMs) }
    }

    fun setGateMode(mode: GatePolicy.GateMode) {
        viewModelScope.launch { repo.setGateMode(mode) }
    }

    /**
     * Debug builds only. See [CycleStateStore.setAppStateForDebug]. The UI that
     * calls this is behind `DebugSurface.ENABLED`, so it is unreachable in a
     * release variant.
     */
    fun setAppStateForDebug(pkg: String, accumulatedMs: Long, tierIndex: Int) {
        viewModelScope.launch { store.setAppStateForDebug(pkg, accumulatedMs, tierIndex) }
    }

    /**
     * Ask the accessibility service to arm the sink over this screen. The
     * Activity cannot open a trusted overlay itself.
     */
    fun requestStallPreview() {
        viewModelScope.launch { store.requestStallPreview() }
    }

    fun setFontScale(scale: FontScale) {
        viewModelScope.launch { repo.setFontScale(scale) }
    }

    fun setSensitivePrefixes(prefixes: List<String>) {
        viewModelScope.launch { repo.setSensitivePrefixes(prefixes) }
    }

    /**
     * Start or end the 15 minute pause. Suppresses every overlay; does not
     * touch accumulated time or tier, so this is an escape hatch and not a
     * friction holiday.
     */
    /**
     * Turn the accessibility service off, for good, until the user turns it
     * back on from Android Settings.
     *
     * The Activity cannot call `disableSelf()`; only the service can. So this
     * is a request the service observes, exactly like the stall preview.
     */
    fun requestDisable() {
        viewModelScope.launch { store.requestDisable() }
    }

    fun setPaused(active: Boolean) {
        viewModelScope.launch { repo.setPaused(active) }
    }

    /** Debug builds only. Zero restores the curve. */
    fun setPinnedStallMs(ms: Long) {
        viewModelScope.launch { store.setPinnedStallMs(ms) }
    }

    /** Debug builds only. Zero restores the curve; 1..100 forces a percentage. */
    fun setForcedProbabilityPct(pct: Int) {
        viewModelScope.launch { store.setForcedProbabilityPct(pct) }
    }

    fun clearLedger() {
        viewModelScope.launch { withContext(Dispatchers.IO) { dao.clear() } }
    }

    fun resetAllState() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.clear() }
            store.clearAll()
        }
    }
}
