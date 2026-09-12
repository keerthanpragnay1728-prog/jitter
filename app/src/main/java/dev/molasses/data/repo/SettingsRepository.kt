package dev.molasses.data.repo

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.molasses.core.model.CycleResetPolicy
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.toModel
import dev.molasses.monitor.MolassesAccessibilityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One installed, launchable app, for the target picker. */
data class InstalledApp(
    val pkg: String,
    val label: String,
)

/** Live state of one onboarding requirement. */
data class PermissionState(
    val accessibility: Boolean,
    val usageAccess: Boolean,
    val activityRecognition: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
) {
    /** The gate and the stall both work without notifications or overlay. */
    val essentialsGranted: Boolean get() = accessibility && usageAccess
    val allGranted: Boolean
        get() = accessibility && usageAccess && activityRecognition && notifications
}

class SettingsRepository(
    context: Context,
    private val store: CycleStateStore,
) {
    private val appContext = context.applicationContext

    val targets: Flow<List<String>> = store.data.map { it.targetPackagesList.toList() }
    val resetPolicy: Flow<CycleResetPolicy> = store.data.map { it.resetPolicy.toModel() }
    val alternativeChallenge: Flow<Boolean> = store.data.map { it.alternativeChallenge }

    suspend fun setTargets(packages: List<String>) = store.setTargets(packages)
    suspend fun setResetPolicy(policy: CycleResetPolicy) = store.setResetPolicy(policy)
    suspend fun setAlternativeChallenge(enabled: Boolean) =
        store.setAlternativeChallenge(enabled)

    fun permissionState(): PermissionState = PermissionState(
        accessibility = isAccessibilityServiceEnabled(),
        usageAccess = hasUsageAccess(),
        activityRecognition = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED,
        notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        },
        overlay = Settings.canDrawOverlays(appContext),
    )

    /**
     * Read from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` rather than
     * from any state the service itself sets, so the checklist stays honest if
     * the user disables the service while the settings screen is open.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${appContext.packageName}/${MolassesAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Launchable, non-system apps, for the picker.
     *
     * Needs QUERY_ALL_PACKAGES on API 30+; without it this returns only this
     * app. The permission is declared with a `tools:ignore` because a target
     * picker is exactly the "user selects an app" case the policy allows, but
     * it is worth knowing it is a Play review question.
     */
    fun installedApps(): List<InstalledApp> {
        val pm = appContext.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != appContext.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || isKnownTarget(it.packageName) }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun isKnownTarget(pkg: String) = pkg in setOf(
        "com.instagram.android",
        "com.twitter.android",
        "com.google.android.youtube",
    )
}
