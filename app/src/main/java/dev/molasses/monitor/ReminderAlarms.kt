package dev.molasses.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.time.StampedInstant
import dev.molasses.data.datastore.CycleStateStore

/**
 * Scheduling for `$ rem`, on AlarmManager.
 *
 * ## setAndAllowWhileIdle, and what that costs
 * No SCHEDULE_EXACT_ALARM and no USE_EXACT_ALARM: exact delivery is a
 * separate decision that needs one of those permissions, and this app does
 * not ask for it. So the alarm is inexact. With the screen on it is normally
 * close to its time. With the device idle, Android batches it: allow-while-
 * idle alarms from one app fire at most about once every nine minutes, and
 * on Android 12 and later the platform documents inexact alarms as delivered
 * within an hour of their time. A reminder can therefore arrive up to about
 * an hour late with the screen off, more if the system has put the app in a
 * restricted standby bucket. The README and the manual row say so.
 *
 * ## The wall clock, deliberately
 * RTC_WAKEUP rather than an elapsed alarm, because an elapsed alarm is gone
 * after a reboot and [rescheduleAll] is exactly the path that re-arms after
 * one. On the same boot, `ReminderBook.reschedule` derives the wall time from
 * the remaining monotonic time, so a clock moved since the reminder was set
 * does not move when it fires.
 */
object ReminderAlarms {

    const val ACTION_FIRE = "dev.molasses.action.REMINDER_FIRE"
    const val EXTRA_ID = "dev.molasses.extra.REMINDER_ID"
    private const val TAG = "Molasses.Reminder"

    fun schedule(context: Context, id: Long, atWallMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, pendingIntent(context, id))
        }.onFailure { Log.w(TAG, "could not schedule reminder $id", it) }
    }

    /**
     * Re-arm every unfired reminder, and fire the ones already due, marked
     * late. Run on BOOT_COMPLETED and on every service connect: alarms do not
     * survive a reboot, and a force-stop cancels them too.
     */
    suspend fun rescheduleAll(context: Context, store: CycleStateStore, now: StampedInstant) {
        for (step in ReminderBook.reschedule(store.currentReminders(), now)) {
            when (step) {
                is ReminderBook.Step.Schedule -> schedule(context, step.reminder.id, step.atWallMs)
                is ReminderBook.Step.FireLate -> fire(context, store, step.reminder.id, late = true)
            }
        }
    }

    /**
     * Mark [id] fired and sound the tone. Once only: an alarm delivered after
     * a reschedule already fired it late finds it fired and does nothing.
     */
    suspend fun fire(context: Context, store: CycleStateStore, id: Long, late: Boolean) {
        val pending = store.currentReminders().firstOrNull { it.id == id && !it.fired } ?: return
        store.markReminderFired(pending.id, late)
        ReminderChime.play(context)
        Log.i(TAG, "reminder ${pending.id} fired${if (late) " late" else ""}")
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        // The request code keys the PendingIntent, so each reminder has its
        // own. Ids are issued monotonically and capped far below Int range in
        // any realistic life of an install.
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
