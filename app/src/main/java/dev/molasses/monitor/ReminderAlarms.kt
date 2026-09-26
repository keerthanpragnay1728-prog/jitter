package dev.molasses.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.molasses.core.remind.ReminderArming
import dev.molasses.core.remind.ReminderBook
import dev.molasses.core.time.StampedInstant
import dev.molasses.data.datastore.CycleStateStore

/**
 * Scheduling for `$ rem`, on AlarmManager.
 *
 * ## Exact when allowed, inexact otherwise, and the prompt says which
 * Inexact alarms slipped on hardware (a 1m reminder arrived 40 s late), so
 * reminders are exact where the platform lets them be:
 * `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true, which
 * it always is below Android 12, is by USE_EXACT_ALARM on 13 and later, and
 * is by SCHEDULE_EXACT_ALARM on 12 and 12L unless the user has revoked it in
 * the system's Alarms and reminders page. Otherwise, or if the exact call
 * throws, `setAndAllowWhileIdle`: close to its time with the screen on,
 * batched when the device is idle, and on Android 12 and later documented as
 * within an hour. If neither call succeeds the reminder stays saved and
 * is armed by [rescheduleAll] on the next service connect. [schedule]
 * returns which of the three happened and the prompt reports it.
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

    /**
     * Arm [id]'s alarm and return how, never null: NOT_ARMED when no alarm
     * could be set, including when AlarmManager is unavailable. The decision
     * is [ReminderArming.arm]; this only makes the two calls.
     */
    fun schedule(context: Context, id: Long, atWallMs: Long): ReminderArming.Armed {
        val am = context.getSystemService(AlarmManager::class.java)
        if (am == null) {
            Log.w(TAG, "no AlarmManager; reminder $id saved but not armed")
            return ReminderArming.Armed.NOT_ARMED
        }
        val intent = pendingIntent(context, id)
        return ReminderArming.arm(
            exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms(),
            tryExact = {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, intent) }
                    // A revocation can land between the check and the call.
                    .onFailure { Log.w(TAG, "exact alarm refused for reminder $id; falling back to inexact", it) }
                    .isSuccess
            },
            tryInexact = {
                runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, intent) }
                    .onFailure { Log.w(TAG, "could not schedule reminder $id; saved but not armed", it) }
                    .isSuccess
            },
        )
    }

    /**
     * Re-arm every unfired reminder, and fire the ones already due, marked
     * late. Run on BOOT_COMPLETED and on every service connect: alarms do not
     * survive a reboot, and a force-stop cancels them too. The chime sounds
     * once for the whole backlog, not once per reminder, so a boot with many
     * missed reminders stays inside the receiver's window.
     */
    suspend fun rescheduleAll(context: Context, store: CycleStateStore, now: StampedInstant) {
        var firedAny = false
        for (step in ReminderBook.reschedule(store.currentReminders(), now)) {
            when (step) {
                is ReminderBook.Step.Schedule -> schedule(context, step.reminder.id, step.atWallMs)
                is ReminderBook.Step.FireLate -> if (markFired(store, step.reminder.id, late = true)) firedAny = true
            }
        }
        if (firedAny) ReminderChime.play(context)
    }

    /**
     * Mark [id] fired and sound the chime, returning after it has played.
     * Once only: an alarm delivered after a reschedule already fired it late
     * finds it fired and does nothing.
     */
    suspend fun fire(context: Context, store: CycleStateStore, id: Long) {
        if (markFired(store, id, late = false)) ReminderChime.play(context)
    }

    private suspend fun markFired(store: CycleStateStore, id: Long, late: Boolean): Boolean {
        val pending = store.currentReminders().firstOrNull { it.id == id && !it.fired } ?: return false
        store.markReminderFired(pending.id, late)
        Log.i(TAG, "reminder ${pending.id} fired${if (late) " late" else ""}")
        return true
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
