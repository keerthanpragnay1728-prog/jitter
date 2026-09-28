package dev.molasses.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.core.settings.CfgAccordion
import dev.molasses.core.ui.FontScale
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.ui.launcher.EXTRA_ONBOARDING
import dev.molasses.ui.launcher.LauncherActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (savedInstanceState == null) openSection = sectionFrom(intent)

        setContent {
            // Read here, not defaulted. This screen carries the font size
            // selector, so leaving it at 1.0 meant the one place a user
            // changes the setting was the one place guaranteed never to show
            // it. They pressed VERY_LARGE, nothing moved, and the only
            // reasonable conclusion was that the setting does not work.
            val fontScale by settingsRepository.fontScale
                .collectAsState(initial = FontScale.DEFAULT)

            MolassesTheme(fontScale = fontScale.multiplier) {
                var showDebug by rememberSaveable { mutableStateOf(false) }
                // The Surface the launcher has and this screen did not. Without
                // it the platform window background shows through everywhere
                // Compose does not paint.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = JitterBackground,
                ) {
                if (showDebug) {
                    DebugScreen(onBack = { showDebug = false })
                } else {
                    SettingsScreen(
                        onOpenAccessibility = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                        onOpenUsageAccess = { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                        onRequestActivityRecognition = { requestActivityRecognition() },
                        onRequestNotifications = { requestNotifications() },
                        onOpenDebug = { showDebug = true },
                        onOpenOnboarding = { openOnboarding() },
                        openSection = openSection,
                        onSectionOpened = { openSection = null },
                    )
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sectionFrom(intent)?.let { openSection = it }
    }

    private fun sectionFrom(intent: Intent): CfgAccordion.Section? =
        intent.getStringExtra(EXTRA_OPEN_SECTION)?.let { name ->
            CfgAccordion.Section.entries.firstOrNull { it.name == name }
        }

    /** The first-run flow lives on the launcher. Hand over and get out of its way. */
    private fun openOnboarding() {
        startActivity(Intent(this, LauncherActivity::class.java).putExtra(EXTRA_ONBOARDING, true))
        finish()
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }
    }

    private fun requestActivityRecognition() {
        requestPermissions.launch(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    companion object {
        /** A [CfgAccordion.Section] name to open on arrival. */
        const val EXTRA_OPEN_SECTION = "dev.molasses.extra.OPEN_SECTION"
    }
}
