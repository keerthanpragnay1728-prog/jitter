package dev.molasses.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The two broadcasts `$ rem` needs: a reminder's own alarm, and BOOT_COMPLETED
 * to re-arm them all.
 *
 * Not exported. A reminder's PendingIntent names this class explicitly, and
 * BOOT_COMPLETED comes from the system, which reaches a non-exported
 * receiver; nothing else has any reason to reach it. The boot broadcast uses
 * the RECEIVE_BOOT_COMPLETED permission the manifest already declared.
 *
 * Its dependencies come through a Hilt entry point rather than
 * `@AndroidEntryPoint`. Hilt injects a receiver inside the generated
 * superclass's `onReceive`, which a Kotlin subclass of `BroadcastReceiver`
 * cannot call, because the method it can see is abstract. An entry point has
 * no such trap.
 *
 * ## The PendingResult
 * [goAsync] keeps the broadcast open while the coroutine runs, and `finish`
 * closes it in a `finally`, so it is finished on every path: after the chime
 * has played (ReminderChime.play suspends until it has), on an early return,
 * and on a failure. A failure is logged and swallowed rather than rethrown:
 * rethrown from this scope it would crash the process for a reminder, and
 * the reminder is either already marked fired or re-armed on the next
 * service connect.
 */
class ReminderReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "Molasses.Reminder"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun cycleStateStore(): CycleStateStore
        fun settingsRepository(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val store = deps.cycleStateStore()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ReminderAlarms.ACTION_FIRE -> {
                        val id = intent.getLongExtra(ReminderAlarms.EXTRA_ID, -1L)
                        if (id >= 0L) ReminderAlarms.fire(context, store, id)
                    }
                    Intent.ACTION_BOOT_COMPLETED ->
                        ReminderAlarms.rescheduleAll(context, store, deps.settingsRepository().nowStamped())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reminder broadcast $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
