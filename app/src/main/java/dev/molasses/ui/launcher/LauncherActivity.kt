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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.molasses.R
import dev.molasses.core.bit.BitStateMachine
import dev.molasses.core.command.CommandParser
import dev.molasses.core.command.ParseError
import dev.molasses.core.command.ParseResult
import dev.molasses.core.ui.FontScale
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.data.repo.SettingsRepository
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
                            actions = LauncherActions(
                                showLedger = {
                                    scope.launch { pagerState.animateScrollToPage(PAGE_LEDGER) }
                                },
                                openWifiPanel = ::openWifiPanel,
                                openDndSettings = ::openDndSettings,
                            ),
                            onDialer = {
                                startActivity(Intent(Intent.ACTION_DIAL))
                            },
                            onOpenMessaging = ::openMessaging,
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
     * @return false when the device has no such panel, so the caller can say
     *   that rather than reporting a success that did nothing.
     */
    private fun openWifiPanel(): Boolean = runCatching {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        startActivity(Intent(action))
        true
    }.getOrDefault(false)

    private fun openDndSettings(): Boolean = runCatching {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        true
    }.getOrDefault(false)

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
    onOpenNotifInbox: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
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
                        fontSize = 11.sp,
                        fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (pagerState.currentPage == index) PhosphorGreen else PhosphorDim,
                    )
                }
            }

            Text(
                text = stringResource(R.string.launcher_cfg),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = PhosphorGreen,
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
                    onOpenNotifInbox = onOpenNotifInbox,
                    onOpenDrawer = onOpenDrawer,
                    onLaunchPackage = onLaunchPackage,
                    onDialer = onDialer,
                    onOpenMessaging = onOpenMessaging,
                )
                PAGE_LEDGER -> TextualWellbeingView(
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
    onOpenNotifInbox: () -> Unit,
    onOpenDrawer: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(100) }

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
                if (level >= 0 && scale > 0) {
                    batteryPercent = (level * 100) / scale
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
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
    fun submit(): CommandOutcome {
        val text = query.trim()
        if (text.isEmpty()) return CommandOutcome.NotACommand

        return when (val parsed = CommandParser.parse(text)) {
            is ParseResult.Ok -> CommandDispatcher.dispatch(parsed.command, actions)
            is ParseResult.Err -> when (parsed.error) {
                // Not a command at all: fall back to app filtering, which is
                // what a bare app name is.
                is ParseError.UnknownCommand, ParseError.Empty -> CommandOutcome.NotACommand
                // A real command typed wrong. Report it rather than silently
                // trying to launch an app called "block".
                else -> CommandOutcome.Failed(
                    parsed.error.messageRes(),
                    parsed.error.argument(),
                )
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
        Text(
            text = stringResource(R.string.launcher_telemetry_fmt, timeText, dateText, batteryPercent.toString()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
        )

        Spacer(Modifier.height(14.dp))

        BitCompanion(
            frame = BitStateMachine.frame(
                mood = BitStateMachine.Mood.IDLE,
                reaction = reaction,
                reactionAgeMs = reactionAgeMs,
                tickMs = bitTickMs,
            ),
            onTap = { taps ->
                react(
                    when {
                        taps >= 5 -> BitStateMachine.Reaction.TurnedAway
                        taps >= 2 -> BitStateMachine.Reaction.Irritated
                        else -> BitStateMachine.Reaction.Poked
                    },
                )
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

        // Quick Launch Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.launcher_quick_phone),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .clickable { onDialer() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            Text(
                text = stringResource(R.string.launcher_quick_messages),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorGreen,
                modifier = Modifier
                    .clickable { onOpenMessaging() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
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

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.launcher_search_placeholder),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PhosphorDim,
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
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
                                is CommandOutcome.Executed -> {
                                    react(BitStateMachine.Reaction.Confirm(outcome.message(context)))
                                    query = ""
                                }
                                is CommandOutcome.NotWired ->
                                    // Deliberately the FAILED face: nothing
                                    // happened, so a confirmation would lie.
                                    react(BitStateMachine.Reaction.Failed(outcome.message(context)))
                                is CommandOutcome.Failed ->
                                    react(BitStateMachine.Reaction.Failed(outcome.message(context)))
                                CommandOutcome.NotACommand ->
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

        if (commandHint != null) {
            Text(
                text = stringResource(R.string.cmd_hint_fmt, commandHint),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = PhosphorDivider,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
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
fun TextualWellbeingView(onOpenWellbeing: () -> Unit) {
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
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var containerWidth by remember { mutableIntStateOf(0) }
    var bitWidth by remember { mutableIntStateOf(0) }

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    val velocityTracker = remember { VelocityTracker() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BIT_ROW_HEIGHT)
            .onSizeChanged { containerWidth = it.width },
    ) {
        Text(
            text = frame.face,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = PhosphorGreen,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .onSizeChanged { bitWidth = it.width }
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
                .pointerInput(frame.ignoresInput) {
                    if (frame.ignoresInput) return@pointerInput
                    detectDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity()
                            val maxX = (containerWidth - bitWidth).coerceAtLeast(0).toFloat()
                            scope.launch {
                                // Carry the throw, then settle to the nearer
                                // bezel. Two animations rather than one so the
                                // momentum is visible before the snap takes
                                // over.
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
                                val target = if (offsetX.value > maxX / 2f) maxX else 0f
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
