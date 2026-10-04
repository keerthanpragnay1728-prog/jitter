package dev.molasses.ui.setup

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.molasses.core.session.TargetScope
import dev.molasses.core.setup.Onboarding
import dev.molasses.core.setup.Onboarding.Session
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.monitor.ServiceDiagnostics
import dev.molasses.core.diag.ServiceHealthPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How often the flow re-reads a grant it is waiting on. */
private const val SETUP_POLL_MS = 1_000L

/**
 * The first-run flow's [Session], one per process.
 *
 * An object rather than per activity state because the flow can be shown by
 * the console or by CFG, whichever opens first, and LATER on one has to hold
 * on the other. A cold start is a new process and so a fresh session, which
 * is what brings the flow back after LATER.
 */
object SetupSession {
    private val _state = MutableStateFlow(Session())
    val state: StateFlow<Session> = _state

    fun update(transform: (Session) -> Session) = _state.update(transform)
}

/** The detectable facts, before the session's seen flags are added. */
data class SetupGrants(
    val serviceWorking: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val usageAccess: Boolean = false,
    val defaultHome: Boolean = false,
)

/**
 * The Android half of the flow for one activity: reads the grants, opens the
 * screens that change them, and remembers that it did.
 *
 * Constructed as an activity field, because the role request is an activity
 * result and has to be registered before the activity starts. The repository
 * is passed as a function because Hilt injects it after field initialisers
 * run.
 */
class SetupFlowController(
    private val activity: ComponentActivity,
    private val repository: () -> SettingsRepository,
) {
    var grants by mutableStateOf(SetupGrants())
        private set

    /** A Settings screen was opened from the flow and the activity has not resumed since. */
    private var awayInSettings = false

    private val roleManager: RoleManager? by lazy { activity.getSystemService(RoleManager::class.java) }

    val roleAvailable: Boolean
        get() = roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true

    // An activity result, not startActivity: the role request reads the
    // calling package, which a plain start does not carry, and refuses
    // without it.
    private val roleRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        SetupSession.update { it.copy(homeRequestReturned = true) }
        refresh()
    }

    /** Call from the activity's onResume. */
    fun onResume() {
        if (awayInSettings) {
            awayInSettings = false
            SetupSession.update { it.copy(returnedFromSettings = true) }
        }
        refresh()
    }

    /** Working is `ServiceHealthPolicy.working`, the rule CFG's service row uses. */
    fun refresh() {
        val permissions = repository().permissionState()
        grants = SetupGrants(
            serviceWorking = ServiceHealthPolicy.working(ServiceDiagnostics.health(), permissions.accessibility),
            accessibilityEnabled = permissions.accessibility,
            usageAccess = permissions.usageAccess,
            // The shared reader. CFG's SETUP row and the console's line read
            // this same field, so all three agree. See HomeRole.
            defaultHome = activity.isDefaultHome(),
        )
    }

    fun openAccessibility() = openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppInfo() = openSettings(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
    )

    fun openUsageAccess() = openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openHome() {
        when (Onboarding.homeRoute(roleAvailable)) {
            Onboarding.HomeRoute.ROLE_REQUEST -> {
                val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_HOME)
                val launched = intent != null && runCatching { roleRequest.launch(intent) }.isSuccess
                if (!launched) openHomeSettings()
            }
            Onboarding.HomeRoute.HOME_SETTINGS -> openHomeSettings()
        }
    }

    fun openHomeSettings() = openSettings(Intent(Settings.ACTION_HOME_SETTINGS))

    private fun openSettings(intent: Intent) {
        if (runCatching { activity.startActivity(intent) }.isSuccess) awayInSettings = true
    }

    /** Labels of the tracked apps that are installed, for the targets step. */
    fun trackedLabels(tracked: Set<String>): List<String> {
        val pm = activity.packageManager
        return tracked.mapNotNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.sortedBy { it.lowercase() }
    }
}

/**
 * Shows the first-run flow in place of [content] while setup is incomplete,
 * and [content] otherwise. Both activities wrap their screen in this, so the
 * flow appears on whichever the user opens first, and closing it (DONE or
 * LATER) reveals the screen they came to.
 *
 * Instead of the content rather than over it: the console delivers Bit's
 * lines and spends their budget while composed, and would do it unseen.
 *
 * Nothing is drawn while the stored flag is loading, so neither screen
 * flashes on a guess.
 *
 * @param suspended true while CFG has stepped aside for the targets route.
 */
@Composable
fun SetupFlowGate(
    controller: SetupFlowController,
    repository: SettingsRepository,
    onEditTargets: () -> Unit,
    suspended: Boolean = false,
    content: @Composable () -> Unit,
) {
    val completed by repository.onboardingComplete
        .collectAsState<Boolean, Boolean?>(initial = null)
    val session by SetupSession.state.collectAsState()
    val selection by repository.targetSelection
        .collectAsState(initial = TargetScope.Selection(emptyList(), chosen = false))
    val scope = rememberCoroutineScope()

    val grants = controller.grants
    val facts = Onboarding.Facts(
        serviceWorking = grants.serviceWorking,
        accessibilityEnabled = grants.accessibilityEnabled,
        usageAccess = grants.usageAccess,
        defaultHome = grants.defaultHome,
        targetsSeen = session.targetsSeen,
        limitsSeen = session.limitsSeen,
    )
    val show = completed?.let { Onboarding.shouldShow(it, session) }
    val showing = show == true && !suspended

    val poll = showing && Onboarding.needsPoll(Onboarding.current(facts))
    LaunchedEffect(poll) {
        while (poll) {
            delay(SETUP_POLL_MS)
            controller.refresh()
        }
    }

    when {
        show == null -> Unit
        showing -> {
            // Back is LATER. Composed after the activity's own handler, so
            // it takes precedence while the flow is up.
            BackHandler { SetupSession.update(Onboarding::later) }
            // Through resolve, like every reader of the tracked set.
            val tracked = remember(selection) { TargetScope.resolve(selection, DEFAULT_TARGETS) }
            val labels = remember(tracked) { controller.trackedLabels(tracked) }
            OnboardingScreen(
                facts = facts,
                showUnlock = Onboarding.showUnlock(facts, session.returnedFromSettings, Build.VERSION.SDK_INT),
                showHomeSettings = Onboarding.showHomeSettingsFallback(facts, session, controller.roleAvailable),
                trackedLabels = labels,
                onOpenAccessibility = controller::openAccessibility,
                onOpenAppInfo = controller::openAppInfo,
                onOpenUsageAccess = controller::openUsageAccess,
                onOpenHome = controller::openHome,
                onOpenHomeSettings = controller::openHomeSettings,
                onEditTargets = onEditTargets,
                onTargetsSeen = { SetupSession.update { it.copy(targetsSeen = true) } },
                onDone = {
                    SetupSession.update(Onboarding::done)
                    scope.launch { repository.setOnboardingComplete() }
                },
                onLater = { SetupSession.update(Onboarding::later) },
            )
        }
        else -> content()
    }
}
