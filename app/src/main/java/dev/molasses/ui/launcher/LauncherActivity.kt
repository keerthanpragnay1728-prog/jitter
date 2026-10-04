package dev.molasses.ui.launcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.R
import dev.molasses.core.bit.BitDisplay
import dev.molasses.core.bit.BitDock
import dev.molasses.core.bit.BitGlyph
import dev.molasses.core.bit.BitHud
import dev.molasses.core.bit.BitStateMachine
import dev.molasses.core.bit.BitStatus
import dev.molasses.core.bit.BitTap
import dev.molasses.core.command.AnswerTimer
import dev.molasses.core.command.CommandRegistry
import dev.molasses.core.command.DeferredWait
import dev.molasses.core.command.ReadingWindow
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.diag.ServiceOffLine
import dev.molasses.core.launch.QuickLaunch
import dev.molasses.core.remind.Reminder
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.launch.ShortcutLadder
import dev.molasses.core.console.ConsoleSpeech
import dev.molasses.core.console.Greeting
import dev.molasses.core.bit.HudStep
import dev.molasses.core.command.AppTokenResolver
import dev.molasses.core.command.Command
import dev.molasses.core.command.CommandParser
import dev.molasses.core.command.Manual
import dev.molasses.core.command.LockConfirmation
import dev.molasses.core.command.DispatchResult
import dev.molasses.core.command.ParseError
import dev.molasses.core.command.ParseResult
import dev.molasses.core.lock.BedtimeWindow
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.ui.BezelSnap
import dev.molasses.core.stats.DayUsage
import dev.molasses.core.ui.CycleLine
import dev.molasses.core.ui.FontScale
import dev.molasses.core.ui.PowerBar
import dev.molasses.debug.BitTrace
import dev.molasses.data.datastore.ConsoleState
import dev.molasses.core.session.TargetScope
import dev.molasses.core.settings.CfgAccordion
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.repo.CycleReadout
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.monitor.ReminderAlarms
import dev.molasses.monitor.ServiceDiagnostics
import dev.molasses.monitor.foregroundEvents
import dev.molasses.ui.canResolve
import dev.molasses.ui.lock.lockOpensAtText
import dev.molasses.ui.settings.SettingsActivity
import dev.molasses.ui.setup.SetupFlowController
import dev.molasses.ui.setup.SetupFlowGate
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.MolassesTheme
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorDivider
import dev.molasses.ui.theme.PhosphorGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val isTarget: Boolean,
)

data class AppUsageRecord(
    val label: String,
    val minutes: Long,
    val bar: String,
)

