package dev.molasses.core.remind

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
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
        val check = schedule.indexOf("am.canScheduleExactAlarms()")
        val arm = schedule.indexOf("ReminderArming.arm(")
        val exact = schedule.indexOf("am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP", arm)
        val inexact = schedule.indexOf("am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP", arm)
        assertTrue(check >= 0 && arm > check && exact > arm && inexact > exact)
        assertTrue(schedule.contains("exactAllowed = exactAllowed,"))
        val log = schedule.indexOf("Log.i(TAG, \"reminder \$id armed=\$armed")
        assertTrue("the verdict is logged before it is returned", log > inexact && log < schedule.indexOf("return armed"))
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
        assertTrue(chime.contains("else -> sound(context, am)"))
    }

    @Test
    fun `the fallback plays the default notification sound, awaited through isPlaying, capped, and stopped on every path`() {
        val src = repoFile("app/src/main/java/dev/molasses/monitor/ReminderChime.kt").readText()
        assertTrue(src.contains("suspend fun play(context: Context)"))
        // Imports, not text: the class doc names both to say why they went.
        assertFalse("silent on hardware", src.contains("import android.media.ToneGenerator"))
        assertFalse("silent on hardware", src.contains("import android.media.AudioTrack"))
        val sound = functionBody(src, "private suspend fun playRingtone(")
        assertTrue(sound.contains("RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)"))
        assertTrue(sound.contains("ringtone.audioAttributes = notificationEvent()"))
        assertTrue(functionBody(src, "private fun notificationEvent()").contains(".setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)"))
        val attrs = sound.indexOf("ringtone.audioAttributes =")
        val play = sound.indexOf("ringtone.play()")
        val poll = sound.indexOf("ChimeWait.keepWaiting(elapsed, playing, everPlayed)")
        val stop = sound.indexOf("runCatching { ringtone.stop() }")
        assertTrue("attributes before play, poll after, stop last", attrs in 0 until play && play < poll && poll < stop)
        assertTrue(sound.substring(poll, stop).contains("} finally {"))
        assertTrue(sound.contains("chime: ringtone uri=") && sound.contains("chime: play() called, isPlaying="))
        assertTrue(functionBody(src, "suspend fun play(").contains("else -> sound(context, am)"))
        val vibrate = functionBody(src, "private suspend fun vibrate(")
        assertTrue(vibrate.contains("VibrationEffect.createOneShot(VIBRATE_MS"))
        assertFalse("once, not a waveform", vibrate.contains("createWaveform"))
    }

    @Test
    fun `the bundled chime plays first, through MediaPlayer, and falls back to the ringtone on any failure`() {
        val src = repoFile("app/src/main/java/dev/molasses/monitor/ReminderChime.kt").readText()
        val sound = functionBody(src, "private suspend fun sound(")
        assertTrue(sound.indexOf("if (playRaw(context)) return") in 0 until sound.indexOf("playRingtone(context)"))
        val raw = functionBody(src, "private suspend fun playRaw(")
        val attrs = raw.indexOf("player.setAudioAttributes(notificationEvent())")
        val source = raw.indexOf("openRawResourceFd(R.raw.jitter_chime)")
        val prepare = raw.indexOf("player.prepare()")
        val start = raw.indexOf("player.start()")
        val wait = raw.indexOf("withTimeoutOrNull(ChimeWait.CAP_MS) { finished.await() }")
        val release = raw.indexOf("player.release()")
        assertTrue(attrs in 0 until source && source < prepare && prepare < start && start < wait && wait < release)
        assertTrue("released on every path", raw.substring(wait, release).contains("} finally {"))
        assertTrue(raw.contains("player.setOnCompletionListener { finished.complete(true) }"))
        assertTrue("a playback error falls back", raw.contains("finished.complete(false)") && raw.contains("false -> false"))
        assertTrue(raw.contains("} catch (e: Exception) {") && raw.contains("return false"))
        assertTrue(raw.contains("chime: path=raw prepared in") && raw.contains("completed after") && raw.contains("cap reached after"))
    }

    @Test
    fun `nothing references the deleted PCM generator`() {
        val main = java.io.File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val hits = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("ChimeWave") }.toList()
        assertTrue("$hits", hits.isEmpty())
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
        val remind = launcher.substring(launcher.indexOf("remind = remind@{ whenSpec, text, done ->")).substringBefore("onDialer = {")
        assertTrue(remind.contains("when (val verdict = settingsRepository.addReminder(text, due))"))
        assertTrue(remind.contains("ReminderBook.Added.Full -> done(RemindOutcome.Full)"))
        assertTrue(remind.contains("is ReminderBook.Added.Ok ->"))
    }

    @Test
    fun `a bare rem is a held list of pending reminders, or NO PENDING REMINDERS`() {
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        val branch = dispatch.substring(dispatch.indexOf("Command.RemList -> {")).substringBefore("is Command.Days ->")
        assertTrue(branch.contains("val all = actions.pendingReminders()") && branch.contains("ReminderBook.pending(all)"))
        assertTrue(branch.contains("DispatchResult.Answered(R.string.cmd_ans_rem_none)"))
        assertTrue(branch.contains("ackKey = R.string.cmd_ans_rem_list"))
        assertTrue("rows, not text", branch.contains("reminderIds = pending.map { it.id }"))
        assertFalse("held, not a fading reaction", branch.contains("DispatchResult.Confirmed("))
        assertFalse("pending only", branch.contains("toShow("))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"cmd_ans_rem_none\">NO PENDING REMINDERS</string>"))
        // The usage line keeps its two argument shapes: it also drives the
        // typing ghost, which splits it on spaces, so an optional bracket
        // around both would leave a dangling "<text>]". The row's
        // description names the bare form instead.
        assertTrue(Regex("""name="cmd_desc_rem">[^<]*A bare rem lists the next five pending""").containsMatchIn(strings))
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        assertTrue(launcher.contains("pendingReminders = { reminders },"))
    }

    @Test
    fun `the bare rem path is logged at the parse, the read and the answer, never with the text`() {
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        val submit = functionBody(launcher, "fun submit(): DispatchResult {")
        assertTrue(submit.contains("submit: parsed verb=\${CommandRegistry.verbOf(parsed.command)}"))
        assertTrue(submit.contains("if (parsed.command == Command.RemList) \" (pending list)\""))
        assertFalse("never the typed text", Regex("""Log\.i\([^)]*\btext\b""").containsMatchIn(submit))
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        val branch = dispatch.substring(dispatch.indexOf("Command.RemList -> {")).substringBefore("is Command.Days ->")
        assertTrue(branch.contains("rem list: read \${all.size} reminders"))
        val answered = launcher.substring(launcher.indexOf("is DispatchResult.Answered -> {")).substringBefore("is DispatchResult.Unavailable")
        assertTrue(answered.contains("answer shown:"))
    }

    // ------------------------------------------------------------ kill

    private val launcherSrc by lazy { repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText() }

    @Test
    fun `the list is drawn as live rows, and a tap reveals kill`() {
        val rows = launcherSrc.substring(launcherSrc.indexOf("if (spoken is BitDisplay.Answer && answerReminderIds.isNotEmpty()) {"))
            .substringBefore("Spacer(Modifier.height(8.dp))")
        assertTrue("from the live list", rows.contains("ReminderBook.pending(reminders).filter { it.id in answerReminderIds }"))
        assertTrue(rows.contains("onTap = { killArmedId = if (killArmedId == pending.id) null else pending.id }"))
        assertTrue(rows.contains("onKillReminder(pending.id)"))
        val row = functionBody(launcherSrc, "private fun PendingReminderRow(")
        val armed = row.indexOf("if (armed) {")
        assertTrue("kill only once revealed", armed >= 0 && row.indexOf("R.string.console_reminder_kill") > armed)
        assertTrue(row.contains(".clickable(onClick = onKill)"))
    }

    @Test
    fun `a kill removes in one transaction, then cancels the alarm, and logs no text`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        val kill = functionBody(store, "suspend fun killReminder(")
        assertEquals(1, Regex("""store\.updateData""").findAll(kill).count())
        assertTrue(kill.contains("ReminderBook.killed("))
        val host = launcherSrc.substring(launcherSrc.indexOf("onKillReminder = { id ->")).substringBefore("// Remembered so")
        val removed = host.indexOf("settingsRepository.killReminder(id)")
        val cancel = host.indexOf("ReminderAlarms.cancel(this@LauncherActivity, id)")
        assertTrue("store first, then the alarm", removed in 0 until cancel)
        assertTrue("only a removed one is cancelled", host.contains("removed && ReminderAlarms.cancel("))
        assertTrue(host.contains("kill: reminder \$id removed=\$removed alarmCancelled=\$cancelled"))
        assertFalse("never the text", host.contains(".text"))
        val alarms = repoFile("app/src/main/java/dev/molasses/monitor/ReminderAlarms.kt").readText()
        val cancelBody = functionBody(alarms, "fun cancel(context: Context, id: Long)")
        assertTrue(cancelBody.contains("am.cancel(intent)") && cancelBody.contains("pendingIntent(context, id)"))
    }
}
