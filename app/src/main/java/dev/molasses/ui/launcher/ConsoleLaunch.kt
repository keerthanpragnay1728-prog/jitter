package dev.molasses.ui.launcher

import android.app.Activity
import android.content.Intent
import android.util.Log
import dev.molasses.core.launch.ConsoleStart

private const val TAG = "Molasses.ConsoleLaunch"

/**
 * Where a refused start is said. The console page sets [say] while it is
 * composed and clears it when it is not, so a refusal from the drawer, a
 * quick-launch row or a command lands on the console's own answer line.
 *
 * The ledger page has no answer line. Wellbeing is opened from there, so a
 * refused Wellbeing start is logged and not said, because the console page
 * is not composed to say it.
 */
internal class ConsoleRefusal {
    var say: (() -> Unit)? = null
}

/**
 * Every activity start from the console: the drawer, quick launch, the
 * shortcut ladder's rungs, `[phone]`, Wellbeing, CFG, and the intents a
 * command opens. The one path, so no caller wraps it in a catch of its own.
 *
 * ## Never a crash
 * Any `RuntimeException` from the start call is caught, logged with its
 * class and the intent, and said on the console in one line through
 * [refusal]. The platform's usual two are `ActivityNotFoundException` and
 * `SecurityException`. The start then reads as false, so the ladder can try
 * its next rung and a command can give its own, more specific answer, which
 * replaces this one on the same line. The catch covers the start call alone,
 * and an `Error` is thrown on; see `ConsoleStart`.
 *
 * ## Why NEW_TASK on every one, set here
 * The console is the home activity, in the home task. A start without
 * `FLAG_ACTIVITY_NEW_TASK` asks the system to put the new activity in the
 * caller's task, and what it then does with a home task is the platform's
 * call, not one this app can see or test. Every launcher sets the flag on
 * every launch so that question never comes up. Here it is set once, in
 * this function, rather than at each call site.
 *
 * A package launch intent from `getLaunchIntentForPackage` carries the flag
 * already, as the platform builds it today. That is an implementation
 * detail of another codebase, so the console does not rely on it.
 *
 * The intent is copied, so a caller's intent is never changed under it.
 *
 * Not for an activity result: the system cancels a result request started
 * with NEW_TASK at once. The home role request is one, and it lives in the
 * setup controller for that reason.
 *
 * @return true when the start returned, false when it was refused.
 */
internal fun Activity.startFromConsole(intent: Intent, refusal: ConsoleRefusal): Boolean =
    ConsoleStart.attempt(
        start = { startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onRefused = {
            Log.w(TAG, "nothing could open $intent: ${it.javaClass.name}", it)
            refusal.say?.invoke()
        },
    )