@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /**
     * Read once, at first composition. The launcher is a singleTask activity
     * that outlives most of what it launches, and re-querying PackageManager
     * on every recomposition is an IPC per frame. A newly installed app
     * appears on the next cold start, which is the trade the terminal aesthetic
     * can afford.
     */
    private val installedApps: List<LaunchableApp> by lazy { queryLaunchableApps() }

    /**
     * The first-run flow's reads and routes for this activity. A field,
     * because it registers an activity result. See SetupFlowGate.
     */
    private val setup = SetupFlowController(this) { settingsRepository }

    /** Where a refused start is said. The console page fills it in; see ConsoleRefusal. */
    private val consoleRefusal = ConsoleRefusal()

    /**
     * Between onStart and onStop. The service-off line measures its grace
     * and re-reads the service only while this is true. See ServiceOffLine.
     */
    private var consoleVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge stays here: it is a window layout attribute and it is
        // correct from onCreate. Hiding the bar is not, and does not; see
        // onWindowFocusChanged.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val fontScale by settingsRepository.fontScale
                .collectAsState(initial = FontScale.DEFAULT)

            // The armed locks. Read by the prompt to predict what a lock
            // command will do, and by the target list to dim what is locked.
            // The store stays authoritative: the extend-only compare happens
            // inside its transform, not against this copy.
            val locks by settingsRepository.locks
                .collectAsState(initial = LockRegistry())

            val selection by settingsRepository.targetSelection
                .collectAsState(initial = TargetScope.Selection(emptyList(), chosen = false))

            // [TRACKED] is derived here, and not baked in by the query.
            //
            // It used to be `pkg in DEFAULT_TARGETS`, decided inside
            // queryLaunchableApps, which is wrong three times over and showed
            // up as one symptom: apps on the default list badged and an app
            // the user added themselves never did.
            //
            // The list is the first fault. The second is that
            // `installedApps` is `by lazy`, so even the right set would have
            // frozen at the first read and a target toggled in CFG would not
            // have reached the badge without restarting the launcher. The
            // third is that the stored list is not the tracked set: the
            // service runs it through TargetScope.resolve, which falls back
            // to the defaults when it is empty, so reading the raw flow would
            // have made the badge vanish entirely on a fresh install.
            //
            // So: the expensive PackageManager query stays lazy and stops
            // deciding this, and the one volatile field is recomputed from
            // the same resolve the service uses.
            val tracked = remember(selection) { TargetScope.resolve(selection, DEFAULT_TARGETS) }
            val badgedApps = remember(tracked) {
                installedApps.map { it.copy(isTarget = it.packageName in tracked) }
            }

            // What Bit's readout reads. The anchor rather than a remaining
            // figure, so the HUD subtracts against a fresh stamp when it
            // renders instead of needing a per-second ticker for something
            // that is on screen five seconds at a time.
            val cycle by settingsRepository.cycleReadout
                .collectAsState(initial = CycleReadout(StampedInstant.UNSET, 0L))

            // Bit's queue, its live prompt and what it has already spent.
            val console by settingsRepository.console
                .collectAsState(initial = ConsoleState())

            // The quick-launch rows. Untouched reads as the five defaults, so
            // the initial value is the same answer the store gives a fresh
            // install. See QuickLaunch.
            val quickLaunch by settingsRepository.quickLaunch
                .collectAsState(initial = QuickLaunch.Selection(emptyList(), chosen = false))

            // $ rem. The fired ones are shown on the console until dismissed;
            // the pending ones are listed by a bare rem.
            val reminders by settingsRepository.reminders
                .collectAsState(initial = emptyList())

            // The service-off line. Read on resume like the home line, then
            // once a second while the console is visible and the service is
            // not working, and only then. Shown after ServiceOffLine's grace,
            // so a boot, which resumes the console before the service has
            // connected, shows nothing at all.
            val working = setup.grants.serviceWorking
            val visible = consoleVisible
            var offSince by remember { mutableStateOf<Long?>(null) }
            var offReadAtMs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(visible, working) {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val workingNow = setup.grants.serviceWorking
                    offSince = ServiceOffLine.since(offSince, workingNow, visible, now)
                    offReadAtMs = now
                    if (!visible || workingNow) break
                    delay(SERVICE_OFF_POLL_MS)
                    setup.refresh()
                }
            }
            val serviceOff = ServiceOffLine.shown(offSince, working, offReadAtMs)

            MolassesTheme(fontScale = fontScale.multiplier) {
                var showDrawer by remember { mutableStateOf(false) }
                // A long console lock waiting on the full-screen panel. Held
                // here, above the pager and the drawer, so the panel covers
                // everything. See LockConfirmation.
                var lockConfirm by remember { mutableStateOf<LockConfirmRequest?>(null) }
                val pagerState = rememberPagerState(pageCount = { 2 })
                val scope = rememberCoroutineScope()

                // Per destination, and a no-op only on the console.
                //
                // A launcher that swallows back everywhere is a launcher you
                // cannot get out of. Back has to pop the drawer and the
                // ledger; it is inert only on the console, which is home and
                // has nowhere above it to go.
                BackHandler(enabled = true) {
                    when {
                        // Back aborts a lock confirmation and writes nothing.
                        // It is the panel's only way out besides the commit.
                        lockConfirm != null -> lockConfirm = null
                        showDrawer -> showDrawer = false
                        pagerState.currentPage != PAGE_CONSOLE ->
                            scope.launch { pagerState.animateScrollToPage(PAGE_CONSOLE) }
                        else -> Unit
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = JitterBackground,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SetupFlowGate(
                            controller = setup,
                            repository = settingsRepository,
                            onEditTargets = {
                                // CFG opens on TARGETS with the flow stepped
                                // aside, and Back there returns here.
                                startFromConsole(
                                    Intent(this@LauncherActivity, SettingsActivity::class.java)
                                        .putExtra(SettingsActivity.EXTRA_OPEN_SECTION, CfgAccordion.Section.TARGETS.name)
                                        .putExtra(SettingsActivity.EXTRA_SETUP_DETOUR, true),
                                    consoleRefusal,
                                )
                            },
                        ) {
                            MainLauncherWorkspace(
                                appList = badgedApps,
                                pagerState = pagerState,
                                onOpenDrawer = { showDrawer = true },
                                onOpenSettings = {
                                    startFromConsole(Intent(this@LauncherActivity, SettingsActivity::class.java), consoleRefusal)
                                },
                                onLaunchPackage = ::launchPackage,
                                cycle = cycle,
                                // A standing bedtime lock is the curfew. Read from
                                // BedtimeWindow rather than a second copy of the
                                // hour, so a real setting behind that constant is
                                // picked up here for free.
                                curfewEndMinuteOfDay = remember(locks) {
                                    val active = locks.active(settingsRepository.nowStamped())
                                    if (active.any { it.reason == LockReason.BEDTIME }) {
                                        BedtimeWindow.WAKE_MINUTE_OF_DAY
                                    } else {
                                        null
                                    }
                                },
                                nowStamped = settingsRepository::nowStamped,
                                console = console,
                                onDeliverConsoleLine = { line, budget ->
                                    scope.launch {
                                        settingsRepository.deliverConsoleLine(line, budget)
                                    }
                                },
                                onAnswerConsolePrompt = {
                                    scope.launch { settingsRepository.clearConsolePrompt() }
                                },
                                onEnqueueConsoleLine = { line ->
                                    scope.launch { settingsRepository.enqueueConsoleLine(line) }
                                },
                                onRecordCommand = { line ->
                                    scope.launch {
                                        settingsRepository.recordCommand(line)
                                    }
                                },
                                onRequestLockConfirm = { lockConfirm = it },
                                quickLaunch = quickLaunch,
                                reminders = reminders,
                                onDismissReminder = { id ->
                                    scope.launch { settingsRepository.dismissReminder(id) }
                                },
                                onKillReminder = { id ->
                                    scope.launch {
                                        // Store first, then the alarm: an alarm that
                                        // fires in between finds nothing unfired.
                                        val removed = settingsRepository.killReminder(id)
                                        val cancelled = removed && ReminderAlarms.cancel(this@LauncherActivity, id)
                                        Log.i(REMINDER_TAG, "kill: reminder $id removed=$removed alarmCancelled=$cancelled")
                                    }
                                },
                                // Remembered so the prompt can build its
                                // dispatcher once rather than on every keystroke.
                                actions = remember(pagerState) {
                                    LauncherActions(
                                        showLedger = {
                                            scope.launch { pagerState.animateScrollToPage(PAGE_LEDGER) }
                                        },
                                        // False when refused, so a command
                                        // gives its own answer in place of
                                        // the helper's line.
                                        startIntent = { startFromConsole(it, consoleRefusal) },
                                        canResolve = ::canResolve,
                                        lockRemainingMs = { pkg ->
                                            locks.remainingMs(pkg, settingsRepository.nowStamped())
                                        },
                                        anyLockArmed = {
                                            locks.active(settingsRepository.nowStamped()).isNotEmpty()
                                        },
                                        targets = { tracked.toList() },
                                        installedPackages = {
                                            TargetScope.installedOrUnknown(installedApps.map { it.packageName })
                                        },
                                        resolveApp = { token ->
                                            AppTokenResolver.resolve(
                                                token = token,
                                                candidates = installedApps.map {
                                                    AppTokenResolver.Candidate(it.packageName, it.label)
                                                },
                                                preferred = tracked,
                                            )
                                        },
                                        armLock = { packages, durationMs, reason ->
                                            scope.launch {
                                                settingsRepository.armLocks(packages, durationMs, reason)
                                            }
                                        },
                                        minuteOfDay = {
                                            val c = Calendar.getInstance()
                                            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                                        },
                                        remind = remind@{ whenSpec, text, done ->
                                            val now = settingsRepository.nowStamped()
                                            val c = Calendar.getInstance()
                                            val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                                            val due = ReminderBook.dueAt(whenSpec, now, minute, ReminderAlarms::wallOn)
                                            // A dated reminder in the past is refused
                                            // before anything is written.
                                            if (due == null) {
                                                done(RemindOutcome.Past)
                                                return@remind
                                            }
                                            scope.launch {
                                                // The cap is the store's call, made in its own
                                                // transaction, not a check on the list this
                                                // screen last saw. Only what it took is armed,
                                                // and the prompt reports the precision the
                                                // alarm was actually set with.
                                                when (val verdict = settingsRepository.addReminder(text, due)) {
                                                    ReminderBook.Added.Full -> done(RemindOutcome.Full)
                                                    is ReminderBook.Added.Ok -> {
                                                        val added = verdict.reminder
                                                        val armed = ReminderAlarms.schedule(
                                                            this@LauncherActivity, added.id, added.due.wallMs,
                                                        )
                                                        done(RemindOutcome.Saved(dueWallMs = added.due.wallMs, armed = armed))
                                                    }
                                                }
                                            }
                                        },
                                        // Reads the collected State when called, not
                                        // a value captured when this was remembered.
                                        pendingReminders = { reminders },
                                    )
                                },
                                onDialer = {
                                    startFromConsole(Intent(Intent.ACTION_DIAL), consoleRefusal)
                                },
                                messagingApps = ::messagingApps,
                                onLaunchLadder = ::launchLadder,
                                onOpenWellbeingSettings = ::openWellbeing,
                                refusal = consoleRefusal,
                                // Read on this activity's resume, by the same
                                // reader as CFG. See HomeRole.
                                homeLost = !setup.grants.defaultHome,
                                onOpenHomeSettings = setup::openHomeSettings,
                                // ServiceHealthPolicy.working, through the same
                                // grants CFG's service row and the first-run
                                // flow read, after ServiceOffLine's grace.
                                serviceOff = serviceOff,
                                onOpenAccessibility = setup::openAccessibility,
                            )

                            AnimatedVisibility(
                                visible = showDrawer,
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                            ) {
                                AppDrawerOverlay(
                                    apps = badgedApps,
                                    onLaunchPackage = { pkg ->
                                        showDrawer = false
                                        launchPackage(pkg)
                                    },
                                    onClose = { showDrawer = false },
                                )
                            }

                            // Last, so it draws over the pager and the drawer.
                            // A composable in this activity, not a service
                            // overlay: it asks about the user's own command.
                            lockConfirm?.let { request ->
                                LockConfirmPanel(
                                    panel = request.panel,
                                    onCommit = {
                                        lockConfirm = null
                                        request.commit()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        consoleVisible = true
    }

    override fun onStop() {
        super.onStop()
        consoleVisible = false
    }

    override fun onResume() {
        super.onResume()
        setup.onResume()
    }

    // ------------------------------------------------------------- actions

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startFromConsole(intent, consoleRefusal)
    }

    /**
     * Launch by action, optionally narrowed by category.
     *
     * Silently does nothing when no app handles it. A launcher that toasts
     * "no calendar installed" every time a favourite is tapped is worse than
     * one where the row simply does not respond, and the row is only ever
     * tapped deliberately.
     */
    /**
     * Walk a [ShortcutLadder] and start the first rung this device answers.
     *
     * @return false when no rung resolved, so the caller can say so. It used
     *   to be a bare `runCatching { startActivity(intent) }`, which meant a
     *   device whose clock app does not declare SHOW_ALARMS got a `[clock]`
     *   row that did nothing at all, silently, forever. A row that does
     *   nothing is worse than one that says it cannot, because the user
     *   presses it again.
     *
     * Each rung is resolved before it is launched rather than launched inside
     * a try, for the reason [openWellbeing] gives: a throw has already torn
     * down the touch that caused it, so the fallback has to be a decision
     * rather than a recovery.
     */
    private fun launchLadder(ladder: List<ShortcutLadder.Candidate>): Boolean {
        for (rung in ladder) {
            val intent = when {
                rung.pkg != null -> packageManager.getLaunchIntentForPackage(rung.pkg)
                else -> Intent(rung.action).apply {
                    rung.category?.let { addCategory(it) }
                }
            } ?: continue
            // A package rung's launch intent is already known to exist, so it
            // needs no second resolve; an action rung does.
            if (rung.pkg == null && !canResolve(intent)) continue
            if (startFromConsole(intent, consoleRefusal)) return true
        }
        Log.w(TAG_LAUNCHER, "no rung of the ladder resolved: $ladder")
        return false
    }

    /**
     * The messaging clients worth offering.
     *
     * Two sources, because "messaging app" is two questions. The device's SMS
     * clients declare `CATEGORY_APP_MESSAGING` and can be asked for. WhatsApp,
     * Signal and Telegram do not and never will, because they are not SMS
     * clients, so asking the system for messaging apps returns exactly the
     * ones most people do not use. The named list covers them.
     *
     * Deduplicated by package, because Google Messages is on both lists.
     */
    private fun messagingApps(): List<LaunchableApp> {
        val sms = Intent(Intent.ACTION_MAIN)
            .addCategory(ShortcutLadder.MESSAGING_CATEGORY)
        val declared = runCatching {
            packageManager.queryIntentActivities(sms, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())

        return (declared + ShortcutLadder.CHAT_PACKAGES)
            .distinct()
            .mapNotNull { pkg ->
                val info = runCatching {
                    packageManager.getApplicationInfo(pkg, 0)
                }.getOrNull() ?: return@mapNotNull null
                if (packageManager.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                LaunchableApp(
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = pkg,
                    isTarget = false,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Digital Wellbeing, by the most specific route this device answers.
     *
     * Each is resolved before it is launched rather than launched inside a
     * try. A `startActivity` that throws has already torn down the touch that
     * caused it, and the catch lands the user somewhere they did not ask for
     * with no way to tell that anything went wrong. Resolving first means the
     * fallback is a decision rather than a recovery.
     *
     * The last rung is raw usage-access settings, which is where this used to
     * go every time: it is the right answer only on a device with no Wellbeing
     * at all, and it was the answer on every device.
     */
    private fun openWellbeing() {
        val candidates = listOf(
            // The component first, because on the device this was tested on
            // it is the only one that starts. Both actions below fail there
            // with "No activity found": Wellbeing declares its settings
            // Activity without advertising either action publicly, so an
            // action lookup finds nothing however visible the package is.
            //
            // Hardcoding a ComponentName is ordinarily a thing to avoid, and
            // it is safe here for one reason: it is tried through the same
            // canResolve as everything else, so a device without that exact
            // class falls straight through to the next candidate instead of
            // throwing. It is a shortcut past a lookup, never a bypass of one.
            Intent().setComponent(
                ComponentName(WELLBEING_PACKAGE, WELLBEING_SETTINGS_CLASS),
            ),
            Intent("com.google.android.apps.wellbeing.action.DIGITAL_WELLBEING"),
            Intent("android.settings.DIGITAL_WELLBEING_SETTINGS"),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        val resolved = candidates.firstOrNull { canResolve(it) } ?: return
        // Refused or not, the helper's answer is the whole of it: it logs,
        // and says the line when the console is there to say it.
        startFromConsole(resolved, consoleRefusal)
    }

    /** Whether anything on this device handles [intent]. See the shared helper's doc. */
    private fun canResolve(intent: Intent): Boolean = packageManager.canResolve(intent)

    /**
     * Re-assert immersive every time this window takes focus.
     *
     * ## Why here and not in onCreate, where it was
     * `onCreate` runs before the decor view is attached to a window. A
     * `WindowInsetsControllerCompat` resolves through `ViewRootImpl`, which
     * does not exist until the activity is resumed and `WindowManager.addView`
     * has run, so a hide requested from `onCreate` is dropped. The status bar
     * never hid, on any device, at any point: not a shell quirk, which would
     * have differed between two shells, but the same code path failing the
     * same way on both.
     *
     * ## Why focus and not attach
     * `doOnAttach` would fix the first call and nothing else. Focus fixes
     * three things with one mechanism:
     *
     *  1. It fires after attach, so the controller has somewhere to send the
     *     request.
     *  2. It fires again whenever focus returns, so `launchMode="singleTask"`
     *     coming back from a target app re-hides rather than leaving the bar
     *     restored. `onCreate` does not run a second time and that is the
     *     defect a one-shot fix would have left behind.
     *  3. It fires when one of our own focusable windows gives focus back. The
     *     lease gate and the lock overlay are focusable, and although neither
     *     is drawn over the launcher today, a window that takes focus and
     *     returns it is exactly the shape that would undo immersive silently.
     *
     * Focus rather than `onResume` because resume can happen before the window
     * has focus, which is the original bug one lifecycle step later.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    /**
     * Hide the status bar on the console, and only on the console.
     *
     * ## What it is for
     * The clock, the battery and a row of notification icons are the things
     * this screen exists to not be. A launcher that reports four unread
     * messages along the top is a launcher that gives you somewhere to go,
     * and the whole console is arranged around not doing that.
     *
     * The navigation bar stays. It is not carrying anyone's notifications and
     * hiding it would take the back gesture's affordance with it on a
     * three-button device.
     *
     * ## Transient rather than sticky, and what that costs
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` brings the bar back for a swipe
     * from the top edge and lets it go again. The shade is still reachable
     * and it is now **two gestures rather than one**: the first swipe spends
     * itself revealing the bar, the second pulls the shade down.
     *
     * That is a cost and it is the intended one. The shade is the single
     * largest source of "I only came here to check the time", and making it
     * two deliberate gestures rather than one reflex is friction of exactly
     * the kind this app is. It is not a block: nothing is unreachable and no
     * notification is hidden from the system.
     *
     * ## The order of these two calls does not matter, and it was not the bug
     * `setDecorFitsSystemWindows(window, false)` stays in `onCreate`. It is a
     * window layout attribute and it decides whether this app draws behind the
     * bars; this is a controller request and it decides whether the bars are
     * there. Neither depends on the other having run, and the existing order
     * already matched the androidx pattern. Written down because it is the
     * first thing anyone will suspect next time.
     *
     * ## The log line is the diagnostic, not decoration
     * A dropped insets request fails silently: the controller is obtained, the
     * call returns, and nothing happens. That cost a build and a device round
     * trip to establish once. `attached` and the before-and-after visibility
     * make a second failure readable from logcat alone, and a null visibility
     * is itself the signal, because it means the insets are not available yet
     * and the request went nowhere.
     *
     * ## This conceals the inset fault, it does not fix it
     * Every screen in this app clears the system bars with a hardcoded
     * `padding(vertical = 44.dp)` that is not derived from any measurement.
     * With the status bar hidden there is no status bar for that constant to
     * be wrong about, so the fault becomes invisible here and stays exactly
     * as wrong everywhere else: the settings activity, the gate, the lock
     * overlay, and this screen again the moment a transient bar is showing.
     *
     * See CLAUDE.md, "Window insets are a constant, and that is a known
     * fault". That section stands, and landing this does not close it.
     */
    private fun hideStatusBar() {
        val decor = window.decorView
        val controller = WindowInsetsControllerCompat(window, decor)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        Log.i(
            TAG_IMMERSIVE,
            "hide requested: attached=${decor.isAttachedToWindow} before=${statusBarVisible()}",
        )
        // Posted rather than read inline. The request is asynchronous, so a
        // reading taken on this frame reports the state the call was trying to
        // change and would say "still visible" even on a success.
        decor.post {
            Log.i(TAG_IMMERSIVE, "after one frame: statusBarVisible=${statusBarVisible()}")
        }
    }

    /**
     * Whether the status bar is on screen, or null when nothing can say yet.
     *
     * Null is the interesting answer. It means the window has no root insets,
     * which is the state `onCreate` was asking from, and it is the difference
     * between "the request was refused" and "there was nobody to refuse it".
     */
    private fun statusBarVisible(): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun queryLaunchableApps(): List<LaunchableApp> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val pkg = it.activityInfo.packageName
                LaunchableApp(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = pkg,
                    // Never decided here. This query is cached for the life of
                    // the Activity and the tracked set is not; see where
                    // badgedApps is built.
                    isTarget = false,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        /**
         * Its own tag, not the activity's.
         *
         * The one failure mode here is silence: the controller is obtained,
         * the call returns, and nothing happens. A tag that can be filtered on
         * its own is what makes `adb logcat -s Molasses.Immersive` answer the
         * question without a second build.
         */
        const val TAG_IMMERSIVE = "Molasses.Immersive"
    }
}

/** Pager indices. Named because BackHandler and $ status both reference them. */
const val PAGE_CONSOLE = 0
const val PAGE_LEDGER = 1

@Composable
private fun MainLauncherWorkspace(
    appList: List<LaunchableApp>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    actions: LauncherActions,
    onRecordCommand: (String) -> Unit,
    onRequestLockConfirm: (LockConfirmRequest) -> Unit,
    quickLaunch: QuickLaunch.Selection,
    reminders: List<Reminder>,
    onDismissReminder: (Long) -> Unit,
    onKillReminder: (Long) -> Unit,
    cycle: CycleReadout,
    curfewEndMinuteOfDay: Int?,
    nowStamped: () -> StampedInstant,
    console: ConsoleState,
    onDeliverConsoleLine: (ConsoleLine, ConsoleSpeech.Budget) -> Unit,
    onAnswerConsolePrompt: () -> Unit,
    onEnqueueConsoleLine: (ConsoleLine) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    /** The messaging clients to offer under `[messages]`. */
    messagingApps: () -> List<LaunchableApp>,
    /** Walks a ShortcutLadder. False when no rung resolved. */
    onLaunchLadder: (List<ShortcutLadder.Candidate>) -> Boolean,
    onOpenWellbeingSettings: () -> Unit,
    /**
     * Jitter is not the home app. One line under the header until it is,
     * read on resume and never polled. See HomeRole.
     */
    homeLost: Boolean,
    onOpenHomeSettings: () -> Unit,
    /** Where a refused start is said. Handed to the console page. */
    refusal: ConsoleRefusal,
    /**
     * The accessibility service is not working, by `ServiceHealthPolicy.working`,
     * and has not been for ServiceOffLine's grace. One line under the header
     * until it is, above the home line because nothing else on this screen
     * works without it.
     */
    serviceOff: Boolean,
    onOpenAccessibility: () -> Unit,
) {

    // Queried once by the Activity and passed down, rather than re-run on
    // every recomposition. queryIntentActivities is an IPC and this composable
    // recomposes on every keystroke in the command field.
    val apps = appList

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 44.dp),
    ) {
        // Top System / Tab Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    stringResource(R.string.launcher_page_terminal),
                    stringResource(R.string.launcher_page_screentime),
                ).forEachIndexed { index, title ->
                    Text(
                        text = title,
                        fontFamily = FontFamily.Monospace,
                        // Subdued on purpose. Page tabs are navigation
                        // chrome; the status line below is the data, and the
                        // two must not compete.
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Normal,
                        color = if (pagerState.currentPage == index) PhosphorDim else PhosphorDivider,
                    )
                }
            }

            Text(
                text = stringResource(R.string.launcher_cfg),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 9.sp,
                color = PhosphorDim,
                modifier = Modifier
                    .clickable { onOpenSettings() }
                    .padding(4.dp),
            )
        }

        // Above the pager, so they hold on both pages and nothing scrolls them
        // away. Each goes when its cause is fixed and the console reads it
        // again.
        if (serviceOff) {
            Text(
                text = stringResource(R.string.launcher_service_off),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAccessibility() }
                    .padding(top = 10.dp, bottom = 2.dp),
            )
        }
        if (homeLost) {
            Text(
                text = stringResource(R.string.launcher_home_lost),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenHomeSettings() }
                    .padding(top = 10.dp, bottom = 2.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Above the pager on purpose. The pager disposes the off-screen page,
        // so an origin captured inside the console restarted every time the
        // user swiped to the ledger and back, taking Bit's blink schedule
        // with it. Remembered here, it outlives the page.
        val bitOrigin = remember { SystemClock.elapsedRealtime() }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                PAGE_CONSOLE -> TerminalHomeView(
                    bitOrigin = bitOrigin,
                    apps = apps,
                    actions = actions,
                    onRecordCommand = onRecordCommand,
                    onRequestLockConfirm = onRequestLockConfirm,
                    quickLaunch = quickLaunch,
                    reminders = reminders,
                    onDismissReminder = onDismissReminder,
                    onKillReminder = onKillReminder,
                    cycle = cycle,
                    curfewEndMinuteOfDay = curfewEndMinuteOfDay,
                    nowStamped = nowStamped,
                    console = console,
                    onDeliverConsoleLine = onDeliverConsoleLine,
                    onAnswerConsolePrompt = onAnswerConsolePrompt,
                    onEnqueueConsoleLine = onEnqueueConsoleLine,
                    onOpenDrawer = onOpenDrawer,
                    onLaunchPackage = onLaunchPackage,
                    onDialer = onDialer,
                    messagingApps = messagingApps,
                    onLaunchLadder = onLaunchLadder,
                    refusal = refusal,
                )
                PAGE_LEDGER -> TextualWellbeingView(
                    shown = pagerState.settledPage == PAGE_LEDGER,
                    cycle = cycle,
                    nowStamped = nowStamped,
                    onOpenWellbeing = onOpenWellbeingSettings,
                )
            }
        }
    }
}

@Composable
private fun TerminalHomeView(
    /**
     * Origin for Bit's tick, held above the pager so swiping to the ledger
     * and back does not restart the blink schedule.
     */
    bitOrigin: Long,
    apps: List<LaunchableApp>,
    actions: LauncherActions,
    /** Records the line the user typed. A lock confirmation is a button and never reaches here. */
    onRecordCommand: (String) -> Unit,
    /** Hands a long lock to the full-screen panel at the activity root. */
    onRequestLockConfirm: (LockConfirmRequest) -> Unit,
    /** The user's quick-launch rows, as stored. Resolved here against [apps]. */
    quickLaunch: QuickLaunch.Selection,
    /** Every reminder not yet dismissed. The fired ones are shown here, oldest first. */
    reminders: List<Reminder>,
    onDismissReminder: (Long) -> Unit,
    /** Kill a pending reminder from the bare `$ rem` list. */
    onKillReminder: (Long) -> Unit,
    cycle: CycleReadout,
    /** Minute of day a bedtime lock lifts, or null when none stands. */
    curfewEndMinuteOfDay: Int?,
    nowStamped: () -> StampedInstant,
    console: ConsoleState,
    onDeliverConsoleLine: (ConsoleLine, ConsoleSpeech.Budget) -> Unit,
    onAnswerConsolePrompt: () -> Unit,
    onEnqueueConsoleLine: (ConsoleLine) -> Unit,
    onOpenDrawer: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    /** The messaging clients to offer under `[messages]`. */
    messagingApps: () -> List<LaunchableApp>,
    /** Walks a ShortcutLadder. False when no rung resolved. */
    onLaunchLadder: (List<ShortcutLadder.Candidate>) -> Boolean,
    /** Set to this page's answer line while it is composed. See ConsoleRefusal. */
    refusal: ConsoleRefusal,
) {
    val context = LocalContext.current

    // Dismissed by hand on execute. KeyboardActions carries a
    // defaultKeyboardAction, but its only hiding branch is ImeAction.Done:
    // Go falls through to the else and does nothing, so calling it here
    // would read as a fix and behave as a no-op. Focus is deliberately left
    // where it is; hiding the keyboard does not require clearing it, and
    // clearing it would take the cursor out of the prompt the user is still
    // working in.
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * The filter and the prompt are the same field.
     *
     * Held in `remember`, which survives for as long as this composable is in
     * composition, and launching an app does not take it out of composition:
     * the Activity is stopped, not destroyed. So the text the user typed to
     * find WhatsApp was still there when they came back from WhatsApp, along
     * with the filtered list it produced, and it survived the screen going
     * off for the same reason.
     *
     * Cleared on resume below, and on every launch, rather than made
     * `rememberSaveable`, which would have made it survive *more*.
     */
    var query by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(false) }
    var lastKeystrokeMs by remember { mutableLongStateOf(0L) }

    /**
     * The screen has been off since the last time the console was resumed.
     *
     * This is what makes the greeting fire on the first launcher visit after
     * an unlock rather than on every return to home. Coming back from
     * Instagram is not an arrival; waking the phone is.
     *
     * Seeded true so a cold start greets. Opening to a grey slit with nothing
     * to say was the first thing anyone saw.
     */
    var sleptSinceLastVisit by remember { mutableStateOf(true) }

    // Bit's resting face comes from the pure state machine, which owns the
    // blink timing. The tick is the monotonic clock so the phase is
    // reproducible and so a wall-clock change cannot freeze a frame.
    var bitTickMs by remember { mutableLongStateOf(0L) }
    // Carried rather than re-derived. The pure derivation walks every cycle
    // since zero to find the current one, which is a few hundred iterations
    // per frame after an hour on the launcher and hundreds of thousands after
    // a day. Stepping the cycle forward is one comparison.
    var blinkCycle by remember { mutableStateOf(BitStateMachine.BlinkCycle()) }
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(bitOrigin) {
        while (true) {
            val tick = SystemClock.elapsedRealtime() - bitOrigin
            bitTickMs = tick
            val next = BitStateMachine.advanceBlink(blinkCycle, tick)
            blinkCycle = next
            val shut = BitStateMachine.isBlinking(next, tick)
            // Before the write, so the line describes the value about to be
            // published rather than the previous one. Debug variant only.
            BitTrace.tick(bitOrigin, tick, next, shut)
            blinking = shut
            delay(BitStateMachine.TICK_MS)
        }
    }

    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.let {
            val level = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) batteryPercent = level
        }
        while (true) {
            val now = Date()
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            dateText = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now)
            delay(1000L)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    sleptSinceLastVisit = true
                    return
                }
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                // Never assume scale is 100. Some devices report 255.
                if (level >= 0 && scale > 0) {
                    batteryPercent = (level * 100) / scale
                }
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            // The unlock signal for the greeting. SCREEN_OFF rather than
            // USER_PRESENT, because USER_PRESENT never fires on a phone with
            // no secure lock screen, and the greeting would be dead on
            // exactly the devices most likely to run a launcher like this.
            addAction(Intent.ACTION_SCREEN_OFF)
            // ACTION_TIME_TICK alone fires once a minute and never reports a
            // date rollover or a timezone move, so the date goes stale.
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    val filteredApps = remember(query, apps) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) emptyList()
        else apps.filter {
            it.label.contains(trimmed, ignoreCase = true) ||
            it.packageName.contains(trimmed, ignoreCase = true)
        }
    }

    // Inline autocomplete. Shows the argument *shape* only, never a filled in
    // value: one stray completion must not be able to arm a real lock. The
    // hint is drawn in the divider step, dim enough to ignore.
    val commandHint = remember(query) { CommandParser.hintFor(query) }

    // The inline manual. Not an overlay: a popup over a terminal is a
    // different interface wearing the terminal's clothes, and the app list is
    // exactly the space a list of commands wants.
    var showManual by remember { mutableStateOf(false) }

    /**
     * The messaging clients offered under `[messages]`, or null when closed.
     *
     * Queried on the tap rather than at composition. It is a PackageManager
     * call per press on a row nobody presses in a loop, and querying up front
     * would be an IPC on every visit to the launcher for a list most visits
     * never look at.
     */
    var messagingChoices by remember { mutableStateOf<List<LaunchableApp>?>(null) }

    // The dispatcher, rebuilt only when the action table changes. Surfaces
    // read live state when asked, so nothing here needs to recompose for the
    // service binding or an app being installed.
    val dispatch = remember(actions, context) {
        launcherDispatch(context, actions) { showManual = true }
    }

    // The answer to the last utility command, already rendered.
    //
    // Not a reaction. It is cleared by the three things that mean the user
    // has moved on: the next keystroke, the next command, and leaving the
    // launcher. See BitDisplay.Answer for why a duration was the wrong shape
    // rather than the wrong number for content.
    //
    // The one exception is an acknowledgement marked readingWindow (the
    // reminder's), which also goes by itself after ReadingWindow.holdMs. Its
    // clock is tied to that answer by AnswerTimer: every show and every clear
    // moves the serial on, so a clock can only dismiss the answer it was
    // started for, and a cleared answer's clock does nothing at all.
    var answer by remember { mutableStateOf<String?>(null) }
    var answerTimer by remember { mutableStateOf(AnswerTimer()) }
    var answerShownAtMs by remember { mutableLongStateOf(0L) }
    // The pending reminders a bare `rem` listed, drawn under its answer as
    // rows, and the one whose [kill] is showing. See PendingReminderRow.
    var answerReminderIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var killArmedId by remember { mutableStateOf<Long?>(null) }
    // A deferred command is in flight. See the Deferred branch of handleOutcome.
    var awaitingDeferred by remember { mutableStateOf(false) }
    // Runs the deferred answer's deadline. Cancelled with the console.
    val deferredScope = rememberCoroutineScope()
    // Every clear goes through here, so every clear retires the clock.
    fun clearAnswer() {
        answer = null
        answerTimer = AnswerTimer.cleared(answerTimer)
    }
    // Restarted, and so cancelled, on every show and every clear.
    LaunchedEffect(answerTimer.serial) {
        val started = answerTimer
        val hold = started.holdMs ?: return@LaunchedEffect
        delay(hold)
        if (AnswerTimer.mayExpire(answerTimer, started.serial)) {
            Log.i(CONSOLE_TAG, "answer expired after its reading window of ${hold}ms")
            clearAnswer()
        }
    }

    // The dim remainder of a unique verb prefix, drawn under the caret.
    val ghost = remember(query) { CommandParser.ghostFor(query) }

    // Availability costs a few binder calls (resolveActivity), so the rows
    // are built once and rebuilt when the manual opens rather than on every
    // keystroke. The manual is the only place staleness would show, and it is
    // fresh every time it is opened.
    val manualRows = remember(dispatch, showManual) {
        Manual.rows(dispatch.registry, dispatch::availabilityOf)
    }

    // Bit's transient reaction and when it started. The monotonic clock, so a
    // clock change cannot leave a face stuck on screen.
    var reaction by remember { mutableStateOf<BitStateMachine.Reaction>(BitStateMachine.Reaction.None) }
    var reactionStartedMs by remember { mutableLongStateOf(0L) }
    var reactionAgeMs by remember { mutableLongStateOf(0L) }

    fun react(next: BitStateMachine.Reaction) {
        reaction = next
        reactionStartedMs = SystemClock.elapsedRealtime()
        reactionAgeMs = 0L
    }

    // The terminal burst. Fired on the crossing rather than on the level, so
    // it happens once per entry: the deepest app's accumulated time only
    // falls at a cycle rollover, which makes the rollover the only thing that
    // re-arms it. Null until the first observation, so a launcher that starts
    // up already past the terminal does not burst for a threshold that was
    // crossed twenty minutes ago.
    var lastDeepestMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(cycle.deepestAppMs) {
        val previous = lastDeepestMs
        lastDeepestMs = cycle.deepestAppMs
        if (BitStatus.crossedTerminal(previous, cycle.deepestAppMs, cycle.deepestHorizonMs)) {
            react(BitStateMachine.Reaction.Glitching)
        }
    }

    // The two zero-interaction tells. Polled rather than pushed, because
    // ServiceDiagnostics is a plain object written from the accessibility
    // callback thread and has no change signal to collect. The poll runs at
    // the frame rate Bit already recomposes at, and only while this console
    // is composed.
    var shutterArmed by remember { mutableStateOf(false) }
    var gateShowing by remember { mutableStateOf(false) }
    var callInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var lastAbsorbed = ServiceDiagnostics.lastTouchAbsorbedElapsedMs
        while (true) {
            shutterArmed = ServiceDiagnostics.shutterArmed()
            gateShowing = ServiceDiagnostics.gateShowing
            // AudioManager.getMode is a cached binder read, and it catches
            // VoIP as well as cellular, which is why the shutter already uses
            // it rather than a phone-state permission.
            callInProgress = audio?.mode?.let {
                it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
            } ?: false
            val absorbed = ServiceDiagnostics.lastTouchAbsorbedElapsedMs
            if (absorbed != lastAbsorbed) {
                lastAbsorbed = absorbed
                // Silent. No line, now or ever: the moment Bit narrates a
                // stall the uncanny phase is over, and the dry acknowledgment
                // after nine minutes is a separate thing that stays separate.
                react(BitStateMachine.Reaction.Absorbed)
            }
            delay(BitStateMachine.TICK_MS)
        }
    }

    /**
     * Back to the empty state.
     *
     * The prompt, the filter and the manual are one surface, so they clear
     * together. Command history is deliberately
     * not touched: it is persisted, it is still recorded on every dispatch,
     * and clearing the prompt is not a request to forget what was typed. It
     * simply has no view any more.
     */
    fun clearPrompt() {
        query = ""
        showManual = false
        messagingChoices = null
        // Called on the way out and on the way back in, which is where an
        // answer stops being one. A conversion still sitting there from
        // before you left is stale content on a surface whose whole argument
        // is that nothing sits on it without earning the space.
        clearAnswer()
    }

    /** Launch, and leave the prompt empty behind it. */
    fun launchAndClear(pkg: String) {
        clearPrompt()
        onLaunchPackage(pkg)
    }

    /**
     * Walk a ladder, and say something when no rung answers.
     *
     * The saying is the whole point. These rows used to wrap startActivity in
     * a runCatching and discard the result, so a device whose clock app does
     * not declare SHOW_ALARMS had a `[clock]` row that did nothing, silently,
     * every time. Bit's unavailable face is the same tell the command bar
     * already uses for a command this device cannot run, which is exactly
     * what this is.
     */
    fun launchLadderOrSay(ladder: List<ShortcutLadder.Candidate>) {
        messagingChoices = null
        if (!onLaunchLadder(ladder)) {
            react(
                BitStateMachine.Reaction.Unavailable(
                    context.getString(R.string.launcher_open_refused),
                ),
            )
        }
    }

    // A start the shared helper refused: the drawer, quick launch, [phone],
    // a rung or a command. The same face and the same line as a ladder with
    // no rung, because to the user it is the same thing. Said at once, so a
    // command's own answer, given after, replaces it rather than racing it.
    val sayRefused by rememberUpdatedState {
        react(BitStateMachine.Reaction.Unavailable(context.getString(R.string.launcher_open_refused)))
    }
    DisposableEffect(refusal) {
        refusal.say = { sayRefused() }
        onDispose { refusal.say = null }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Belt to the launch path's braces. A launch clears it directly,
            // and this catches every other way back: recents, the system back
            // gesture, the screen coming on.
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            clearPrompt()
            if (sleptSinceLastVisit) {
                sleptSinceLastVisit = false
                // Queued rather than shown, so it takes exactly the same
                // delivery path as everything else Bit says: the same
                // suppression rules, the same row, the same eight seconds.
                // Its own counter is the only difference, and that lives
                // inside ConsoleSpeech.
                val c = Calendar.getInstance()
                Greeting.lineFor(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
                    ?.let(onEnqueueConsoleLine)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Bit's readout. The step advances on a tap and never on a timer: a
    // rotation that moved by itself would mean a user glancing up mid-cycle
    // reads a number with no label and no way to know which one it is.
    var hudStep by remember { mutableStateOf(HudStep.NONE) }
    // Held on the same tick that already drives the blink and the placeholder,
    // so the readout costs no timer of its own.
    var hudStartedTick by remember { mutableLongStateOf(0L) }

    // Seeded at composition rather than at zero. At zero Bit was docked
    // before the first frame ever drew, which is how the face, the blink and
    // the mood all ended up behind a gesture nobody knew to perform. Landing
    // on the launcher now shows the face, and the retreat is something the
    // user watches happen.
    var lastBitTouchMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Section 05: retreat while typing or idle. Docked Bit answers a question
    // on tap; undocked Bit keeps the startle reaction. One state, two
    // behaviours, and no new gesture to learn.
    // Derived here for rendering and re-derived at the tap, both through
    // dockedNow(), so the two can never be computed from different values.
    // A Boolean captured in the tap lambda would be whatever the composition
    // that built that lambda saw, and the tap decision is the one place a
    // stale answer costs the whole gesture.
    fun dockedNow(): Boolean = BitDock.isDocked(
        hasText = query.isNotEmpty(),
        msSinceInteraction = SystemClock.elapsedRealtime() - lastBitTouchMs,
        msSinceKeystroke = SystemClock.elapsedRealtime() - lastKeystrokeMs,
    )

    val docked = dockedNow()

    // Derived, not written. This used to be a state assignment in the middle
    // of composition, which Compose treats as a backwards write: it happened
    // to converge because the condition is false afterwards, but it is
    // unsupported and it had never been run. Deriving costs nothing and needs
    // no timer either, because bitTickMs is already ticking for the blink.
    fun hudStepNow(): HudStep = if (BitHud.isExpired(bitTickMs - hudStartedTick)) {
        HudStep.NONE
    } else {
        hudStep
    }

    val hudVisibleStep = hudStepNow()

    // Drives the reaction clock, and only while a reaction is running. An
    // always-on ticker would recompose the console forever for nothing.
    LaunchedEffect(reaction, reactionStartedMs) {
        if (reaction == BitStateMachine.Reaction.None) return@LaunchedEffect
        while (true) {
            reactionAgeMs = SystemClock.elapsedRealtime() - reactionStartedMs
            if (BitStateMachine.isExpired(reaction, reactionAgeMs)) {
                reaction = BitStateMachine.Reaction.None
                return@LaunchedEffect
            }
            delay(BitStateMachine.TICK_MS)
        }
    }

    /**
     * Enter. Tries the grammar first, then falls back to launching the top
     * filtered app.
     *
     * The order matters: a command that happens to share a prefix with an app
     * name must still run as a command, because the grammar is the thing the
     * user typed deliberately.
     */
    fun submit(): DispatchResult {
        val text = query.trim()
        if (text.isEmpty()) return DispatchResult.NotACommand

        val parsed = CommandParser.parse(text)
        if (parsed !is ParseResult.Ok) Log.i(CONSOLE_TAG, "submit: not parsed as a command")
        return when (parsed) {
            is ParseResult.Ok -> {
                // The verb and whether it was the bare-rem list, never the
                // text: a reminder's text is the user's.
                Log.i(
                    CONSOLE_TAG,
                    "submit: parsed verb=${CommandRegistry.verbOf(parsed.command)}" +
                        if (parsed.command == Command.RemList) " (pending list)" else "",
                )
                // What the user typed, not the canonical form. It comes back
                // out of history the way they wrote it.
                onRecordCommand(text)
                dispatch.dispatch(parsed.command)
            }
            is ParseResult.Err -> when (parsed.error) {
                // Not a command at all: fall back to app filtering, which is
                // what a bare app name is.
                is ParseError.UnknownCommand, ParseError.Empty -> DispatchResult.NotACommand
                // A real command typed wrong. Report it rather than silently
                // trying to launch an app called "block".
                else -> parsed.error.asFailure()
            }
        }
    }

    /**
     * What the console does with a command's outcome. One place, because the
     * lock panel's commit comes back through here as well as Enter does.
     */
    fun handleOutcome(outcome: DispatchResult) {
        when (outcome) {
            is DispatchResult.Confirmed -> {
                react(BitStateMachine.Reaction.Confirm(outcome.message(context)))
                query = ""
            }
            // Not a reaction. It stays until the user does something else,
            // which is the honest lifetime for content they asked for and may
            // be copying somewhere.
            is DispatchResult.Answered -> {
                val text = outcome.message(context)
                // The answer and the cleared prompt land in the same frame.
                answer = text
                answerTimer = AnswerTimer.shown(
                    answerTimer,
                    holdMs = if (outcome.readingWindow) ReadingWindow.holdMs(text) else null,
                )
                answerReminderIds = outcome.reminderIds
                killArmedId = null
                answerShownAtMs = SystemClock.elapsedRealtime()
                query = ""
                Log.i(
                    CONSOLE_TAG,
                    "answer shown: ${ReadingWindow.words(text)} words, " +
                        "hold=${answerTimer.holdMs?.let { "${it}ms" } ?: "until the user moves on"}",
                )
            }
            // A third face, not the dry one. "Locks are not enforced yet" and
            // "block what?" are different information, and showing the same
            // face for both teaches the user to ignore it.
            is DispatchResult.Unavailable ->
                react(BitStateMachine.Reaction.Unavailable(outcome.message(context)))
            is DispatchResult.Failed ->
                react(BitStateMachine.Reaction.Failed(outcome.message(context)))
            // A long lock. It goes to the full-screen panel rather than back
            // into the prompt. The commit dispatches it confirmed and comes
            // back through here; back on the panel writes nothing. A block
            // whose app does not resolve to one package has no panel, and is
            // dispatched confirmed straight away for the same refusal the
            // one-step path gives, with nothing armed.
            is DispatchResult.NeedsConfirmation -> {
                val label = (outcome.command as? Command.Block)?.let { block ->
                    (actions.resolveApp(block.appToken) as? AppTokenResolver.Result.One)?.let { one ->
                        apps.firstOrNull { it.packageName == one.pkg }?.label ?: one.pkg
                    }
                }
                val panel = LockConfirmation.panelFor(outcome.command, label)
                if (panel == null) {
                    handleOutcome(dispatch.dispatch(outcome.command, confirmed = true))
                } else {
                    query = ""
                    onRequestLockConfirm(
                        LockConfirmRequest(panel) {
                            handleOutcome(dispatch.dispatch(panel.command, confirmed = true))
                        },
                    )
                }
            }
            DispatchResult.NotACommand ->
                if (filteredApps.isNotEmpty()) {
                    launchAndClear(filteredApps.first().packageName)
                }
            // The answer comes later, through this same function, once the
            // command knows it. See DispatchResult.Deferred.
            //
            // The prompt keeps what was typed until then. Clearing it here
            // drew one frame with the prompt empty and the list collapsed,
            // then a second with the answer: two layout passes a few
            // milliseconds apart, which read as a flicker. The delivered
            // outcome clears the prompt in the same frame it shows the
            // answer, and a refusal leaves the line to be corrected, like any
            // other failure.
            // With the line still in the prompt, a second Enter would run it
            // twice, so Enter is ignored until the answer lands.
            //
            // Not forever: after DeferredWait.TIMEOUT_MS with no answer, a
            // failure line replaces it and Enter comes back, so a command
            // that never answers cannot leave the prompt dead.
            is DispatchResult.Deferred -> {
                Log.i(CONSOLE_TAG, "deferred: awaiting the command's answer")
                awaitingDeferred = true
                DeferredWait.start(
                    scope = deferredScope,
                    deferred = outcome,
                    timedOut = DispatchResult.Failed(R.string.cmd_err_deferred_timeout),
                    onLate = { Log.w(CONSOLE_TAG, "deferred: answer arrived after the deadline and was dropped") },
                ) { result ->
                    awaitingDeferred = false
                    if (result is DispatchResult.Failed && result.reasonKey == R.string.cmd_err_deferred_timeout) {
                        Log.w(CONSOLE_TAG, "deferred: no answer in ${DeferredWait.TIMEOUT_MS}ms, Enter released")
                    }
                    handleOutcome(result)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Up opens the drawer. Down does nothing, deliberately.
                //
                // It used to open a notification inbox overlay that has no
                // source of notifications behind it, so the gesture led to an
                // empty screen and, worse, consumed a downward drag on the
                // home screen for it. Removing the branch is only half the
                // point: `detectVerticalDragGestures` consumes the whole
                // drag, so the handler now ends on a downward pull without
                // acting, which is the honest behaviour for a feature that
                // does not exist.
                //
                // It does not hand the notification shade a new way in, and
                // nothing here could. The shade opens from a swipe inside the
                // system gesture inset at the top edge, which this composable
                // never receives; a mid-screen pull-down on the home screen is
                // a launcher feature elsewhere, not a system one.
                //
                // The threshold is a drag distance rather than a velocity so a
                // slow deliberate pull works as well as a flick.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged <= -SWIPE_THRESHOLD_PX) onOpenDrawer()
                    },
                ) { _, amount -> dragged += amount }
            },
    ) {
        PowerLine(
            percent = batteryPercent,
            charging = charging,
            timeText = timeText,
            dateText = dateText,
        )

        Spacer(Modifier.height(14.dp))

    // Bit's speech. A delivered notice lives here rather than in the store,
    // because its eight seconds start at the first composition that draws it
    // and only the host knows when that was.
    var liveNotice by remember { mutableStateOf<ConsoleLine.Notice?>(null) }
    var noticeStartedTick by remember { mutableLongStateOf(0L) }
    // The id already handed to the store, so a second composition before the
    // write lands cannot deliver it twice.
    var deliveringId by remember { mutableStateOf<String?>(null) }


    val visibleNotice = liveNotice?.takeIf {
        !ConsoleSpeech.noticeExpired(bitTickMs - noticeStartedTick)
    }

    // Everything above a notice in the precedence table. Computed here rather
    // than inside resolve, because a notice that would lose has to stay
    // queued and cost nothing, and only the caller can decide not to spend.
    val noticeOutranked = reaction != BitStateMachine.Reaction.None ||
        console.live != null ||
        hudVisibleStep != HudStep.NONE

    LaunchedEffect(console.queued, noticeOutranked, gateShowing, callInProgress) {
        val queued = console.queued ?: return@LaunchedEffect
        if (queued.id == deliveringId) return@LaunchedEffect
        // An id with no copy renders as an empty row. Drop it rather than
        // spending one of three an hour on nothing.
        if (ConsoleCopy.textRes(queued.id) == null) return@LaunchedEffect

        val verdict = ConsoleSpeech.evaluate(
            queued = queued,
            budget = console.budget,
            gate = ConsoleSpeech.Gate(
                callInProgress = callInProgress,
                // Structurally false here: at delivery the foreground package
                // is Jitter, because this row only exists on the console.
                // Carried anyway so a future surface that is not the console
                // inherits the check rather than rediscovering it.
                sensitiveForeground = false,
                gateActive = gateShowing,
                outranked = noticeOutranked,
            ),
            cycleAnchorWallMs = cycle.anchor.wallMs,
            nowWallMs = System.currentTimeMillis(),
        )
        if (verdict !is ConsoleSpeech.Verdict.Render) return@LaunchedEffect

        deliveringId = queued.id
        if (verdict.line is ConsoleLine.Notice) {
            liveNotice = verdict.line
            noticeStartedTick = bitTickMs
        }
        onDeliverConsoleLine(verdict.line, verdict.budget)
    }

        // Resolved once, here, through the one precedence table:
        // glitch > HUD > reaction > mood.
        // The deepest app, never the sum. The curve is per package.
        val mood = BitStateMachine.moodFor(cycle.deepestAppMs, cycle.deepestHorizonMs)
        // On a change only, never per frame: the log that says which resting
        // face the console is drawing, and why.
        LaunchedEffect(mood) {
            Log.i(
                BIT_TAG,
                "mood=$mood deepestMs=${cycle.deepestAppMs} horizonMs=${cycle.deepestHorizonMs}",
            )
        }
        val display = BitDisplay.resolve(
            mood = mood,
            reaction = reaction,
            hud = if (hudVisibleStep == HudStep.NONE) {
                null
            } else {
                BitDisplay.Hud(
                    step = hudVisibleStep,
                    text = BitHud.textFor(
                        step = hudVisibleStep,
                        cycleRemainingMs = CycleWindow.remainingMs(cycle.anchor, nowStamped()),
                        cumulativeMs = cycle.cumulativeMs,
                        curfewEndMinuteOfDay = curfewEndMinuteOfDay,
                    ),
                )
            },
            shutterArmed = shutterArmed,
            curfew = curfewEndMinuteOfDay != null,
            docked = docked,
            penaltyAccruing = cycle.penaltyAccruing,
            answer = answer,
            // The battery reading the power bar is already showing. Below
            // five percent Bit changes identity; at fifteen the bar has
            // already dimmed a step. An escalation, not the same signal
            // twice.
            batteryCritical = PowerBar.isCritical(batteryPercent),
            prompt = console.live,
            notice = visibleNotice,
        )

        BitCompanion(
            // While Bit is speaking it is in the row above the prompt, so its
            // usual row holds the empty slot. The row keeps its height, so
            // nothing below it moves, and there is one Bit rather than a face
            // here and a second one down there.
            frame = if (display is BitDisplay.Spoken) {
                BitStateMachine.BitFrame(face = "")
            } else {
                BitStateMachine.frame(display, reactionAgeMs, bitTickMs, blinking)
                    .also { BitTrace.drew(it.face) }
            },
            onInteract = { lastBitTouchMs = SystemClock.elapsedRealtime() },
            onTap = { taps ->
                // Branch first, then decide whether it counted. Setting the
                // idle clock before the branch un-docked Bit on the tap that
                // opened the readout, so the second tap took the other branch
                // and two thirds of the readout was unreachable.
                val action = BitTap.onTap(dockedNow(), hudStepNow(), taps)
                when (action) {
                    is BitTap.Action.StepHud -> {
                        hudStep = action.step
                        hudStartedTick = bitTickMs
                        reaction = BitStateMachine.Reaction.None
                    }
                    is BitTap.Action.React -> {
                        hudStep = HudStep.NONE
                        react(action.reaction)
                    }
                }
                if (action.undocks) lastBitTouchMs = SystemClock.elapsedRealtime()
            },
        )

        Spacer(Modifier.height(14.dp))

        Spacer(Modifier.height(12.dp))

        // Favourites, one per line, left aligned and ragged right. The
        // brackets are the affordance: in a zero-border layout they are the
        // only thing distinguishing something pressable from something
        // listed, and they match [CFG] and the gate's [DO IT].
        //
        // The rows are the user's, from CFG, and default to the five this
        // screen always had. An app row is its label, bracketed and
        // lowercase like the built-ins. An app that is no longer installed
        // is not in [apps], so it is skipped here rather than drawn as a row
        // that does nothing, and the next edit prunes it from the store.
        val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
        Column(modifier = Modifier.fillMaxWidth()) {
            QuickLaunch.visible(quickLaunch) { it in appLabels }.forEach { entry ->
                when (entry) {
                    is QuickLaunch.Entry.App -> FavouriteText(
                        stringResource(R.string.launcher_fav_app_fmt, appLabels.getValue(entry.pkg).lowercase()),
                    ) { launchAndClear(entry.pkg) }
                    is QuickLaunch.Entry.Row -> when (entry.builtIn) {
                        QuickLaunch.BuiltIn.PHONE -> Favourite(R.string.launcher_fav_phone, onDialer)
                        // [messages] is the one with a choice behind it, so it
                        // expands in place rather than launching. Everything
                        // else walks its ladder and says so when nothing on
                        // the device answers.
                        QuickLaunch.BuiltIn.MESSAGES -> Favourite(R.string.launcher_fav_messages) {
                            val clients = messagingApps()
                            when (clients.size) {
                                // Nothing to pick from, and nothing to say it
                                // with except Bit. A list of none is not a
                                // selector.
                                0 -> react(
                                    BitStateMachine.Reaction.Unavailable(
                                        context.getString(R.string.launcher_fav_none_messaging),
                                    ),
                                )
                                // A list of one is a tax, not a choice.
                                1 -> launchAndClear(clients.first().packageName)
                                else -> messagingChoices = clients
                            }
                        }
                        QuickLaunch.BuiltIn.CALENDAR -> Favourite(R.string.launcher_fav_calendar) {
                            launchLadderOrSay(ShortcutLadder.CALENDAR)
                        }
                        QuickLaunch.BuiltIn.CALCULATOR -> Favourite(R.string.launcher_fav_calculator) {
                            launchLadderOrSay(ShortcutLadder.CALCULATOR)
                        }
                        QuickLaunch.BuiltIn.CLOCK -> Favourite(R.string.launcher_fav_clock) {
                            launchLadderOrSay(ShortcutLadder.CLOCK)
                        }
                    }
                }
            }

            // The selector, inline under the row that opened it. Not a
            // dialog and not an overlay: a popup over a terminal is a
            // different interface wearing the terminal's clothes, and this is
            // the same inline list pattern the manual and the app filter use.
            val choices = messagingChoices
            if (choices != null) {
                for (app in choices) {
                    Text(
                        text = app.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorDim,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                messagingChoices = null
                                launchAndClear(app.packageName)
                            }
                            .padding(start = 14.dp, top = 5.dp, bottom = 5.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Bit's one speech row, immediately above the prompt. Never an
        // overlay: no new window, nothing in the collision guard, no exposure
        // to the financial suppression set. Bit speaks here or it does not
        // speak.
        // $ rem. A fired reminder is shown here until it is dismissed, one at
        // a time and oldest first, so several read in the order they fell
        // due. Its own row and its own queue: it is something the user asked
        // for, so it never passes through ConsoleSpeech, never counts against
        // Bit's hourly or daily caps, and cannot be displaced by a notice.
        ReminderBook.toShow(reminders).firstOrNull()?.let { reminder ->
            ReminderRow(
                face = BitGlyph.pad(BitStateMachine.NEUTRAL),
                text = stringResource(
                    if (reminder.late) R.string.console_reminder_late_fmt else R.string.console_reminder_fmt,
                    reminder.text,
                ),
                onDismiss = { onDismissReminder(reminder.id) },
            )
        }

        val spoken = display as? BitDisplay.Spoken
        if (spoken != null) {
            val said = spoken as? BitDisplay.Speech
            ConsoleSpeechRow(
                face = BitGlyph.pad(
                    BitStateMachine.frame(spoken, reactionAgeMs, bitTickMs, blinking).face,
                ),
                text = when (spoken) {
                    // Already rendered by the dispatcher, which resolved the
                    // answer and its note through the context before the two
                    // were joined. Nothing to look up and nothing to guard.
                    is BitDisplay.Answer -> spoken.text
                    // Resolved through the context rather than stringResource
                    // so the format call can be guarded. A persisted line
                    // carries however many arguments it was queued with, and
                    // a file written by a different build could supply fewer
                    // than the copy takes, which is an IllegalFormatException
                    // on a row that is meant to be the gentlest thing in the
                    // app.
                    is BitDisplay.Speech -> ConsoleCopy.textRes(spoken.line.id)?.let { res ->
                        runCatching {
                            context.getString(res, *spoken.line.args.toTypedArray())
                        }.getOrDefault("")
                    } ?: ""
                },
                prompt = said?.line as? ConsoleLine.Prompt,
                onAnswer = { confirmed ->
                    // Both answers clear it. What [DO IT] runs is the action
                    // the producer named, and no producer emits a prompt yet,
                    // so the effect table is empty rather than guessed at.
                    if (confirmed && said != null) runConsoleAction(said.line)
                    liveNotice = null
                    deliveringId = null
                    onAnswerConsolePrompt()
                },
                onDismissNotice = {
                    // One dismissal for the one row. An answer is the only
                    // thing here the user asked for, so it is also the only
                    // thing they might dismiss on purpose.
                    clearAnswer()
                    liveNotice = null
                    deliveringId = null
                },
            )
            // A bare `rem`: its pending reminders, one row each, under the
            // header. Drawn from the live list, so a reminder killed or fired
            // since the list was asked for drops out without a second `rem`.
            if (spoken is BitDisplay.Answer && answerReminderIds.isNotEmpty()) {
                val rows = ReminderBook.pending(reminders).filter { it.id in answerReminderIds }
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cmd_ans_rem_none),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = PhosphorDim,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                rows.forEach { pending ->
                    PendingReminderRow(
                        text = stringResource(
                            R.string.cmd_ans_rem_row,
                            lockOpensAtText(context, pending.due.wallMs),
                            pending.text,
                        ),
                        armed = killArmedId == pending.id,
                        onTap = { killArmedId = if (killArmedId == pending.id) null else pending.id },
                        onKill = {
                            killArmedId = null
                            onKillReminder(pending.id)
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Monospace Search Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.launcher_prompt_symbol),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = PhosphorGreen,
                fontSize = 13.sp,
            )

            PromptCursor(lastKeystrokeMs = lastKeystrokeMs)

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    // One line, and it does not move.
                    //
                    // It used to rotate through the commands available on
                    // this device, a new one every five seconds off Bit's
                    // tick. The idea was that discovery cost no timer of its
                    // own; the effect was a hint that changed while you were
                    // reading it, so you either read it twice or stopped
                    // reading it. A prompt with a moving label is also a
                    // prompt that never looks idle, which is the opposite of
                    // what this screen is for.
                    //
                    // So: name the one command that opens the whole list, and
                    // let the list do the explaining. "?" is on the STATE
                    // surface, which nothing can make unavailable, so this
                    // advice cannot dead-end on a degraded device.
                    // ManualTest pins that.
                    Text(
                        text = stringResource(R.string.launcher_search_placeholder),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorDim,
                        maxLines = 1,
                    )
                }

                // Ghost completion. Drawn under the field, with the typed
                // characters transparent so the green text on top lands on
                // them exactly. Monospace at the same size is what makes that
                // alignment hold rather than approximately hold.
                if (ghost != null) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.Transparent)) { append(query) }
                            withStyle(SpanStyle(color = PhosphorDivider)) { append(ghost) }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }

                // The field's own value, with its selection and the keyboard's
                // composing region, kept beside `query`.
                //
                // The String overload kept the composing region across a
                // programmatic clear, so the keyboard could go on composing a
                // word the prompt no longer held, and its next commit arrived
                // as an edit after Enter had already put an answer up. Any
                // edit clears the answer, so the answer vanished. A letter
                // word such as a bare `rem` is composed; digits, as in calc,
                // are not, which fits calc's answers staying and rem's not.
                // That is inference from the code, not a confirmed trace; the
                // log line below is what confirms it.
                //
                // When `query` is changed from outside, the field is replaced
                // with no composing region, which makes the keyboard drop the
                // word it was composing.
                var field by remember { mutableStateOf(TextFieldValue("")) }
                val shown = if (field.text == query) field else TextFieldValue(query, TextRange(query.length))
                BasicTextField(
                    value = shown,
                    onValueChange = edit@{ edited ->
                        // Space completes a unique verb prefix. A soft
                        // keyboard has no Tab, and Space is the key a
                        // terminal user reaches for anyway.
                        val next = CommandParser.completeOnSpace(query, edited.text)
                        field = if (next == edited.text) edited else TextFieldValue(next, TextRange(next.length))
                        // A selection or composing change alone is not an
                        // edit, and must not clear anything.
                        if (next == query) return@edit
                        query = next
                        // Typing dismisses the manual. It is a reference, not
                        // a mode, and leaving it up while the user works
                        // would hide the app list they are filtering.
                        showManual = false
                        // And any edit clears the last answer. Starting to
                        // type is the user moving on, which is the whole of
                        // what an answer waits for.
                        if (answer != null) {
                            Log.i(
                                CONSOLE_TAG,
                                "answer cleared by an edit ${SystemClock.elapsedRealtime() - answerShownAtMs}ms " +
                                    "after it was shown (field now ${next.length} chars)",
                            )
                        }
                        clearAnswer()
                        lastKeystrokeMs = SystemClock.elapsedRealtime()
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorGreen,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(PhosphorGreen),
                    // A command line, not prose. Autocorrect and capitals can
                    // rewrite a verb on Enter ("rem" is not a dictionary
                    // word), and the correction lands after the action.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = go@{
                            // Every outcome is a finished command or a
                            // full-screen panel, and neither has any use for
                            // the keyboard. It was staying up, which halved
                            // the viewport the manual then rendered into for
                            // no reason.
                            // Cleared before the branch, so the Answered
                            // branch below is the only thing that can put one
                            // back. Clearing inside each other branch would
                            // be seven places to remember instead of one.
                            if (awaitingDeferred) return@go
                            clearAnswer()
                            val outcome = submit()
                            keyboard?.hide()
                            handleOutcome(outcome)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // The usage hint, always drawn.
        //
        // Composed unconditionally because it used to come and go between the
        // prompt and the list. The first keystroke produced a hint, inserted
        // a line and pushed the list down; clearing the query on execute
        // pulled it back up. That is a shift on every command, in a layout
        // whose whole claim is that nothing moves unless the user moved it.
        //
        // The reservation is the line's own height rather than a dp constant.
        // Sizes here are in sp, and this app multiplies the system font scale
        // by its own setting on top, so a number written here would be wrong
        // on the first device that is not at 1.0 twice over. StatRow in
        // LeaseGateScreen declines a fixed column width for the same reason.
        //
        // One line is reserved, not two. The empty string is a reserved line
        // and not copy, so it has nothing to translate.
        Text(
            text = if (commandHint != null) stringResource(R.string.cmd_hint_fmt, commandHint) else "",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = PhosphorDivider,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )

        Spacer(Modifier.height(10.dp))

        // One list, two jobs. The manual is what the user just asked for; a
        // filter is what they are typing.
        //
        // It had a third: an empty prompt listed recent commands. The view is
        // gone and the feature is not. CommandHistory still deduplicates and
        // caps, the store still records every command through onRecordCommand,
        // and nothing renders it. An empty prompt now shows nothing at all,
        // because filteredApps is empty on an empty query, which is the right
        // answer for a terminal: a blank prompt is a blank prompt.
        //
        // imePadding because the window does not resize for the keyboard:
        // setDecorFitsSystemWindows(false) hands that job to the content, and
        // until now nothing in this tree took it, so the list ran to the
        // bottom of the window and the keyboard covered roughly the lower
        // half of it. The filtered app list is the case that makes this a bug
        // rather than a blemish: it only exists while the query is non-empty,
        // which is exactly when the keyboard is up, so the list that only
        // appears with the keyboard was the list the keyboard covered.
        //
        // On this list and not on the whole column. Padding the column would
        // move the prompt and Bit's row, which is the panning behaviour the
        // manifest now declares its way out of. Only the list gives up the
        // space, because only the list has space to give.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                showManual -> {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.launcher_manual_title),
                            hint = stringResource(R.string.launcher_manual_hint),
                        )
                    }
                    items(manualRows, key = { it.verb }) { row ->
                        ManualRow(
                            row = row,
                            onPick = { verb ->
                                // Fills the prompt, never runs. The usage
                                // shape carries argument placeholders, so
                                // only the verb goes in.
                                query = "$verb "
                                showManual = false
                                lastKeystrokeMs = SystemClock.elapsedRealtime()
                            },
                        )
                    }
                }

                else -> items(filteredApps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { launchAndClear(app.packageName) }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = app.label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = PhosphorGreen,
                        )

                        if (app.isTarget) {
                            Text(
                                text = stringResource(R.string.launcher_target_badge),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = PhosphorGreen,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A dim title and one line of explanation. Only the manual uses it now. */
@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = PhosphorGreen,
        )
        Text(
            text = hint,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = PhosphorDivider,
        )
    }
}

/**
 * One manual row: the usage shape, the description, and the reason when the
 * command cannot run.
 *
 * An unavailable row is dimmed rather than hidden. Hiding it would make the
 * manual lie by omission: the command parses, and a user who types it deserves
 * to be told why nothing happened rather than that it does not exist.
 */
@Composable
private fun ManualRow(row: Manual.Row, onPick: (String) -> Unit) {
    val body = if (row.available) PhosphorGreen else PhosphorDim
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.available) { onPick(row.verb) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            text = stringResource(row.usageKey),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = body,
        )
        Text(
            text = stringResource(row.descriptionKey),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (row.available) PhosphorDim else PhosphorDivider,
        )
        val reasonKey = row.reasonKey
        if (reasonKey != null) {
            Text(
                text = stringResource(reasonKey),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorDivider,
            )
        }
    }
}

@Composable
fun TextualWellbeingView(
    /** The pager has settled on this page. Each change re-reads the day. */
    shown: Boolean,
    cycle: CycleReadout,
    nowStamped: () -> StampedInstant,
    onOpenWellbeing: () -> Unit,
) {
    val context = LocalContext.current
    // Null means "not known", never zero. A device without usage access, or
    // one queried before the first event of the day, must render -- rather
    // than a confident 0, which is indistinguishable from a real idle day.
    var screenTimeMs by remember { mutableStateOf<Long?>(null) }
    var unlockCount by remember { mutableStateOf<Int?>(null) }
    var usageRecords by remember { mutableStateOf(emptyList<AppUsageRecord>()) }
    // What the floor and the cap left out, so the page can say so.
    var underFloor by remember { mutableIntStateOf(0) }
    var pastCap by remember { mutableIntStateOf(0) }

    // Bumped on every resume. The launcher is never sent back to the console
    // on home, so a user who left from this page comes back to it with the
    // page still composed, and a query keyed on nothing kept the day as it
    // was when the page was first drawn. That is how Instagram, used after
    // the page was opened, was missing while Settings, used before, was not.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumes++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Re-read on every show of this page and on every resume.
    LaunchedEffect(shown, resumes) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@LaunchedEffect
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        // ------------------------------------------------------ screen time
        // A replay of the raw event stream, not queryAndAggregateUsageStats.
        // See DayUsage for why that call reported a number this page could
        // not defend against Digital Wellbeing's.
        //
        // Off the main thread: a full day of ACTIVITY_RESUMED and
        // ACTIVITY_PAUSED is thousands of events, and this composes on the
        // first frame of the ledger page. The previous call was a single
        // aggregate read and got away with running here.
        val day = withContext(Dispatchers.Default) {
            readDayUsage(
                usm, startOfDay, now,
                exclude = setOf(context.packageName),
                // Unknown reads as not interactive, so an open interval is not
                // run to now on a guess.
                interactiveNow = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false,
            )
        }
        // Every value is assigned on every read, so a refresh can clear a
        // row as well as add one. Null (no grant, failed query) and an empty
        // day both read as unknown, as they did before.
        screenTimeMs = day?.takeIf { it.apps.isNotEmpty() }?.totalMs
        val distribution = day?.distribution(DISTRIBUTION_ROWS, DISTRIBUTION_MIN_MS)
        underFloor = distribution?.underFloor ?: 0
        pastCap = distribution?.pastCap ?: 0
        usageRecords = if (day == null || distribution == null || day.apps.isEmpty()) {
            emptyList()
        } else {
            // The headline is every app. The list below it is the leaders,
            // which is a different question and must not decide the total.
            val pm = context.packageManager
            distribution.rows.map { entry ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(entry.pkg, 0)).toString()
                } catch (e: Exception) {
                    entry.pkg.substringAfterLast('.')
                }
                // A share of the whole day, so five rows reading 19% each do
                // not have to add up. They are five of however many apps
                // there were, and the headline is all of them.
                val pct = ((entry.foregroundMs * 100) / day.totalMs.coerceAtLeast(1)).toInt()
                val filled = (pct / 5).coerceIn(0, 20)
                val empty = (20 - filled).coerceAtLeast(0)
                val bar = "[" + "=".repeat(filled) + " ".repeat(empty) + "] $pct%"
                AppUsageRecord(label, entry.foregroundMs / 60_000L, bar)
            }
        }

        // ---------------------------------------------------------- unlocks
        unlockCount = countUnlocks(usm, startOfDay, now)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.ledger_title),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = PhosphorGreen,
        )

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.ledger_screentime_label),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = PhosphorDim,
                )
                Text(
                    text = run {
                        val unknown = stringResource(R.string.ledger_value_unknown)
                        val mins = screenTimeMs?.let { it / 60_000L }
                        stringResource(
                            R.string.ledger_screentime_fmt,
                            mins?.let { (it / 60).toString() } ?: unknown,
                            mins?.let { (it % 60).toString() } ?: unknown,
                        )
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = PhosphorGreen,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // Unlocks are real. Notifications are not: there is no
                    // NotificationFilterService yet, so there is no source
                    // for that number and it renders as unknown.
                    // TODO: wire the notification count once
                    // NotificationFilterService exists and its Room table is
                    // the source. Do not substitute a proxy count from
                    // anywhere else; a plausible wrong number is worse here
                    // than an honest --.
                    text = stringResource(
                        R.string.ledger_metrics_fmt,
                        unlockCount?.toString() ?: stringResource(R.string.ledger_value_unknown),
                        stringResource(R.string.ledger_value_unknown),
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = PhosphorGreen,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Jitter's own numbers, on a page that until now showed only the
        // system's. Every other figure here comes from UsageStatsManager, so
        // without this line the engine's state was reachable only through a
        // debug screen and one step of a readout behind a retreated Bit.
        val fields = remember(cycle) {
            CycleLine.fields(cycle.deepest, cycle.remainingMs(nowStamped()))
        }
        val penalty = fields.penalty
        Text(
            text = if (penalty == null) {
                stringResource(
                    R.string.ledger_cycle_fmt,
                    fields.cycle,
                    fields.horizon,
                    fields.resets,
                )
            } else {
                stringResource(
                    R.string.ledger_cycle_penalty_fmt,
                    fields.cycle,
                    penalty,
                    fields.horizon,
                    fields.resets,
                )
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = PhosphorGreen,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.ledger_distribution),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = PhosphorDim,
        )

        Spacer(Modifier.height(8.dp))

        // Only when there is truly nothing. A day of apps all under the floor
        // is not "no usage recorded", and saying so blamed the grant for a
        // rule of this page.
        if (usageRecords.isEmpty() && underFloor == 0) {
            Text(
                text = stringResource(R.string.ledger_distribution_empty),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = PhosphorDim,
            )
        }

        usageRecords.forEach { record ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = record.label.uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = PhosphorGreen,
                    )
                    Text(
                        text = stringResource(R.string.ledger_app_minutes_fmt, record.minutes.toString()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = PhosphorGreen,
                    )
                }
                Text(
                    text = record.bar,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = PhosphorDim,
                )
            }
        }

        // What the cap and the floor left out, counted. See DayUsage.distribution.
        if (pastCap > 0) {
            Text(
                text = stringResource(
                    R.string.ledger_distribution_past_cap_fmt,
                    pastCap.toString(),
                    DISTRIBUTION_ROWS.toString(),
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorDim,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        if (underFloor > 0) {
            Text(
                text = stringResource(R.string.ledger_distribution_under_floor_fmt, underFloor.toString()),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorDim,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.ledger_view_details),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = PhosphorGreen,
            modifier = Modifier
                .clickable { onOpenWellbeing() }
                .padding(vertical = 4.dp),
        )
    }
}

/** Rows in the distribution list. The headline is not capped; this is. */
private const val DISTRIBUTION_ROWS = 5

/** A row shorter than this carries no information and costs a real one. */
private const val DISTRIBUTION_MIN_MS = 60_000L

/**
 * Today's foreground milliseconds per package, from the raw event stream.
 *
 * This is the Android half of [DayUsage]: pull `queryEvents`, map the two
 * activity transitions onto [DayUsage.Transition], hand the pairing to the
 * pure function. `UsageEvents.Event` has no public constructor, so the
 * arithmetic lives on the other side of this boundary where it is testable.
 *
 * [exclude] carries the launcher's own package. Jitter is the home screen, so
 * counting it adds every glance at the console to the day.
 *
 * Returns null on a missing grant or a failed query, which renders as
 * unknown. It must never collapse to 0.
 */
private fun readDayUsage(
    usm: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    exclude: Set<String>,
    interactiveNow: Boolean,
): DayUsage.Result? = try {
    // The shared reader and the shared bounding rule, the same as the gate's
    // "today" and the reconciler. See ForegroundIntervals.
    DayUsage.replay(usm.foregroundEvents(startMs, endMs), startMs, endMs, interactiveNow, exclude)
} catch (e: SecurityException) {
    null
} catch (e: Exception) {
    null
}

/**
 * Unlocks today, from `UsageStatsManager.queryEvents`.
 *
 * ## Why not a count of ACTIVITY_RESUMED
 * That counts app launches, which is a different and much larger number. A
 * user who checks one app twenty times in a single unlocked session has
 * unlocked once.
 *
 * ## Why two event types, and why not their sum
 * Unlocking a phone with a secure lock screen emits `SCREEN_INTERACTIVE` and
 * then `KEYGUARD_HIDDEN`. Adding them would double every unlock. A device
 * with no lock screen set emits only `SCREEN_INTERACTIVE`, so keying on
 * `KEYGUARD_HIDDEN` alone would report zero forever.
 *
 * So: count `KEYGUARD_HIDDEN` when the device produces any, and fall back to
 * `SCREEN_INTERACTIVE` when it produces none. The fallback is the honest
 * reading on an unsecured device, where "unlock" and "screen on" really are
 * the same event.
 *
 * Returns null on a missing grant or a failed query. Null renders as unknown;
 * it must never collapse to 0, which would read as a genuinely idle day.
 */
private fun countUnlocks(usm: UsageStatsManager, startMs: Long, endMs: Long): Int? = try {
    val events = usm.queryEvents(startMs, endMs)
    val event = UsageEvents.Event()
    var keyguardHidden = 0
    var screenInteractive = 0
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.KEYGUARD_HIDDEN -> keyguardHidden += 1
            UsageEvents.Event.SCREEN_INTERACTIVE -> screenInteractive += 1
        }
    }
    if (keyguardHidden > 0) keyguardHidden else screenInteractive
} catch (e: SecurityException) {
    null
} catch (e: Exception) {
    null
}

