package dev.molasses.ui

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Whether anything on this device handles [intent]. The one resolve check in
 * the app: the launcher's commands and favourite rows ask it, and so does
 * CFG's [ REPORT A PROBLEM ] row.
 *
 * Package visibility on API 30+ means this answers only for actions
 * declared in the manifest's `queries` block. An action that is missing
 * there reads as unhandled on a device that handles it perfectly well, so
 * the two lists are kept in step.
 *
 * ## Why the flag depends on the intent
 * `startActivity` on an **implicit** intent requires the target's filter
 * to declare `CATEGORY_DEFAULT`. `resolveActivity` with no flags does not,
 * so the unflagged query was more permissive than the thing it exists to
 * predict: it could report a handler that dispatch would then refuse.
 * `MATCH_DEFAULT_ONLY` makes the prediction agree with the outcome.
 *
 * An **explicit** intent is the opposite case, and the branch is here
 * rather than a blanket flag because of it. `ComputerEngine`'s resolver
 * takes a separate path for a component: it calls `getActivityInfo(comp,
 * flags, userId)` and then consults only the instant-app flags.
 * `MATCH_DEFAULT_ONLY` is never read there, and no filter matching happens
 * at all, so there is nothing for a category to be matched against. The
 * flag is a no-op for that path.
 *
 * Which means the branch changes nothing functionally and is written
 * anyway. `openWellbeing`'s `ComponentName` rung is the one candidate in
 * this app verified on hardware, and a reader should be able to see it is
 * untouched without having to know that detail of the resolver. A
 * guarantee that depends on a framework subtlety is a guarantee that
 * quietly stops holding.
 */
fun PackageManager.canResolve(intent: Intent): Boolean {
    val flags = if (intent.component != null) 0 else PackageManager.MATCH_DEFAULT_ONLY
    return resolveActivity(intent, flags) != null
}
