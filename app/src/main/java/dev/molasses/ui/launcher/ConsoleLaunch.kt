package dev.molasses.ui.launcher

import android.app.Activity
import android.content.Intent

/**
 * Every activity start from the console: the drawer, quick launch, the
 * shortcut ladder's rungs, `[phone]`, Wellbeing, CFG, and the intents a
 * command opens. Throws whatever `startActivity` throws; each caller keeps
 * its own answer to that.
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
 */
internal fun Activity.startFromConsole(intent: Intent) {
    startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