/**
 * Bit, as a draggable glyph on the background.
 *
 * ## No container
 * Raw text on black. The previous version was a bordered card labelled
 * "DRAGGABLE COMPANION" that did not move, which is the worst of both: it
 * claimed a capability it did not have and it broke the zero-border rule to
 * do it.
 *
 * ## Tap versus drag
 * Separated by the platform touch slop rather than by a timer.
 * `detectDragGestures` only begins past slop, so a tap that wobbles a few
 * pixels still reads as a tap, which is what a thumb actually does.
 *
 * ## Momentum and the bezel
 * Release throws the glyph with the velocity it was carrying, then it settles
 * to the nearer vertical edge on a bouncy spring. Snapping to a bezel rather
 * than resting anywhere is what stops Bit sitting in the middle of the app
 * list obscuring it.
 *
 * Rapid taps are counted in a window so five in quick succession can turn Bit
 * away, per the reaction ladder. The count resets once the window lapses,
 * which is why it is compared against [SystemClock.elapsedRealtime] rather
 * than accumulated forever.
 */
@Composable
private fun BitCompanion(
    frame: BitStateMachine.BitFrame,
    onTap: (taps: Int) -> Unit,
    /** Any touch at all, so the host can tell idle from in-use. */
    onInteract: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var bitWidth by remember { mutableIntStateOf(0) }
    var bitHeight by remember { mutableIntStateOf(0) }

    // The width Bit was measured at last time, so a change can be told from a
    // first measurement. Deliberately not a state: nothing renders off it, and
    // making it one would recompose on every docking.
    val lastBitWidth = remember { intArrayOf(0) }

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    val velocityTracker = remember { VelocityTracker() }

    // Bound both axes to the parent, so neither the drag nor the decay can
    // put Bit where Compose will not deliver it touch events. Set as the
    // Animatable's own bounds rather than clamped afterwards: animateDecay
    // stops at a bound, whereas a post-hoc clamp would let it fly outside and
    // come back, and Bit is untouchable for the whole excursion.
    LaunchedEffect(containerWidth, bitWidth) {
        if (BezelSnap.canSnap(containerWidth, bitWidth)) {
            // Read before the bounds change, so the question asked is "was it
            // docked at the old width", not "where did updateBounds leave it".
            val was = offsetX.value
            val from = lastBitWidth[0]
            offsetX.updateBounds(0f, BezelSnap.maxOffset(containerWidth, bitWidth))
            // Bit's slot is narrower while it is retreated, so docking and
            // un-docking both move the right-hand bezel. Follow it, or the
            // slit draws four cells in from the edge on the way down and the
            // face hangs outside the parent on the way back up. See
            // BezelSnap.reSnap for both failures in full.
            val target = BezelSnap.reSnap(was, containerWidth, from, bitWidth)
            if (target != null) {
                // snapTo rather than animateTo. The glyph has just changed
                // character, so there is nothing to animate between, and an
                // animation here would be racing the drag that un-docked Bit
                // in the first place.
                offsetX.snapTo(target)
            }
        }
        lastBitWidth[0] = bitWidth
    }
    LaunchedEffect(containerHeight, bitHeight) {
        if (BezelSnap.canSnap(containerHeight, bitHeight)) {
            offsetY.updateBounds(0f, BezelSnap.maxOffset(containerHeight, bitHeight))
        }
    }

    // This Box is what "bezel" means, and it is not the glass.
    //
    // It fills the console column, which sits 18dp in from each screen edge,
    // so a docked Bit rests 18dp from the physical edge rather than against
    // it. That is deliberate and it is not the horizontal padding merely
    // happening to be there.
    //
    // The glass edge belongs to the system. On gesture navigation the back
    // swipe claims roughly the outer 20dp of each vertical edge, reported
    // through the systemGestures insets. A Bit docked flush would sit inside
    // that, so every attempt to pick it up would race the back gesture and
    // usually lose. Modifier.systemGestureExclusion exists to claim it back,
    // but exclusions are capped per side and OEM behaviour varies, so a flush
    // Bit is a fight rather than a line of code. The 18dp is very nearly
    // exactly the clearance that keeps Bit draggable.
    //
    // Two smaller reasons, in case the first one is ever solved. The slit is
    // about 30dp of ink at 17sp, so at 18dp in it already reads as against
    // the edge at arm's length. And every other element on this screen shares
    // that 18dp, so a flush Bit would introduce a second margin into a layout
    // whose whole look is one column.
    //
    // So: Bit docks to the layout edge, on purpose. If a future change wants
    // it closer to the glass, trim the shared horizontal constant rather than
    // giving this Box a width its siblings do not have. BezelSnap itself
    // needs no change either way; it reads whatever container width it is
    // handed.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BIT_ROW_HEIGHT)
            .onSizeChanged {
                containerWidth = it.width
                containerHeight = it.height
            },
    ) {
        Text(
            // Padded into a fixed slot, of which there are two: faces and
            // readouts get the wide one, the retreated slit gets a narrow one
            // so it can sit flush on the bezel. Bit's snap target is computed
            // from its measured width, so the change between them moves the
            // target; the LaunchedEffect above follows it. BezelSnap carries
            // the scar tissue from that arithmetic going wrong once already.
            text = BitGlyph.padFor(frame.face),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = PhosphorGreen,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .onSizeChanged {
                    bitWidth = it.width
                    bitHeight = it.height
                }
                .pointerInput(frame.ignoresInput) {
                    // While Bit has turned away it genuinely ignores input,
                    // rather than accepting taps and discarding them.
                    if (frame.ignoresInput) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            val now = SystemClock.elapsedRealtime()
                            tapCount =
                                if (now - lastTapMs <= BIT_TAP_WINDOW_MS) tapCount + 1 else 1
                            lastTapMs = now
                            onTap(tapCount)
                        },
                    )
                }
                .pointerInput(frame.ignoresInput, containerWidth, bitWidth) {
                    if (frame.ignoresInput) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            onInteract()
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            // Never snap from a measurement that has not
                            // arrived. A zero width makes the right target
                            // look arithmetically fine and puts Bit one full
                            // width outside the parent, which is the reported
                            // lockup.
                            if (!BezelSnap.canSnap(containerWidth, bitWidth)) {
                                return@detectDragGestures
                            }
                            val velocity = velocityTracker.calculateVelocity()
                            scope.launch {
                                offsetY.animateDecay(
                                    velocity.y,
                                    exponentialDecay(frictionMultiplier = BIT_DECAY_FRICTION),
                                )
                            }
                            scope.launch {
                                offsetX.animateDecay(
                                    velocity.x,
                                    exponentialDecay(frictionMultiplier = BIT_DECAY_FRICTION),
                                )
                                val target =
                                    BezelSnap.snapTargetX(offsetX.value, containerWidth, bitWidth)
                                check(
                                    BezelSnap.isWithinBounds(target, containerWidth, bitWidth),
                                ) {
                                    "bezel target $target outside " +
                                        "[0, ${BezelSnap.maxOffset(containerWidth, bitWidth)}]"
                                }
                                offsetX.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                )
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            // snapTo respects the bounds set above, so a drag
                            // cannot carry Bit out of the parent either.
                            offsetX.snapTo(offsetX.value + dragAmount.x)
                            offsetY.snapTo(offsetY.value + dragAmount.y)
                        }
                    }
                },
        )
        val line = frame.line
        if (line != null) {
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = PhosphorDim,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * The swipe-up app drawer: every launchable app, alphabetical, with a filter.
 *
 * No settings entry here. `[CFG]` in the header is the single path to
 * settings, and a second one in a drawer is how two settings screens get built
 * by accident.
 */
@Composable
private fun AppDrawerOverlay(
    apps: List<LaunchableApp>,
    onLaunchPackage: (String) -> Unit,
    onClose: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter, apps) {
        val trimmed = filter.trim()
        if (trimmed.isEmpty()) apps
        else apps.filter {
            it.label.contains(trimmed, ignoreCase = true) ||
                it.packageName.contains(trimmed, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JitterBackground)
            .padding(horizontal = 18.dp, vertical = 44.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.drawer_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = PhosphorGreen,
            )
            Text(
                text = stringResource(R.string.launcher_chevron_glyph),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = PhosphorGreen,
                modifier = Modifier.clickable { onClose() }.padding(4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.launcher_prompt_symbol),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = PhosphorGreen,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (filter.isEmpty()) {
                    Text(
                        text = stringResource(R.string.drawer_search_placeholder),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorDim,
                    )
                }
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorGreen,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(PhosphorGreen),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (shown.isEmpty()) {
            Text(
                text = stringResource(R.string.drawer_empty),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = PhosphorDim,
            )
        }

        // Same as the console list: the window does not resize for the
        // keyboard, and this filter is only useful while the keyboard is up.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLaunchPackage(app.packageName) }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorGreen,
                    )
                    if (app.isTarget) {
                        Text(
                            text = stringResource(R.string.launcher_target_badge),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = PhosphorDim,
                        )
                    }
                }
            }
        }
    }
}

