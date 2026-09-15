package dev.molasses.ui.launcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
import dev.molasses.core.bit.HudStep
import dev.molasses.core.command.AppTokenResolver
import dev.molasses.core.command.CommandParser
import dev.molasses.core.command.Manual
import dev.molasses.core.command.ConfirmPrompt
import dev.molasses.core.command.DispatchResult
import dev.molasses.core.command.ParseError
import dev.molasses.core.command.ParseResult
import dev.molasses.core.lock.BedtimeWindow
import dev.molasses.core.lock.LockReason
import dev.molasses.core.lock.LockRegistry
import dev.molasses.core.time.CycleWindow
import dev.molasses.core.time.StampedInstant
import dev.molasses.core.ui.BezelSnap
import dev.molasses.core.ui.CycleLine
import dev.molasses.core.ui.FontScale
import dev.molasses.core.ui.PowerBar
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.repo.CycleReadout
import dev.molasses.data.repo.SettingsRepository
import dev.molasses.monitor.ServiceDiagnostics
import dev.molasses.ui.settings.SettingsActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val isTarget: Boolean,
)

data class FilteredNotification(
    val id: String,
    val sourceApp: String,
    val title: String,
    val message: String,
    val time: String,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val fontScale by settingsRepository.fontScale
                .collectAsState(initial = FontScale.DEFAULT)

            // Newest first and already capped, so the prompt renders it in
            // stored order without sorting or truncating.
            val commandHistory by settingsRepository.commandHistory
                .collectAsState(initial = emptyList())

            // The armed locks. Read by the prompt to predict what a lock
            // command will do, and by the target list to dim what is locked.
            // The store stays authoritative: the extend-only compare happens
            // inside its transform, not against this copy.
            val locks by settingsRepository.locks
                .collectAsState(initial = LockRegistry())

            val targets by settingsRepository.targets
                .collectAsState(initial = emptyList())

            // What Bit's readout reads. The anchor rather than a remaining
            // figure, so the HUD subtracts against a fresh stamp when it
            // renders instead of needing a per-second ticker for something
            // that is on screen five seconds at a time.
            val cycle by settingsRepository.cycleReadout
                .collectAsState(initial = CycleReadout(StampedInstant.UNSET, 0L))

            MolassesTheme(fontScale = fontScale.multiplier) {
                var showNotifInbox by remember { mutableStateOf(false) }
                var showDrawer by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(pageCount = { 2 })
                val scope = rememberCoroutineScope()

                // Per destination, and a no-op only on the console.
                //
                // A launcher that swallows back everywhere is a launcher you
                // cannot get out of. Back has to pop the drawer, the inbox and
                // the ledger; it is inert only on the console, which is home
                // and has nowhere above it to go.
                BackHandler(enabled = true) {
                    when {
                        showDrawer -> showDrawer = false
                        showNotifInbox -> showNotifInbox = false
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
                        MainLauncherWorkspace(
                            appList = installedApps,
                            pagerState = pagerState,
                            onOpenNotifInbox = { showNotifInbox = true },
                            onOpenDrawer = { showDrawer = true },
                            onOpenSettings = {
                                startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
                            },
                            onLaunchPackage = ::launchPackage,
                            history = commandHistory,
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
                            onRecordCommand = { line, confirmation ->
                                scope.launch {
                                    settingsRepository.recordCommand(line, confirmation)
                                }
                            },
                            // Remembered so the prompt can build its
                            // dispatcher once rather than on every keystroke.
                            actions = remember(pagerState) {
                                LauncherActions(
                                    showLedger = {
                                        scope.launch { pagerState.animateScrollToPage(PAGE_LEDGER) }
                                    },
                                    startIntent = ::startIfHandled,
                                    canResolve = ::canResolve,
                                    lockRemainingMs = { pkg ->
                                        locks.remainingMs(pkg, settingsRepository.nowStamped())
                                    },
                                    anyLockArmed = {
                                        locks.active(settingsRepository.nowStamped()).isNotEmpty()
                                    },
                                    targets = { targets },
                                    resolveApp = { token ->
                                        AppTokenResolver.resolve(
                                            token = token,
                                            candidates = installedApps.map {
                                                AppTokenResolver.Candidate(it.packageName, it.label)
                                            },
                                            preferred = targets.toSet(),
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
                                )
                            },
                            onDialer = {
                                startActivity(Intent(Intent.ACTION_DIAL))
                            },
                            onOpenMessaging = ::openMessaging,
                            onLaunchIntent = ::launchIntent,
                            onOpenWellbeingSettings = {
                                try {
                                    val dw = Intent("com.google.android.apps.wellbeing.action.WELLBEING_DASHBOARD")
                                    startActivity(dw)
                                } catch (e: Exception) {
                                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                            },
                        )

                        // Slide-Over Notification Inbox (Minimalist Phone style)
                        AnimatedVisibility(
                            visible = showNotifInbox,
                            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        ) {
                            NotificationInboxOverlay(
                                onClose = { showNotifInbox = false }
                            )
                        }

                        AnimatedVisibility(
                            visible = showDrawer,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        ) {
                            AppDrawerOverlay(
                                apps = installedApps,
                                onLaunchPackage = { pkg ->
                                    showDrawer = false
                                    launchPackage(pkg)
                                },
                                onClose = { showDrawer = false },
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- actions

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(intent)
    }

    /**
     * Launch by action, optionally narrowed by category.
     *
     * Silently does nothing when no app handles it. A launcher that toasts
     * "no calendar installed" every time a favourite is tapped is worse than
     * one where the row simply does not respond, and the row is only ever
     * tapped deliberately.
     */
    private fun launchIntent(action: String, category: String?) {
        val intent = Intent(action).apply { category?.let { addCategory(it) } }
        runCatching { startActivity(intent) }
    }

    private fun openMessaging() {
        val wa = packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
        if (wa != null) {
            startActivity(wa)
            return
        }
        val sms = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
        }
        runCatching { startActivity(sms) }
    }

    /**
     * @return false when nothing handled it, so the caller can say that rather
     *   than reporting a success that did nothing.
     */
    private fun startIfHandled(intent: Intent): Boolean = runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * Whether anything on this device handles [intent].
     *
     * Package visibility on API 30+ means this answers only for actions
     * declared in the manifest's `queries` block. An action that is missing
     * there reads as unhandled on a device that handles it perfectly well, so
     * the two lists are kept in step.
     */
    private fun canResolve(intent: Intent): Boolean =
        packageManager.resolveActivity(intent, 0) != null

    private fun queryLaunchableApps(): List<LaunchableApp> {
        val targets = DEFAULT_TARGETS.toSet()
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
                    isTarget = pkg in targets,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}

/** Pager indices. Named because BackHandler and $ status both reference them. */
const val PAGE_CONSOLE = 0
const val PAGE_LEDGER = 1

@Composable
fun MainLauncherWorkspace(
    appList: List<LaunchableApp>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    actions: LauncherActions,
    history: List<String>,
    onRecordCommand: (String, Boolean) -> Unit,
    cycle: CycleReadout,
    curfewEndMinuteOfDay: Int?,
    nowStamped: () -> StampedInstant,
    onOpenNotifInbox: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
    onLaunchIntent: (String, String?) -> Unit,
    onOpenWellbeingSettings: () -> Unit,
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

        Spacer(Modifier.height(14.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                PAGE_CONSOLE -> TerminalHomeView(
                    apps = apps,
                    actions = actions,
                    history = history,
                    onRecordCommand = onRecordCommand,
                    cycle = cycle,
                    curfewEndMinuteOfDay = curfewEndMinuteOfDay,
                    nowStamped = nowStamped,
                    onOpenNotifInbox = onOpenNotifInbox,
                    onOpenDrawer = onOpenDrawer,
                    onLaunchPackage = onLaunchPackage,
                    onDialer = onDialer,
                    onOpenMessaging = onOpenMessaging,
                    onLaunchIntent = onLaunchIntent,
                )
                PAGE_LEDGER -> TextualWellbeingView(
                    cycle = cycle,
                    nowStamped = nowStamped,
                    onOpenWellbeing = onOpenWellbeingSettings,
                )
            }
        }
    }
}

@Composable
fun TerminalHomeView(
    apps: List<LaunchableApp>,
    actions: LauncherActions,
    history: List<String>,
    /** @param confirmation true for the second Enter on a long lock. */
    onRecordCommand: (String, Boolean) -> Unit,
    cycle: CycleReadout,
    /** Minute of day a bedtime lock lifts, or null when none stands. */
    curfewEndMinuteOfDay: Int?,
    nowStamped: () -> StampedInstant,
    onOpenNotifInbox: () -> Unit,
    onOpenDrawer: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
    onLaunchIntent: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(false) }
    var lastKeystrokeMs by remember { mutableLongStateOf(0L) }

    // Bit's resting face comes from the pure state machine, which owns the
    // blink timing. The tick is the monotonic clock so the phase is
    // reproducible and so a wall-clock change cannot freeze a frame.
    var bitTickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val origin = SystemClock.elapsedRealtime()
        while (true) {
            bitTickMs = SystemClock.elapsedRealtime() - origin
            delay(BIT_FRAME_MS)
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

    // The dispatcher, rebuilt only when the action table changes. Surfaces
    // read live state when asked, so nothing here needs to recompose for the
    // service binding or an app being installed.
    val dispatch = remember(actions) { launcherDispatch(actions) { showManual = true } }

    // A long lock held for a second Enter. Null except in that window.
    var pending by remember { mutableStateOf<ConfirmPrompt.Pending?>(null) }

    // The dim remainder of a unique verb prefix, drawn under the caret.
    val ghost = remember(query) { CommandParser.ghostFor(query) }

    // Availability costs a few binder calls (resolveActivity), so the rows
    // are built once and rebuilt when the manual opens rather than on every
    // keystroke. The manual is the only place staleness would show, and it is
    // fresh every time it is opened.
    val manualRows = remember(dispatch, showManual) {
        Manual.rows(dispatch.registry, dispatch::availabilityOf)
    }
    val suggestable = remember(manualRows) { Manual.suggestable(manualRows) }

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
        if (BitStatus.crossedTerminal(previous, cycle.deepestAppMs)) {
            react(BitStateMachine.Reaction.Glitching)
        }
    }

    // The two zero-interaction tells. Polled rather than pushed, because
    // ServiceDiagnostics is a plain object written from the accessibility
    // callback thread and has no change signal to collect. The poll runs at
    // the frame rate Bit already recomposes at, and only while this console
    // is composed.
    var shutterArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastAbsorbed = ServiceDiagnostics.lastTouchAbsorbedElapsedMs
        while (true) {
            shutterArmed = ServiceDiagnostics.shutterArmed()
            val absorbed = ServiceDiagnostics.lastTouchAbsorbedElapsedMs
            if (absorbed != lastAbsorbed) {
                lastAbsorbed = absorbed
                // Silent. No line, now or ever: the moment Bit narrates a
                // stall the uncanny phase is over, and the dry acknowledgment
                // after nine minutes is a separate thing that stays separate.
                react(BitStateMachine.Reaction.Absorbed)
            }
            delay(BIT_FRAME_MS)
        }
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
    val docked = BitDock.isDocked(
        typing = query.isNotEmpty(),
        msSinceInteraction = SystemClock.elapsedRealtime() - lastBitTouchMs,
    )

    // Derived, not written. This used to be a state assignment in the middle
    // of composition, which Compose treats as a backwards write: it happened
    // to converge because the condition is false afterwards, but it is
    // unsupported and it had never been run. Deriving costs nothing and needs
    // no timer either, because bitTickMs is already ticking for the blink.
    val hudVisibleStep = if (BitHud.isExpired(bitTickMs - hudStartedTick)) {
        HudStep.NONE
    } else {
        hudStep
    }

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
            delay(BIT_FRAME_MS)
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

        // A pending long lock first. The armed line is the canonical echo, so
        // this only fires on the exact text the user was shown.
        when (val decision = ConfirmPrompt.onSubmit(pending, text)) {
            is ConfirmPrompt.Decision.Confirm -> {
                pending = null
                // Passed through rather than skipped, so the rule is visible
                // here and exercised: CommandHistory drops it. Recording the
                // echo would put an armed 30d line in a list the user taps.
                onRecordCommand(text, true)
                return dispatch.dispatch(decision.command, confirmed = true)
            }
            ConfirmPrompt.Decision.Dispatch -> pending = null
        }

        return when (val parsed = CommandParser.parse(text)) {
            is ParseResult.Ok -> {
                // What the user typed, not the canonical form. It comes back
                // out of history the way they wrote it.
                onRecordCommand(text, false)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Up opens the drawer, down opens the shade. The threshold is
                // a drag distance rather than a velocity so a slow deliberate
                // pull works as well as a flick.
                //
                // Drags starting inside the status bar are not intercepted:
                // that strip belongs to the system and this composable never
                // receives those events anyway.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        when {
                            dragged <= -SWIPE_THRESHOLD_PX -> onOpenDrawer()
                            dragged >= SWIPE_THRESHOLD_PX -> onOpenNotifInbox()
                        }
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

        // Resolved once, here, through the one precedence table:
        // glitch > HUD > reaction > mood.
        val display = BitDisplay.resolve(
            // The deepest app, never the sum. The curve is per package.
            mood = BitStateMachine.moodFor(cycle.deepestAppMs),
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
            // The battery reading the power bar is already showing. Below
            // five percent Bit changes identity; at fifteen the bar has
            // already dimmed a step. An escalation, not the same signal
            // twice.
            batteryCritical = PowerBar.isCritical(batteryPercent),
        )

        BitCompanion(
            frame = BitStateMachine.frame(display, reactionAgeMs, bitTickMs),
            onInteract = { lastBitTouchMs = SystemClock.elapsedRealtime() },
            onTap = { taps ->
                // Branch first, then decide whether it counted. Setting the
                // idle clock before the branch un-docked Bit on the tap that
                // opened the readout, so the second tap took the other branch
                // and two thirds of the readout was unreachable.
                val action = BitTap.onTap(docked, hudVisibleStep, taps)
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

        // Pull-Down / Notification Shade Launcher Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenNotifInbox() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.launcher_filter_glyph),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PhosphorGreen,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.launcher_filter_row_title),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PhosphorGreen,
                    )
                    Text(
                        text = stringResource(R.string.filter_status_idle),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = PhosphorDim,
                    )
                }
            }

            Text(
                text = stringResource(R.string.launcher_chevron_glyph),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = PhosphorGreen,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Favourites, one per line, left aligned and ragged right. The
        // brackets are the affordance: in a zero-border layout they are the
        // only thing distinguishing something pressable from something
        // listed, and they match [CFG] and the gate's [DO IT].
        Column(modifier = Modifier.fillMaxWidth()) {
            Favourite(R.string.launcher_fav_phone, onDialer)
            Favourite(R.string.launcher_fav_messages, onOpenMessaging)
            Favourite(R.string.launcher_fav_calendar) { onLaunchIntent(Intent.ACTION_MAIN, "android.intent.category.APP_CALENDAR") }
            Favourite(R.string.launcher_fav_calculator) { onLaunchIntent(Intent.ACTION_MAIN, "android.intent.category.APP_CALCULATOR") }
            Favourite(R.string.launcher_fav_clock) { onLaunchIntent(android.provider.AlarmClock.ACTION_SHOW_ALARMS, null) }
        }

        Spacer(Modifier.height(16.dp))

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
                    // The placeholder rotates through commands that actually
                    // work on this device, driven off Bit's existing tick so
                    // discovery costs no timer of its own.
                    val suggestion = Manual.at(
                        suggestable,
                        (bitTickMs / PLACEHOLDER_CYCLE_MS).toInt(),
                    )
                    Text(
                        text = if (suggestion == null) {
                            stringResource(R.string.launcher_search_placeholder)
                        } else {
                            stringResource(
                                R.string.launcher_placeholder_try,
                                stringResource(suggestion.usageKey),
                            )
                        },
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

                BasicTextField(
                    value = query,
                    onValueChange = { raw ->
                        // Space completes a unique verb prefix. A soft
                        // keyboard has no Tab, and Space is the key a
                        // terminal user reaches for anyway.
                        val next = CommandParser.completeOnSpace(query, raw)
                        query = next
                        // Typing dismisses the manual. It is a reference, not
                        // a mode, and leaving it up while the user works
                        // would hide the app list they are filtering.
                        showManual = false
                        // Any edit cancels a pending confirmation. Leaving it
                        // armed would mean an Enter on a half-typed line runs
                        // something that was confirmed in a different form.
                        pending = ConfirmPrompt.onTextChanged(pending, next)
                        lastKeystrokeMs = SystemClock.elapsedRealtime()
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorGreen,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(PhosphorGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            when (val outcome = submit()) {
                                is DispatchResult.Confirmed -> {
                                    react(BitStateMachine.Reaction.Confirm(outcome.message(context)))
                                    query = ""
                                }
                                // A third face, not the dry one. "Locks are
                                // not enforced yet" and "block what?" are
                                // different information, and showing the same
                                // face for both teaches the user to ignore it.
                                is DispatchResult.Unavailable ->
                                    react(BitStateMachine.Reaction.Unavailable(outcome.message(context)))
                                is DispatchResult.Failed ->
                                    react(BitStateMachine.Reaction.Failed(outcome.message(context)))
                                // Not a reaction: the echo goes into the
                                // prompt and the instruction into the hint
                                // line, so the thing being confirmed stays on
                                // screen instead of expiring after two
                                // seconds like a face would.
                                is DispatchResult.NeedsConfirmation -> {
                                    pending = ConfirmPrompt.arm(outcome.command, outcome.echo)
                                    query = outcome.echo
                                    lastKeystrokeMs = SystemClock.elapsedRealtime()
                                }
                                DispatchResult.NotACommand ->
                                    if (filteredApps.isNotEmpty()) {
                                        onLaunchPackage(filteredApps.first().packageName)
                                        query = ""
                                    }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // One line, two jobs. A pending confirmation outranks the usage hint:
        // the hint is something to glance at, and this is a question.
        val armed = pending
        if (armed != null) {
            Text(
                text = stringResource(R.string.cmd_confirm_line, armed.line),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorGreen,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        } else if (commandHint != null) {
            Text(
                text = stringResource(R.string.cmd_hint_fmt, commandHint),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorDivider,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // One list, three jobs, in priority order. The manual is what the
        // user just asked for; a filter is what they are typing; and an empty
        // prompt is the only moment there is room to show them what they have
        // typed before.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

                query.isEmpty() && history.isNotEmpty() -> {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.launcher_history_title),
                            hint = stringResource(R.string.launcher_history_hint),
                        )
                    }
                    // No key. The store deduplicates before writing, so
                    // these are unique in practice, and a duplicate key is a
                    // crash rather than a glitch.
                    items(history) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = PhosphorDim,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Fills the prompt. A tap must never run a
                                // command: the whole point of a confirmation
                                // gate is that arming takes a deliberate
                                // Enter, and a list you scroll with your
                                // thumb is the opposite of deliberate.
                                .clickable {
                                    query = line
                                    lastKeystrokeMs = SystemClock.elapsedRealtime()
                                }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                        )
                    }
                }

                else -> items(filteredApps, key = { it.packageName }) { app ->
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
                                color = PhosphorGreen,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A dim title and one line of explanation, shared by the manual and history. */
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
fun NotificationInboxOverlay(onClose: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // Empty, and empty on purpose. NotificationFilterService does not exist
    // yet, so nothing is being filtered and there is nothing to show. Six
    // plausible promo notifications were here; they made a screen that does
    // nothing look like a screen that works.
    // TODO: back this with the FilteredNotificationEntity Room table once the
    // listener service lands.
    val notifications = remember { mutableStateListOf<FilteredNotification>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The one surviving fill. This is a full-screen overlay drawn over
            // the console, so it has to be opaque. Everything else floats on
            // the window background.
            .background(JitterBackground)
            .padding(horizontal = 16.dp, vertical = 40.dp),
    ) {
        // Top App Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = PhosphorGreen,
                )
            }
            Text(
                text = stringResource(R.string.notif_shade_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = PhosphorGreen,
                modifier = Modifier.padding(start = 8.dp),
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Tabs: FILTERED NOTIFICATIONS vs SETTINGS
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = JitterBackground,
            contentColor = PhosphorGreen,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = PhosphorGreen,
                )
            },
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        text = stringResource(R.string.notif_tab_filtered),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTab == 0) PhosphorGreen else PhosphorDim,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        text = stringResource(R.string.notif_tab_settings),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTab == 1) PhosphorGreen else PhosphorDim,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
        }

        Spacer(Modifier.height(14.dp))

        if (selectedTab == 0) {
            if (notifications.isEmpty()) {
                Text(
                    text = stringResource(R.string.notif_empty_box),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = PhosphorDim,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(notifications, key = { it.id }) { notif ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = notif.sourceApp,
                                fontSize = 12.sp,
                                color = PhosphorDim,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = notif.time,
                                fontSize = 11.sp,
                                color = PhosphorDim,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = notif.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = PhosphorGreen,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = notif.message,
                            fontSize = 12.sp,
                            color = PhosphorDim,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.HorizontalDivider(color = PhosphorDivider, thickness = 1.dp)
                    }
                }
            }

            // Bottom Clear All Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { notifications.clear() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notif_clear_all),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                )
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.notif_clear_all),
                    tint = PhosphorGreen,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.filter_allowlist_header),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PhosphorGreen,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.filter_allowlist_body),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = PhosphorDim,
                )
                Spacer(Modifier.height(16.dp))

                // No rows. The four app names here were literals, not an
                // allowlist: nothing read them and nothing acted on them.
                // TODO: populate from the filter service's allowlist once it
                // exists, and make the rows togglable then.
                Text(
                    text = stringResource(R.string.filter_allowlist_empty),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = PhosphorDim,
                )
            }
        }
    }
}

@Composable
fun TextualWellbeingView(
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

    LaunchedEffect(Unit) {
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
        try {
            val statsMap = usm.queryAndAggregateUsageStats(startOfDay, now)
            if (!statsMap.isNullOrEmpty()) {
                val valid = statsMap.values
                    .filter { it.totalTimeInForeground > 60_000L }
                    .sortedByDescending { it.totalTimeInForeground }
                    .take(5)

                val sumMillis = valid.sumOf { it.totalTimeInForeground }
                if (sumMillis > 0) {
                    screenTimeMs = sumMillis
                    val allMins = sumMillis / 60_000L
                    val pm = context.packageManager
                    usageRecords = valid.map { stat ->
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                        } catch (e: Exception) {
                            stat.packageName.substringAfterLast('.')
                        }
                        val mins = stat.totalTimeInForeground / 60_000L
                        val pct = ((mins * 100) / allMins.coerceAtLeast(1)).toInt()
                        val filled = (pct / 5).coerceIn(0, 20)
                        val empty = (20 - filled).coerceAtLeast(0)
                        val bar = "[" + "=".repeat(filled) + " ".repeat(empty) + "] $pct%"
                        AppUsageRecord(label, mins, bar)
                    }
                }
            }
        } catch (e: Exception) {
            // Leaves screenTimeMs null, which renders as unknown.
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
                    fields.tier,
                    fields.resets,
                )
            } else {
                stringResource(
                    R.string.ledger_cycle_penalty_fmt,
                    fields.cycle,
                    penalty,
                    fields.tier,
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

        if (usageRecords.isEmpty()) {
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
            offsetX.updateBounds(0f, BezelSnap.maxOffset(containerWidth, bitWidth))
        }
    }
    LaunchedEffect(containerHeight, bitHeight) {
        if (BezelSnap.canSnap(containerHeight, bitHeight)) {
            offsetY.updateBounds(0f, BezelSnap.maxOffset(containerHeight, bitHeight))
        }
    }

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
            // Padded into a fixed slot. Bit's snap target is computed from its
            // measured width, so a glyph two characters narrower than the last
            // one would move the target, which moves Bit while nobody touched
            // it. BezelSnap already carries the scar tissue from that
            // arithmetic going wrong once.
            text = BitGlyph.pad(frame.face),
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

/** Bit's frame interval. 25 fps is well above the 12 fps glitch floor. */
private const val BIT_FRAME_MS = 40L

/**
 * How long each placeholder suggestion holds.
 *
 * Driven off Bit's existing tick rather than a timer of its own, so discovery
 * adds no recomposition the console was not already doing. Five seconds is
 * long enough to read a usage shape and short enough that someone standing at
 * the home screen sees more than one.
 */
private const val PLACEHOLDER_CYCLE_MS = 5_000L

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

/** One favourite row. The brackets come from the string, not from here. */
@Composable
private fun Favourite(@StringRes labelRes: Int, onClick: () -> Unit) {
    Text(
        text = stringResource(labelRes),
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
                text = stringResource(R.string.launcher_pwr_label),
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
                    R.string.launcher_pwr_suffix_fmt,
                    PowerBar.hexCapacity(percent),
                    percent.toString(),
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
