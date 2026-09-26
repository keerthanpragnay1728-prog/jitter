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
    fun `schedule makes the two calls and ReminderArming decides what they mean`() {
        val alarms = repoFile("app/src/main/java/dev/molasses/monitor/ReminderAlarms.kt").readText()
        val schedule = functionBody(alarms, "fun schedule(")
        assertTrue(alarms.contains("fun schedule(context: Context, id: Long, atWallMs: Long): ReminderArming.Armed {"))
        assertTrue(schedule.contains("return ReminderArming.Armed.NOT_ARMED"))
        val arm = schedule.indexOf("ReminderArming.arm(")
        val check = schedule.indexOf("am.canScheduleExactAlarms()", arm)
        val exact = schedule.indexOf("am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP", arm)
        val inexact = schedule.indexOf("am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP", arm)
        assertTrue(arm >= 0 && check > arm && exact > check && inexact > exact)
        assertFalse("no second verdict beside the pure one", schedule.contains("Precision"))
    }

    @Test
    fun `each arming outcome has its own acknowledgement`() {
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        val keys = functionBody(dispatch, "fun remindAckKey(")
        assertTrue(keys.contains("ReminderArming.Armed.EXACT -> R.string.cmd_ans_rem_exact"))
        assertTrue(keys.contains("ReminderArming.Armed.INEXACT -> R.string.cmd_ans_rem_inexact"))
        assertTrue(keys.contains("ReminderArming.Armed.NOT_ARMED -> R.string.cmd_ans_rem_not_armed"))
        assertFalse("exhaustive over the enum, no fallback", keys.contains("else ->"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        val exact = Regex("""name="cmd_ans_rem_exact">([^<]*)<""").find(strings)!!.groupValues[1]
        val inexact = Regex("""name="cmd_ans_rem_inexact">([^<]*)<""").find(strings)!!.groupValues[1]
        val notArmed = Regex("""name="cmd_ans_rem_not_armed">([^<]*)<""").find(strings)!!.groupValues[1]
        assertTrue(exact.contains("EXACT") && !exact.contains("INEXACT"))
        assertTrue(inexact.contains("INEXACT"))
        assertTrue(notArmed.contains("NOT ARMED") && !notArmed.contains("EXACT"))
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        assertTrue(launcher.contains("done(RemindOutcome.Saved(dueWallMs = added.due.wallMs, armed = armed))"))
    }

    @Test
    fun `the acknowledgement is a held answer, delivered after the write`() {
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        val branch = dispatch.substring(dispatch.indexOf("is Command.Rem -> DispatchResult.Deferred"))
            .substringBefore("is Command.Days ->")
        assertTrue(branch.contains("DispatchResult.Answered("))
        assertTrue(branch.contains("ackKey = remindAckKey(outcome.armed)"))
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
    fun `one soft tone, awaited, well inside the receiver window`() {
        val src = repoFile("app/src/main/java/dev/molasses/monitor/ReminderChime.kt").readText()
        assertTrue(src.contains("suspend fun play(context: Context)"))
        val tone = functionBody(src, "private suspend fun tone(")
        assertTrue(Regex("""generator\.startTone\(""").findAll(tone).count() == 1)
        assertTrue(tone.contains("generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS.toInt())"))
        assertFalse("BEEP2 is two beeps", tone.contains("TONE_PROP_BEEP2"))
        assertTrue(tone.contains("ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)"))
        assertTrue(src.contains("const val VOLUME = 50"))
        assertTrue(tone.indexOf("generator.release()") > tone.indexOf("delay(TOTAL_MS)"))
        val vibrate = functionBody(src, "private suspend fun vibrate(")
        assertTrue(vibrate.contains("VibrationEffect.createOneShot(TONE_MS"))
        assertFalse("once, not a waveform", vibrate.contains("createWaveform"))
        assertTrue(vibrate.contains("delay(TOTAL_MS)"))
        // 150 + 100 = 250 ms. The shortest goAsync window is 10 s.
        assertTrue(src.contains("const val TONE_MS = 150L") && src.contains("const val TAIL_MS = 100L"))
        assertTrue(src.contains("const val TOTAL_MS = TONE_MS + TAIL_MS"))
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

    @Test
    fun `the cap is the store's verdict, returned to the caller that reports it`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        val add = functionBody(store, "suspend fun addReminder(")
        assertTrue(store.contains("suspend fun addReminder(text: String, due: StampedInstant): ReminderBook.Added {"))
        val txn = add.indexOf("store.updateData { state ->")
        assertTrue(txn >= 0 && add.indexOf("ReminderBook.add(", txn) > txn && add.indexOf("verdict = result", txn) > txn)
        assertTrue(add.contains("return verdict"))
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        assertFalse("no check on a stale snapshot", launcher.contains("reminders.size >= ReminderBook.MAX"))
        val remind = launcher.substring(launcher.indexOf("remind = { whenSpec, text, done ->")).substringBefore("onDialer = {")
        assertTrue(remind.contains("when (val verdict = settingsRepository.addReminder(text, due))"))
        assertTrue(remind.contains("ReminderBook.Added.Full -> done(RemindOutcome.Full)"))
        assertTrue(remind.contains("is ReminderBook.Added.Ok ->"))
    }
}
