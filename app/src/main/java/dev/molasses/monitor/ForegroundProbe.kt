package dev.molasses.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * "Which package is in front right now?", without reading window content.
 *
 * The obvious route -- `AccessibilityWindowInfo` -- does not work:
 * [android.view.accessibility.AccessibilityWindowInfo] carries no package
 * name, and the only way to get one is `window.root.packageName`, which is a
 * content read and is exactly what `canRetrieveWindowContent="false"` gives
 * up. So the exit watchdog uses `UsageStatsManager` instead, which needs only
 * the `PACKAGE_USAGE_STATS` grant the app already requires for reconciliation.
 *
 * Each call is an IPC and a small scan, so it must not run on the
 * accessibility callback thread -- the watchdog calls it from a coroutine on
 * `Dispatchers.IO` every 2 s while a target app is foreground, and not at all
 * otherwise.
 */
class ForegroundProbe(context: Context) {

    private val usage: UsageStatsManager? =
        context.applicationContext.getSystemService(UsageStatsManager::class.java)

    /**
     * The most recent `ACTIVITY_RESUMED` in the last [lookbackMs], or null
     * when nothing is known. Null means "no information", never "nothing is
     * in front" -- callers must treat it as no-change, or a missing grant
     * would look like the user leaving every target app.
     */
    fun currentForegroundPackage(lookbackMs: Long = DEFAULT_LOOKBACK_MS): String? {
        val manager = usage ?: return null
        val now = System.currentTimeMillis()
        return try {
            val events = manager.queryEvents(now - lookbackMs, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTs = Long.MIN_VALUE
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                if (event.timeStamp >= latestTs) {
                    latestTs = event.timeStamp
                    latestPkg = event.packageName
                }
            }
            latestPkg
        } catch (e: SecurityException) {
            Log.w(TAG, "no usage-stats access; exit watchdog degraded", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "Molasses.Probe"

        /**
         * Long enough to survive a couple of missed polls, short enough that
         * the scan stays cheap.
         */
        const val DEFAULT_LOOKBACK_MS = 30_000L
    }
}
