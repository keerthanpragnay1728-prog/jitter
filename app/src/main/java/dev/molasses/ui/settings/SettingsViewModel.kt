package dev.molasses.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.toEngineSnapshot
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.db.UsageEventEntity
import dev.molasses.data.repo.InstalledApp
import dev.molasses.data.repo.PermissionState
import dev.molasses.data.db.GateOutcomeRow
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.sensing.Thresholds
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LadderRow(
    val pkg: String,
    val accumulatedMs: Long,
    val tierIndex: Int,
    val gatesCleared: Int,
    val tierUnlockedUntilMs: Long,
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

    val alternativeChallenge: StateFlow<Boolean> = repo.alternativeChallenge
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The user's additions only. The shipped defaults are not editable. */
    val sensitivePrefixes: StateFlow<List<String>> = repo.sensitivePrefixes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pauseRemainingMs: StateFlow<Long> = repo.pauseRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val ladder: StateFlow<List<LadderRow>> = store.data
        .map { state ->
            state.toEngineSnapshot().perApp.values
                .map {
                    LadderRow(
                        pkg = it.pkg,
                        accumulatedMs = it.accumulatedMs,
                        tierIndex = it.tierIndex,
                        gatesCleared = it.gatesCleared,
                        tierUnlockedUntilMs = it.tierUnlockedUntilMs,
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

    fun setAlternativeChallenge(enabled: Boolean) {
        viewModelScope.launch { repo.setAlternativeChallenge(enabled) }
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

    fun setSensitivePrefixes(prefixes: List<String>) {
        viewModelScope.launch { repo.setSensitivePrefixes(prefixes) }
    }

    /**
     * Start or end the 15 minute pause. Suppresses every overlay; does not
     * touch accumulated time or tier, so this is an escape hatch and not a
     * friction holiday.
     */
    fun setPaused(active: Boolean) {
        viewModelScope.launch { repo.setPaused(active) }
    }

    /** Debug builds only. Zero restores the curve. */
    fun setPinnedStallMs(ms: Long) {
        viewModelScope.launch { store.setPinnedStallMs(ms) }
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