private const val TAG_LAUNCHER = "Molasses.Launcher"

/**
 * How often the console re-reads the service while it is visible and the
 * service is not working. Not at all otherwise.
 */
private const val SERVICE_OFF_POLL_MS = 1_000L

/**
 * How long each placeholder suggestion holds.
 *
 * Driven off Bit's existing tick rather than a timer of its own, so discovery
 * adds no recomposition the console was not already doing. Five seconds is
 * long enough to read a usage shape and short enough that someone standing at
 * the home screen sees more than one.
 */

/** Taps inside this window count toward the same burst. */
private const val BIT_TAP_WINDOW_MS = 400L

/** Higher is stickier. Tuned so a flick crosses the screen but does not fly. */
private const val BIT_DECAY_FRICTION = 2.2f

private val BIT_ROW_HEIGHT = 44.dp

/**
 * Drag distance that counts as a page-level swipe.
 *
 * Distance rather than velocity, so a slow deliberate pull works as well as a
 * flick. Comfortably above the touch slop, so a tap that wanders does not
 * open the drawer by accident.
 */
private const val SWIPE_THRESHOLD_PX = 140f

/**
 * Digital Wellbeing's package and its top level settings Activity.
 *
 * Named constants rather than literals inside the candidate list, so the pair
 * that has to match the `<queries>` entry is written once and is greppable
 * from the manifest side.
 */
