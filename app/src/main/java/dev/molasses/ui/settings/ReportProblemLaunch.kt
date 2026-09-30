package dev.molasses.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.molasses.core.support.ReportProblem

/** The Android half of [ReportProblem.spec]. The only place its Intent is built. */
fun ReportProblem.Spec.toIntent(): Intent =
    Intent(action, Uri.parse(url)).addCategory(category)

/**
 * Open the issue tracker. Never throws: a failure is logged and reported to
 * the caller, which shows a note in the row.
 *
 * The resolve check that decides whether the row is shown can still be
 * beaten between the check and the tap, by a browser being disabled or a
 * work profile refusing the launch, so the launch is guarded as well.
 *
 * @return false when nothing was opened.
 */
fun launchReportProblem(context: Context): Boolean = try {
    context.startActivity(ReportProblem.spec().toIntent())
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "report a problem: no activity for ${ReportProblem.ISSUES_URL}", e)
    false
} catch (e: SecurityException) {
    Log.w(TAG, "report a problem: launch refused", e)
    false
}

private const val TAG = "Molasses.Report"
