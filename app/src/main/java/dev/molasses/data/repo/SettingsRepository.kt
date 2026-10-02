package dev.molasses.data.repo

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.molasses.CycleState
import dev.molasses.core.model.AppSnapshot
import dev.molasses.core.diag.ServiceComponent
import dev.molasses.core.bit.BitStatus
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.console.ConsoleSpeech
import dev.molasses.core.launch.QuickLaunch
import dev.molasses.core.remind.Reminder
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.lock.LockReason
import dev.molasses.core.session.TargetScope
import dev.molasses.core.settings.UntrackSunset
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.friction.FrictionCurve
import dev.molasses.core.lease.GatePolicy
import dev.molasses.core.safety.PauseWindow
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.ui.FontScale
import dev.molasses.data.datastore.ConsoleState
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.datastore.pauseInstant
import dev.molasses.data.datastore.toEngineSnapshot
import dev.molasses.monitor.MolassesAccessibilityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One installed, launchable app, for the target picker. */
data class InstalledApp(
    val pkg: String,
    val label: String,
)

/**
 * The cycle, as much of it as Bit's HUD reads.
 *
 * [cumulativeMs] is summed across targets rather than reported per app,
 * because the question the HUD answers is "how deep am I", not "how deep am I
 * in Instagram specifically". The ledger already answers the second.
 */
data class CycleReadout(
    val anchor: StampedInstant,
    /**
     * Summed across targets. The HUD's second step asks how much of the cycle
     * has gone, and for that a total is the right answer.
     */
    val cumulativeMs: Long,
    /**
     * The deepest single app, or null when nothing has accumulated.
     *
     * Separate from [cumulativeMs] because the friction curve is per package:
     * two apps at ten minutes each are not twenty minutes deep in anything,
     * and feeding the sum to `moodFor` put Bit in a mood no app's friction
     * justified. The whole snapshot rather than just its time, because the
     * ledger line reports this app's tier and penalty alongside it and the
     * three have to describe the same app.
     */
    val deepest: AppSnapshot? = null,
    /** A checkpoint is overdue right now. Drives the docked slit's alert. */
    val penaltyAccruing: Boolean = false,
) {
    /** Zero when nothing has accumulated, which `moodFor` reads as idle. */
    val deepestAppMs: Long get() = deepest?.accumulatedMs ?: 0L

    /**
     * The deepest app's horizon, which is the one the mood must be read
     * against.
     *
     * Off the same snapshot as [deepestAppMs] deliberately: a time from one
     * app and a horizon from another would put Bit in a mood no app's friction
     * justifies, which is the defect that shipped once when the summed cycle
     * total was fed to a per app curve.
     *
     * The default when nothing has accumulated, because there is no app to ask
     * and idle is idle at every horizon.
     */
    val deepestHorizonMs: Long get() = deepest?.horizonMs ?: FrictionCurve.DEFAULT_HORIZON_MS

    /**
     * Milliseconds until the cycle resets, or null when none is anchored.
     *
     * Null rather than the full window, because an unanchored cycle has not
     * started and a ledger that reported six hours left would be describing a
     * cycle that does not exist yet.
     */
    fun remainingMs(now: StampedInstant): Long? =
        if (!anchor.isSet) null else CycleWindow.remainingMs(anchor, now)
}

/** Live state of one onboarding requirement. */
data class PermissionState(
    val accessibility: Boolean,
    val usageAccess: Boolean,
    val activityRecognition: Boolean,
) {
    /** The gate and the stall both work without the step sensor. */
    val essentialsGranted: Boolean get() = accessibility && usageAccess
}