private const val WELLBEING_PACKAGE = "com.google.android.apps.wellbeing"
private const val WELLBEING_SETTINGS_CLASS =
    "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity"

/** One favourite row. The brackets come from the string, not from here. */
@Composable
private fun Favourite(@StringRes labelRes: Int, onClick: () -> Unit) {
    FavouriteText(stringResource(labelRes), onClick)
}

/** A favourite row from text already resolved, for a user-chosen app. */
@Composable
private fun FavouriteText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        color = PhosphorGreen,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * The status line, and the primary anchor of the screen.
 *
 * Larger and brighter than the page tabs above it on purpose: this is the
 * data, they are navigation chrome.
 *
 * ## It does not recompose per second
 * The clock text arrives as a parameter and changes at most once a minute.
 * The charging shimmer is one `rememberInfiniteTransition`, started only
 * while charging and while the screen is on, and stopped entirely otherwise.
 * Nothing here polls.
 */
@Composable
private fun PowerLine(
    percent: Int,
    charging: Boolean,
    timeText: String,
    dateText: String,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenOn by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> screenOn = true
                Lifecycle.Event.ON_STOP -> screenOn = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val animate = charging && screenOn
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "pwr")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = PowerBar.CHARGE_CYCLE_MS.toInt(),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pwrPhase",
        ).value
    } else {
        0f
    }

    val brightIndex = PowerBar.chargingCellIndex(percent, animate, phase)
    // Low battery dims toward the divider step. Never red: that hue is the
    // terminal tier alone, and a red battery would teach the user to read red
    // as "the phone is broken", which is the confusion the shutter's tell
    // exists to prevent.
    val base = if (PowerBar.isLow(percent)) PhosphorDivider else PhosphorGreen

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.launcher_bat_label),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = base,
            )
            val bar = PowerBar.bar(percent, POWER_GLYPHS)
            bar.forEachIndexed { index, glyph ->
                // Index 0 is the opening bracket, so cell n is at n + 1.
                val bright = brightIndex != null && index == brightIndex + 1
                Text(
                    text = glyph.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (bright) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = if (bright) PhosphorGreen else base,
                )
            }
            Text(
                text = stringResource(
                    R.string.launcher_bat_suffix_fmt,
                    percent.coerceIn(0, 100).toString(),
                ),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = base,
            )
        }
        Text(
            text = stringResource(R.string.launcher_clock_fmt, timeText, dateText),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
        )
    }
}

