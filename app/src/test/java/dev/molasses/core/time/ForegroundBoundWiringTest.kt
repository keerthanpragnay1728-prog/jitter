package dev.molasses.core.time

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ledger, the gate's "today" and the reconciler read one stream through
 * one bounding rule. Read as text: the readers compile nowhere here.
 */
class ForegroundBoundWiringTest {

    private fun main(path: String) = repoFile("app/src/main/java/dev/molasses/$path").readText()
    private val reader by lazy { main("monitor/ForegroundEvents.kt") }
    private val reconciler by lazy { main("monitor/ForegroundReconciler.kt") }
    private val service by lazy { main("monitor/MolassesAccessibilityService.kt") }
    private val launcher by lazy { main("ui/launcher/LauncherActivity.kt") }

    private fun code(text: String) = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `one reader maps the event types, with the class of each activity`() {
        val fn = functionBody(reader, "fun UsageStatsManager.foregroundEvents(")
        assertTrue(fn.contains("UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundIntervals.Kind.RESUMED"))
        assertTrue(fn.contains("UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundIntervals.Kind.PAUSED"))
        assertTrue(fn.contains("UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundIntervals.Kind.STOPPED"))
        assertTrue("each activity event carries its class", fn.contains("val cls = if (activity) event.className.orEmpty() else \"\""))
        assertTrue(fn.contains("ForegroundIntervals.Event(kind, event.timeStamp, pkg, cls)"))
        assertTrue(fn.contains("UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundIntervals.Kind.SCREEN_OFF"))
        assertTrue(fn.contains("UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundIntervals.Kind.KEYGUARD_SHOWN"))
        assertTrue(fn.contains("UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundIntervals.Kind.SHUTDOWN"))
        // No caller pairs events its own way any more.
        val main = File(repoRoot(), "app/src/main/java")
        val mappers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { code(it.readText()).contains("Event.ACTIVITY_PAUSED") }.map { it.name }.toList()
        assertEquals(listOf("ForegroundEvents.kt"), mappers)
    }

    @Test
    fun `all three callers read every package through it, with the screen state`() {
        val day = functionBody(service, "private fun dayUsageFor(")
        assertTrue(day.contains("val events = usm.foregroundEvents(startOfDay, endMs)"))
        assertTrue(day.contains("DayUsage.replay(events, startOfDay, endMs, interactiveNow = isInteractive()).entry(pkg)"))
        assertFalse("every package's events, not only this one's", day.contains("event.packageName != pkg"))
        val ledger = functionBody(launcher, "private fun readDayUsage(")
        assertTrue(ledger.contains("DayUsage.replay(usm.foregroundEvents(startMs, endMs), startMs, endMs, interactiveNow, exclude)"))
        assertTrue(launcher.contains("interactiveNow = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false,"))
        val query = functionBody(reconciler, "private fun queryEvents(")
        assertTrue(query.contains("manager.foregroundEvents(beginWallMs, endWallMs)"))
        assertFalse("not filtered to targets: other apps bound them", query.contains("targets"))
    }

    @Test
    fun `the reconciler credits nothing when the stream cannot be read`() {
        val query = functionBody(reconciler, "private fun queryEvents(")
        assertEquals("both failures are null, not an empty stream", 2, Regex("""\n\s+null\n""").findAll(query).count())
        val reconcile = functionBody(reconciler, "suspend fun reconcile()")
        val read = reconcile.indexOf("val events = queryEvents(windowStart, nowWall)")
        val guard = reconcile.indexOf("if (events == null) {")
        val replay = reconcile.indexOf("replay = ForegroundReplay.replay(")
        assertTrue(read in 0 until guard && guard < replay)
        assertTrue(reconcile.contains("interactiveNow = isInteractive(),"))
        assertTrue(functionBody(reconciler, "private fun isInteractive()").contains("?.isInteractive ?: false"))
    }

    @Test
    fun `both replays pair through the shared bound, each naming its orphan rule`() {
        val day = main("core/stats/DayUsage.kt")
        val replay = main("core/time/ForegroundReplay.kt")
        assertTrue(functionBody(day, "fun replay(").contains("orphan = ForegroundIntervals.Orphan.CREDIT_FROM_WINDOW_START,"))
        assertTrue(functionBody(replay, "fun replay(").contains("orphan = ForegroundIntervals.Orphan.DROP,"))
        for (text in listOf(day, replay)) {
            assertTrue(text.contains("ForegroundIntervals.bound("))
            assertFalse("no pairing of its own", code(text).contains("openSince"))
        }
    }
}
