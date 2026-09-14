package dev.molasses.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.ui.theme.MolassesTheme

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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* the checklist re-reads real state on resume; nothing to do here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MolassesTheme {
                var showDebug by rememberSaveable { mutableStateOf(false) }
                if (showDebug) {
                    DebugScreen(onBack = { showDebug = false })
                } else {
                    SettingsScreen(
                        onOpenAccessibility = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                        onOpenUsageAccess = { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                        onRequestActivityRecognition = { requestActivityRecognition() },
                        onRequestNotifications = { requestNotifications() },
                        onOpenDebug = { showDebug = true },
                    )
                }
            }
        }
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
}