/**
 * Block glyphs, with the ASCII set one edit away.
 *
 * Not a setting: this is a per-device rendering fact, and a user should not
 * have to know what a monospace fallback is. Verify on device and change here.
 */
private val POWER_GLYPHS = PowerBar.Glyphs.BLOCK

/**
 * The block cursor after the prompt.
 *
 * Solid while typing, blinking only once idle. A cursor blinking under the
 * user's own fingers is distracting, and the moment it matters least is
 * exactly the moment it is hardest to ignore.
 *
 * Driven from one infinite transition rather than a recomposition timer, so
 * nothing else on the console recomposes on the blink.
 */
@Composable
private fun PromptCursor(lastKeystrokeMs: Long) {
    var idle by remember { mutableStateOf(true) }

    // Restarts on every keystroke, so the cursor stays solid for as long as
    // typing continues and only settles into a blink after a real pause.
    LaunchedEffect(lastKeystrokeMs) {
        idle = false
        delay(CURSOR_IDLE_AFTER_MS)
        idle = true
    }

    val alpha = if (idle) {
        val transition = rememberInfiniteTransition(label = "cursor")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                // A square wave, not a fade. A terminal cursor is on or off.
                animation = tween(
                    durationMillis = (CURSOR_BLINK_MS / 2).toInt(),
                    easing = { if (it < 0.5f) 0f else 1f },
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        ).value
    } else {
        1f
    }

    Text(
        text = stringResource(R.string.launcher_cursor),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = PhosphorGreen.copy(alpha = alpha),
    )
}