class SettingsRepository(
    context: Context,
    private val store: CycleStateStore,
) {
    private val appContext = context.applicationContext

    /**
     * The stored target list together with whether it is an answer.
     *
     * One flow rather than a list and a flag, because every bug this app has
     * had in this area came from reading one without the other. A pair that
     * cannot be taken apart cannot be half read, and the raw list is
     * deliberately not exposed beside it. See CLAUDE.md, "The stored target
     * list is not the tracked set".
     */
    val targetSelection: Flow<TargetScope.Selection> = store.data.map {
        TargetScope.Selection(
            stored = it.targetPackagesList.toList(),
            chosen = it.targetsChosen,
        )
    }
    /**
     * What the gate asks for at the terminal tier. Below it, always the
     * countdown. See [dev.molasses.core.lease.GatePolicy].
     */
    val gateMode: Flow<GatePolicy.GateMode> = store.gateMode

    /**
     * The persisted locks.
     *
     * There is no unlock on this repository, and that is not an omission. A
     * lock that can be cleared is a lock that will be cleared, at the exact
     * moment it is doing its job.
     */
    val locks: Flow<LockRegistry> = store.locks

    suspend fun armLock(
        pkg: String,
        durationMs: Long,
        reason: LockReason,
    ) = store.armLock(pkg, nowStamped(), durationMs, reason)

    suspend fun armLocks(
        packages: Collection<String>,
        durationMs: Long,
        reason: LockReason,
    ) = store.armLocks(packages, nowStamped(), durationMs, reason)

    /**
     * What Bit's readout needs, in one emission.
     *
     * The anchor rather than a remaining figure, so the caller subtracts
     * against a fresh stamp at the moment it renders. A precomputed remainder
     * would either be up to fifteen seconds stale, because that is the
     * checkpoint cadence, or need a per-second ticker for a readout that is
     * on screen for five seconds at a time.
     */
    val cycleReadout: Flow<CycleReadout> = store.data.map { state ->
        val snapshot = state.toEngineSnapshot()
        CycleReadout(
            anchor = StampedInstant(
                wallMs = state.cycleAnchorWallMs,
                elapsedMs = state.cycleAnchorElapsedMs,
                bootId = state.cycleAnchorBootId,
            ),
            cumulativeMs = snapshot.perApp.values.sumOf { it.accumulatedMs },
            deepest = BitStatus.deepest(snapshot.perApp.values),
            penaltyAccruing = BitStatus.penaltyAccruing(snapshot.perApp.values),
        )
    }

    /** Bit's queue, its live prompt and what it has already spent. */
    val console: Flow<ConsoleState> = store.console

    suspend fun deliverConsoleLine(line: ConsoleLine, budget: ConsoleSpeech.Budget) =
        store.deliverConsoleLine(line, budget)

    suspend fun clearConsolePrompt() = store.clearConsolePrompt()

    suspend fun enqueueConsoleLine(line: ConsoleLine) = store.enqueueConsoleLine(line)

    /** The last twenty submitted command lines, newest first. */
    val commandHistory: Flow<List<String>> =
        store.data.map { it.commandHistoryList.toList() }

    suspend fun recordCommand(line: String) =
        store.recordCommand(line)

    /** Only the user's additions. The shipped defaults are not editable. */
    val sensitivePrefixes: Flow<List<String>> =
        store.data.map { it.sensitivePackagePrefixesList.toList() }

    /**
     * Milliseconds left in the pause, recomputed on every store emission and
     * not on a timer. The settings screen renders its own countdown from this.
     */
    val pauseRemainingMs: Flow<Long> = store.data.map {
        PauseWindow.remainingMs(it.pauseInstant(), nowStamped())
    }

    /** Multiplies the sp sizes; it does not replace the system font scale. */
    val fontScale: Flow<FontScale> =
        store.data.map { FontScale.fromOrdinal(it.fontScaleOrdinal) }

    suspend fun setFontScale(scale: FontScale) = store.setFontScale(scale)

    /**
     * Track or untrack [pkg], unless a lock stands on it.
     *
     * The only way to change the target list. There is deliberately no
     * `setTargets`: see [CycleStateStore.toggleTarget].
     */
    suspend fun toggleTarget(pkg: String) = store.toggleTarget(pkg, nowStamped())

    /**
     * Remove [pkg] from the tracked set, at the end of the untrack
     * cooling-off. The same single writer and the same lock guard as
     * [toggleTarget], restricted to removing. A social app gets a re-arm,
     * `UntrackSunset.GRACE_DAYS` out, in the same write: see [sunsetInScope].
     */
    suspend fun untrackTarget(pkg: String) =
        store.toggleTarget(pkg, nowStamped(), onlyIfTracked = true, grantsSunset = sunsetInScope(pkg))

    /**
     * Whether untracking [pkg] is temporary. The one reading of the category,
     * used both by the cooling-off panel that states it and by the write that
     * acts on it, so the two cannot disagree. A package this app cannot see
     * reads as no category and falls to the named list.
     */
    fun sunsetInScope(pkg: String): Boolean {
        val social = runCatching {
            appContext.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_SOCIAL
        }.getOrDefault(false)
        return UntrackSunset.inScope(pkg, categorySocial = social)
    }

    /** Pending re-arms. See [UntrackSunset]. */
    val untrackSunsets: Flow<List<UntrackSunset.Sunset>> = store.untrackSunsets
    suspend fun setGateMode(mode: GatePolicy.GateMode) = store.setGateMode(mode)

    /** The user's declared horizon per package. See [CycleStateStore.appHorizons]. */
    val appHorizons: Flow<Map<String, Long>> = store.appHorizons

    /** Every reminder not yet dismissed. See [dev.molasses.core.remind.ReminderBook]. */
    val reminders: Flow<List<Reminder>> = store.reminders

    /** The store's verdict on the cap, decided in its transaction. See [CycleStateStore.addReminder]. */
    suspend fun addReminder(text: String, due: StampedInstant): ReminderBook.Added = store.addReminder(text, due)

    /** The console's dismiss. The only thing that removes a reminder. */
    suspend fun dismissReminder(id: Long) = store.dismissReminder(id)

    /** True when a pending reminder [id] was removed. See [CycleStateStore.killReminder]. */
    suspend fun killReminder(id: Long): Boolean = store.killReminder(id)

    /** Whether the first-run flow has been completed once. */
    val onboardingComplete: Flow<Boolean> = store.onboardingComplete

    suspend fun setOnboardingComplete() = store.setOnboardingComplete()

    /** The console's quick-launch rows, as stored. See [QuickLaunch]. */
    val quickLaunch: Flow<QuickLaunch.Selection> = store.quickLaunch

    suspend fun addQuickLaunch(entry: QuickLaunch.Entry) =
        store.editQuickLaunch(::isLaunchable) { QuickLaunch.added(it, entry) }

    suspend fun swapQuickLaunch(old: QuickLaunch.Entry, new: QuickLaunch.Entry) =
        store.editQuickLaunch(::isLaunchable) { QuickLaunch.swapped(it, old, new) }

    suspend fun removeQuickLaunch(entry: QuickLaunch.Entry) =
        store.editQuickLaunch(::isLaunchable) { QuickLaunch.removed(it, entry) }

    /** Whether [pkg] is installed and has a launcher entry. */
    fun isLaunchable(pkg: String): Boolean =
        runCatching { appContext.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    /**
     * Every app with a launcher entry, system apps included, for the
     * quick-launch picker. Unlike [installedApps], which the target list
     * reads, this does not drop system apps: a camera or a browser is
     * exactly what someone wants one tap away.
     */
    fun launchableApps(): List<InstalledApp> {
        val pm = appContext.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(main, 0) }.getOrDefault(emptyList())
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun setAppHorizon(pkg: String, horizonMs: Long) =
        store.setAppHorizon(pkg, horizonMs)

    /** Refuses a new prefix that would void a standing lock. See [CycleStateStore.setSensitivePrefixes]. */
    suspend fun setSensitivePrefixes(prefixes: List<String>) =
        store.setSensitivePrefixes(prefixes, nowStamped())

    suspend fun setPaused(active: Boolean) = store.setPaused(active, readBootCount())

    /** Live remainder, for a UI that wants to tick without a store write. */
    fun pauseRemainingNowMs(state: CycleState): Long =
        PauseWindow.remainingMs(state.pauseInstant(), nowStamped())

    /**
     * Public because the command bar has to evaluate a lock against the same
     * stamp the store will write with. Two clocks would mean the prompt could
     * report a refusal the store then allowed, or the reverse.
     */
    fun nowStamped() = StampedInstant(
        wallMs = System.currentTimeMillis(),
        elapsedMs = SystemClock.elapsedRealtime(),
        bootId = readBootCount(),
    )

    /**
     * `Settings.Global.BOOT_COUNT`. Read here rather than passed in because a
     * pause is armed from the settings process, which has no service handle.
     */
    private fun readBootCount(): Int =
        runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)

    fun permissionState(): PermissionState = PermissionState(
        accessibility = isAccessibilityServiceEnabled(),
        usageAccess = hasUsageAccess(),
        activityRecognition = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED,
    )

    /**
     * Read from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` rather than
     * from any state the service itself sets, so the checklist stays honest if
     * the user disables the service while the settings screen is open.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // Either flattened form, as ComponentName.unflattenFromString reads
        // them. See ServiceComponent.
        return ServiceComponent.isEnabled(enabled, appContext.packageName, MolassesAccessibilityService::class.java.name)
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
