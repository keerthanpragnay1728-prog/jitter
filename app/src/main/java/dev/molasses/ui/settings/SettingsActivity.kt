package dev.molasses.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.core.settings.CfgAccordion
import dev.molasses.core.ui.FontScale
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.core.setup.Onboarding
import dev.molasses.ui.setup.SetupFlowController
import dev.molasses.ui.setup.SetupFlowGate
import dev.molasses.ui.setup.SetupSession
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.MolassesTheme
import javax.inject.Inject

/**
 * Onboarding, target picking, policy selection, and the debug view.
 *
 * Also referenced by name from `accessibility_service_config.xml`
 * (`android:settingsActivity`), so R8 must keep it. See `proguard-rules.pro`.
 *
 * This Activity opens no windows of its own. The stall preview is a request to
 * the accessibility service, because a trusted `TYPE_ACCESSIBILITY_OVERLAY`
 * needs the service's window token and `SYSTEM_ALERT_WINDOW` is no longer
 * requested.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    /**
     * Injected for one thing: the font scale, which has to be read above
     * MolassesTheme. Everything else on this screen goes through
     * SettingsViewModel, and a theme is not a thing a view model can set.
     */
    @Inject lateinit var settingsRepository: SettingsRepository

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* the checklist re-reads real state on resume; nothing to do here */ }

    /**
     * A section a caller asked to see open, from [EXTRA_OPEN_SECTION]. Held
     * here because this activity is singleTask, so a second request arrives
     * through onNewIntent. Cleared once the screen has applied it.
     */
    private var openSection by mutableStateOf<CfgAccordion.Section?>(null)

    /** The first-run flow's reads and routes. A field, because it registers an activity result. */
    private val setup = SetupFlowController(this) { settingsRepository }

    /**
     * The flow has stepped aside so the user can edit targets here. Back ends
     * it: back to the flow when the flow was on this screen, back to the
     * console when the console sent the user here ([detourFinishes]).
     * Leaving the screen ends it too, so the flow is there on return.
     */
    private var targetsDetour by mutableStateOf(false)
    private var detourFinishes = false

    /**
     * [DBG] is showing in place of CFG.
     *
     * A field, and not `rememberSaveable`, so nothing restores it: not a
     * saved instance state and not a process death. It was saveable once,
     * and together with a missing back handler that put DBG in front of every
     * later visit. Back left the screen instead of leaving DBG, and since
     * Android 12 Back on a launcher task's root moves the task back rather
     * than finishing it, so the same instance, still in DBG, is what the next
     * visit found.
     *
     * Not cleared in onStop, because DBG's ledger export opens the system
     * file picker, which stops this activity, and the export's result
     * callback lives in DBG's composition: clearing it there would drop the
     * file. A new visit arrives through onNewIntent, which clears it.
     */
    private var showDebug by mutableStateOf(false)

    /**
     * Bumped on every new visit, and CFG is keyed on it, so each visit starts
     * at the root: sections as `CfgAccordion.initial` has them, nothing
     * expanded, no search text. The same Android 12 behaviour that kept DBG
     * kept these too, which the accordion's own doc says must not happen.
     */
    private var cfgVisit by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (savedInstanceState == null) {
            openSection = sectionFrom(intent)
            detourFrom(intent)
        }

        setContent {
            // Read here, not defaulted. This screen carries the font size
            // selector, so leaving it at 1.0 meant the one place a user
            // changes the setting was the one place guaranteed never to show
            // it. They pressed VERY_LARGE, nothing moved, and the only
            // reasonable conclusion was that the setting does not work.
            val fontScale by settingsRepository.fontScale
                .collectAsState(initial = FontScale.DEFAULT)

            MolassesTheme(fontScale = fontScale.multiplier) {
                // The Surface the launcher has and this screen did not. Without
                // it the platform window background shows through everywhere
                // Compose does not paint.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = JitterBackground,
                ) {
                // Until setup is complete this shows the first-run flow
                // instead of CFG, which is what a fresh sideload opens from
                // the app drawer. Closing it reveals CFG.
                SetupFlowGate(
                    controller = setup,
                    repository = settingsRepository,
                    onEditTargets = {
                        detourFinishes = false
                        targetsDetour = true
                        openSection = CfgAccordion.Section.TARGETS
                    },
                    suspended = targetsDetour,
                ) {
                    BackHandler(enabled = targetsDetour) {
                        if (detourFinishes) finish() else targetsDetour = false
                    }
                    if (showDebug) {
                        // Back returns to CFG's root, the same as DBG's own
                        // back button. Composed after the detour's handler,
                        // so it wins while DBG is up.
                        BackHandler { showDebug = false }
                        DebugScreen(onBack = { showDebug = false })
                    } else {
                        key(cfgVisit) {
                            SettingsScreen(
                                onOpenAccessibility = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                                onOpenUsageAccess = { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                                onRequestActivityRecognition = { requestActivityRecognition() },
                                onOpenDebug = { showDebug = true },
                                onOpenOnboarding = { SetupSession.update(Onboarding::request) },
                                openSection = openSection,
                                onSectionOpened = { openSection = null },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new visit starts at CFG's root. See [showDebug] and [cfgVisit].
        showDebug = false
        cfgVisit++
        sectionFrom(intent)?.let { openSection = it }
        detourFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        setup.onResume()
    }

    override fun onStop() {
        super.onStop()
        targetsDetour = false
    }

    /** The console's route to TARGETS: step the flow aside, and Back returns there. */
    private fun detourFrom(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SETUP_DETOUR, false)) {
            detourFinishes = true
            targetsDetour = true
        }
    }

    private fun sectionFrom(intent: Intent): CfgAccordion.Section? =
        intent.getStringExtra(EXTRA_OPEN_SECTION)?.let { name ->
            CfgAccordion.Section.entries.firstOrNull { it.name == name }
        }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }
    }

    private fun requestActivityRecognition() {
        requestPermissions.launch(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION))
    }

    companion object {
        /** A [CfgAccordion.Section] name to open on arrival. */
        const val EXTRA_OPEN_SECTION = "dev.molasses.extra.OPEN_SECTION"

        /** Sent with [EXTRA_OPEN_SECTION] by the first-run flow on the console. */
        const val EXTRA_SETUP_DETOUR = "dev.molasses.extra.SETUP_DETOUR"
    }
}
