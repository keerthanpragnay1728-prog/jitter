package dev.molasses.core.remind

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `$ rem`'s Android half, read as text: nothing here compiles it.
 */
class ReminderWiringTest {

    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml").readText() }

    @Test
    fun `exact when allowed, inexact otherwise, and the caller is told which`() {
        val alarms = repoFile("app/src/main/java/dev/molasses/monitor/ReminderAlarms.kt").readText()
        val schedule = functionBody(alarms, "fun schedule(")
        val check = schedule.indexOf("am.canScheduleExactAlarms()")
        val exact = schedule.indexOf("am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP")
        val inexact = schedule.indexOf("am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP")
        assertTrue(check in 0 until exact && exact < inexact)
        assertTrue(schedule.contains("return Precision.EXACT"))
        assertTrue(schedule.contains("Precision.INEXACT"))
    }

    @Test
    fun `the acknowledgement is a held answer, delivered after the write`() {
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        val branch = dispatch.substring(dispatch.indexOf("is Command.Rem -> DispatchResult.Deferred"))
            .substringBefore("is Command.Days ->")
        assertTrue(branch.contains("DispatchResult.Answered("))
        assertTrue(branch.contains("R.string.cmd_ans_rem_exact else R.string.cmd_ans_rem_inexact"))
        assertFalse("not a fading reaction", branch.contains("DispatchResult.Confirmed("))
    }

    @Test
    fun `the receiver is declared, not exported, and takes the boot broadcast`() {
        val receiver = Regex("""<receiver[\s\S]*?</receiver>""").find(manifest)?.value ?: error("no receiver")
        assertTrue(receiver.contains("android:name=\".monitor.ReminderReceiver\""))
        assertTrue(receiver.contains("android:exported=\"false\""))
        assertTrue(receiver.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
    }

    @Test
    fun `reminders are rescheduled on boot and on service connect`() {
        val receiver = repoFile("app/src/main/java/dev/molasses/monitor/ReminderReceiver.kt").readText()
        assertTrue(receiver.contains("Intent.ACTION_BOOT_COMPLETED ->"))
        assertTrue(receiver.contains("ReminderAlarms.rescheduleAll("))
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        assertTrue(functionBody(service, "override fun onServiceConnected()").contains("ReminderAlarms.rescheduleAll("))
    }

    @Test
    fun `the tone follows the ringer`() {
        val chime = functionBody(repoFile("app/src/main/java/dev/molasses/monitor/ReminderChime.kt").readText(), "fun play(")
        assertTrue(chime.contains("AudioManager.RINGER_MODE_SILENT -> Unit"))
        assertTrue(chime.contains("AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)"))
        assertTrue(chime.contains("else -> tone()"))
    }

    @Test
    fun `the chime plays twice, awaited, well inside the receiver window`() {
        val src = repoFile("app/src/main/java/dev/molasses/monitor/ReminderChime.kt").readText()
        assertTrue(src.contains("suspend fun play(context: Context)"))
        val tone = functionBody(src, "private suspend fun tone(")
        assertTrue(Regex("""generator\.startTone\(""").findAll(tone).count() == 2)
        assertTrue(tone.indexOf("generator.release()") > tone.lastIndexOf("delay("))
        val vibrate = functionBody(src, "private suspend fun vibrate(")
        assertTrue(vibrate.contains("longArrayOf(0L, PULSE_MS, GAP_MS, PULSE_MS)"))
        assertTrue(vibrate.contains("createWaveform(pattern, -1)"))
        assertTrue(vibrate.contains("delay(TOTAL_MS)"))
        // 300 + 200 + 300 + 100. The shortest goAsync window is 10 s.
        assertTrue(src.contains("const val TOTAL_MS = PULSE_MS + GAP_MS + PULSE_MS + TAIL_MS"))
        assertTrue(src.contains("const val PULSE_MS = 300L") && src.contains("const val GAP_MS = 200L") && src.contains("const val TAIL_MS = 100L"))
    }

    @Test
    fun `the receiver finishes its PendingResult after playback and on every failure`() {
        val body = functionBody(repoFile("app/src/main/java/dev/molasses/monitor/ReminderReceiver.kt").readText(), "override fun onReceive(")
        val async = body.indexOf("val pending = goAsync()")
        val work = body.indexOf("ReminderAlarms.fire(", async)
        val catch = body.indexOf("catch (e: Exception)", work)
        val finish = body.indexOf("pending.finish()", catch)
        assertTrue(async >= 0 && work > async && catch > work && finish > catch)
        assertTrue(body.substring(catch, finish).contains("} finally {"))
        val alarms = repoFile("app/src/main/java/dev/molasses/monitor/ReminderAlarms.kt").readText()
        assertTrue(functionBody(alarms, "suspend fun fire(").contains("ReminderChime.play(context)"))
        val reschedule = functionBody(alarms, "suspend fun rescheduleAll(")
        assertTrue("one chime for a backlog", Regex("""ReminderChime\.play\(""").findAll(reschedule).count() == 1)
        assertTrue(reschedule.indexOf("if (firedAny) ReminderChime.play(context)") > reschedule.indexOf("for (step in"))
    }

    @Test
    fun `the console row is its own queue, outside the speech budget, until dismissed`() {
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        val start = launcher.indexOf("ReminderBook.toShow(reminders).firstOrNull()")
        assertTrue(start >= 0)
        val block = launcher.substring(start, launcher.indexOf("val spoken = display as? BitDisplay.Spoken", start))
        assertFalse("reminders must not pass through the speech budget", block.contains("ConsoleSpeech"))
        assertFalse(block.contains("onDeliverConsoleLine"))
        assertTrue(block.contains("onDismiss = { onDismissReminder(reminder.id) }"))
        val row = functionBody(launcher, "private fun ReminderRow(")
        assertFalse("a reminder is not swiped away like a notice", row.contains("detectHorizontalDragGestures"))
    }

    @Test
    fun `a reminder is removed on dismiss and never on fire`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        assertTrue(functionBody(store, "suspend fun markReminderFired(").contains("ReminderBook.fired("))
        assertTrue(functionBody(store, "suspend fun dismissReminder(").contains("ReminderBook.dismissed("))
        assertTrue(functionBody(store, "suspend fun addReminder(").contains("ReminderBook.Added.Full -> state"))
    }
}
