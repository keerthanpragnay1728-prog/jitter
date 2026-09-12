package dev.molasses.ui.settings

import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.ui.theme.MolassesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Onboarding, target picking, policy selection, and the debug view.
 *
 * Also referenced by name from `accessibility_service_config.xml`
 * (`android:settingsActivity`), so R8 must keep it -- see `proguard-rules.pro`.
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
                        onOpenOverlay = { openOverlaySettings() },
                        onTestStall = { testStall() },
                        onOpenDebug = { showDebug = true },
                    )
                }
            }
        }
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun requestActivityRecognition() {
        requestPermissions.launch(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    /**
     * "Test Phantom Stall": arm a 1 s touch sink over this screen so the user
     * can feel the effect before enabling it on anything they care about.
     *
     * This is the one place the app uses `TYPE_APPLICATION_OVERLAY` and hence
     * `SYSTEM_ALERT_WINDOW`: there is no accessibility service token to borrow
     * from inside an Activity, and the real path
     * ([dev.molasses.overlay.ShutterOverlayManager]) uses
     * `TYPE_ACCESSIBILITY_OVERLAY` instead. Because this window is untrusted,
     * Android 12+ may pass touches through it -- so the preview shows the
     * visible tell and the blackout timing faithfully, but is not a guarantee
     * that touches were actually eaten. The real thing is.
     */
    private fun testStall() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val view = View(this).apply {
            setBackgroundColor(0x59_8C8C96.toInt())
            setOnTouchListener { _, _ -> true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { wm.addView(view, params) }.onSuccess {
            lifecycleScope.launch {
                delay(1_000)
                runCatching { wm.removeViewImmediate(view) }
            }
        }
    }
}
