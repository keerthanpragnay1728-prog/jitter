package dev.molasses.ui.launcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.molasses.data.datastore.DEFAULT_TARGETS
import dev.molasses.ui.settings.SettingsActivity
import dev.molasses.ui.theme.JitterBackground
import dev.molasses.ui.theme.MolassesTheme
import dev.molasses.ui.theme.PhosphorDim
import dev.molasses.ui.theme.PhosphorDivider
import dev.molasses.ui.theme.PhosphorGreen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MolassesTheme {
                var showNotifInbox by remember { mutableStateOf(false) }

                BackHandler(enabled = true) {
                    if (showNotifInbox) {
                        showNotifInbox = false
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = JitterBackground,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainLauncherWorkspace(
                            onOpenNotifInbox = { showNotifInbox = true },
                            onOpenSettings = {
                                startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
                            },
                            onLaunchPackage = { pkg ->
                                val intent = packageManager.getLaunchIntentForPackage(pkg)
                                if (intent != null) startActivity(intent)
                            },
                            onDialer = {
                                startActivity(Intent(Intent.ACTION_DIAL))
                            },
                            onOpenMessaging = {
                                val waIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                                if (waIntent != null) {
                                    startActivity(waIntent)
                                } else {
                                    val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_APP_MESSAGING)
                                    }
                                    startActivity(Intent.createChooser(smsIntent, "Messages"))
                                }
                            },
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
                    }
                }
            }
        }
    }
}

@Composable
fun MainLauncherWorkspace(
    onOpenNotifInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
    onOpenWellbeingSettings: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val context = LocalContext.current

    // The shipped default list, not a second copy of it. A literal here
    // drifts the moment the defaults change, and the [TRACKED] badge would
    // then disagree with what the service actually monitors.
    //
    // Still the *defaults* rather than the user's live target list, which
    // lives in CycleStateStore. Reading it needs a ViewModel this screen does
    // not have yet, so a user who has edited their targets sees a stale badge.
    // Cosmetic, and noted in the README rather than fixed here.
    val targetPackages = remember { DEFAULT_TARGETS.toSet() }

    val apps = remember {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val pkg = it.activityInfo.packageName
                LaunchableApp(
                    label = it.loadLabel(pm).toString(),
                    packageName = pkg,
                    isTarget = targetPackages.contains(pkg),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

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
                0 -> TerminalHomeView(
                    apps = apps,
                    onOpenNotifInbox = onOpenNotifInbox,
                    onLaunchPackage = onLaunchPackage,
                    onDialer = onDialer,
                    onOpenMessaging = onOpenMessaging,
                )
                1 -> TextualWellbeingView(
                    onOpenWellbeing = onOpenWellbeingSettings,
                )
            }
        }
    }
}

@Composable
fun TerminalHomeView(
    apps: List<LaunchableApp>,
    onOpenNotifInbox: () -> Unit,
    onLaunchPackage: (String) -> Unit,
    onDialer: () -> Unit,
    onOpenMessaging: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(100) }

    // Draggable Creature Offset
    var creatureOffsetX by remember { mutableFloatStateOf(0f) }
    var creatureOffsetY by remember { mutableFloatStateOf(0f) }

    // Smooth Multi-Frame Blink Animation
    var creatureFace by remember { mutableStateOf("(o_o)") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3400L)
            creatureFace = "( -_- )"
            delay(120L)
            creatureFace = "( o_o )"
            delay(120L)
            creatureFace = "(o_o)"
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.launcher_telemetry_fmt, timeText, dateText, batteryPercent.toString()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = PhosphorDim,
        )

        Spacer(Modifier.height(14.dp))

        // Draggable & Interactive Daemon Creature Card
        Box(
            modifier = Modifier
                .offset { IntOffset(creatureOffsetX.roundToInt(), creatureOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        creatureOffsetX += dragAmount.x
                        creatureOffsetY += dragAmount.y
                    }
                }
                .fillMaxWidth()
                .clickable {
                    creatureFace = "(^o^)"
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = creatureFace,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = PhosphorGreen,
                )

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.launcher_bit_label),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = PhosphorGreen,
                    )
                    Text(
                        text = stringResource(R.string.launcher_bit_hint),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = PhosphorDim,
                    )
                }
            }
        }

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
                            if (filteredApps.isNotEmpty()) {
                                onLaunchPackage(filteredApps.first().packageName)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
