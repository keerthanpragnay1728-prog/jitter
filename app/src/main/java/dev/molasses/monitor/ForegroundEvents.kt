package dev.molasses.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import dev.molasses.core.time.ForegroundIntervals

/**
 * Every event the foreground bounding rule reads, from [startMs] to [endMs],
 * for every package. The one mapping from `UsageEvents` types, shared by the
 * ledger, the gate's "today" and the reconciler, so the three cannot read
 * different streams. See [ForegroundIntervals] for what each kind does.
 *
 * ACTIVITY_STOPPED is read as well as ACTIVITY_PAUSED. A device logged an
 * app's resume followed by a stop with no pause, and an interval read by its
 * pause alone stayed open until the moment of reading. Each carries its
 * activity's class name, because a stop belongs to one screen and not the
 * whole app: see [ForegroundIntervals].
 *
 * Throws whatever `queryEvents` throws; each caller has its own answer to a
 * failed read, and none of them is "nothing happened".
 */
fun UsageStatsManager.foregroundEvents(startMs: Long, endMs: Long): List<ForegroundIntervals.Event> {
    val events = queryEvents(startMs, endMs)
    val event = UsageEvents.Event()
    val out = mutableListOf<ForegroundIntervals.Event>()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val kind = when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundIntervals.Kind.RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundIntervals.Kind.PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundIntervals.Kind.STOPPED
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundIntervals.Kind.SCREEN_OFF
            UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundIntervals.Kind.KEYGUARD_SHOWN
            UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundIntervals.Kind.SHUTDOWN
            else -> null
        } ?: continue
        val activity = kind == ForegroundIntervals.Kind.RESUMED ||
            kind == ForegroundIntervals.Kind.PAUSED ||
            kind == ForegroundIntervals.Kind.STOPPED
        val pkg = if (activity) event.packageName?.takeIf { it.isNotEmpty() } ?: continue else ""
        // The class keys each activity, so one screen's stop does not close
        // the next screen of the same app. Missing, it reads by package.
        val cls = if (activity) event.className.orEmpty() else ""
        out += ForegroundIntervals.Event(kind, event.timeStamp, pkg, cls)
    }
    return out
}