/** Full on-off cycle. */
private const val CURSOR_BLINK_MS = 530L

/** Typing is considered finished after this long with no keystroke. */
private const val CURSOR_IDLE_AFTER_MS = 900L

/**
 * Bit's speech row: a face, a line, and for a prompt two answers.
 *
 * ```
 * (o_o) YOU SCROLLED 27M IN INSTAGRAM.  [DO IT] [NAH]
 * ```
 *
 * ## It is a row, never a window
 * The whole reason console speech was safe to add is that it is not an
 * overlay. No new window, no second entry in the collision guard, and no
 * exposure to the financial suppression set, because by the time this draws
 * the foreground package is Jitter.
 *
 * ## A notice can be swiped away, a prompt cannot
 * A notice has eight seconds and no answer, so a swipe is a courtesy. A
 * prompt persists until one of the two buttons is pressed: a question that
 * could be brushed off by a stray horizontal drag is a question that gets
 * answered by accident.
 */
/**
 * A fired reminder: Bit's face, the text, and [OK] to dismiss it.
 *
 * Not swipeable, unlike a notice. A reminder stays until the user says they
 * have seen it, because it is the thing they asked to be told, and a stray
 * drag must not be how it disappears.
 */
@Composable
private fun ReminderRow(face: String, text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = face,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = PhosphorGreen,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorGreen,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.console_reminder_ok),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = PhosphorGreen,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/**
 * One pending reminder in the bare `$ rem` list. A tap reveals [kill] beside
 * it and a second tap hides it again; [kill] is the only thing that removes
 * the reminder, so a stray tap on the row cannot.
 */
@Composable
private fun PendingReminderRow(text: String, armed: Boolean, onTap: () -> Unit, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
            modifier = Modifier.weight(1f),
        )
        if (armed) {
            Text(
                text = stringResource(R.string.console_reminder_kill),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .clickable(onClick = onKill)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ConsoleSpeechRow(
    face: String,
    text: String,
    prompt: ConsoleLine.Prompt?,
    onAnswer: (confirmed: Boolean) -> Unit,
    onDismissNotice: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (prompt == null) {
                    Modifier.pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                if (kotlin.math.abs(dragged) >= SWIPE_THRESHOLD_PX) {
                                    onDismissNotice()
                                }
                            },
                        ) { _, amount -> dragged += amount }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = face,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = PhosphorGreen,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
            modifier = Modifier.weight(1f),
        )
        if (prompt != null) {
            Text(
                text = stringResource(R.string.console_do_it),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .clickable { onAnswer(true) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Text(
                text = stringResource(R.string.console_nah),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = PhosphorDivider,
                modifier = Modifier
                    .clickable { onAnswer(false) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * What `[DO IT]` runs.
 *
 * Empty, and empty on purpose. Item 4 specifies the delivery mechanism and
 * the two lifetimes; it does not name a prompt or say what its action does,
 * and no producer emits one. Guessing at an effect that arms a lock would be
 * inventing policy in the one place where an accident is unrecoverable.
 *
 * The action is a stable string precisely so this table can grow later
 * without the persisted form changing.
 */
private fun runConsoleAction(line: ConsoleLine) {
    if (line !is ConsoleLine.Prompt) return
    // No actions defined yet. Answering still clears the prompt.
}

/** `adb logcat -s Molasses.Console`: answers shown, cleared and expired. */
private const val CONSOLE_TAG = "Molasses.Console"

/** `adb logcat -s Molasses.Bit`: each change of Bit's resting mood. */
private const val BIT_TAG = "Molasses.Bit"

/** `adb logcat -s Molasses.Reminder`: a pending reminder killed from the list, never its text. */
private const val REMINDER_TAG = "Molasses.Reminder"
