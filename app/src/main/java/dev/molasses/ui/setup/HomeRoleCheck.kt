package dev.molasses.ui.setup

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.molasses.core.setup.HomeRole

private const val TAG = "Molasses.HomeRole"

/**
 * Whether Jitter is the home app, read now. The one reader every screen
 * uses: the first-run flow's home step, CFG's SETUP row and the console's
 * warning line all get it through `SetupFlowController.refresh`, which both
 * activities call on resume. See `HomeRole` for the decision.
 *
 * Each read that can throw is caught and reads as no answer, so a failed
 * lookup falls through to the next source and, failing both, to "not the
 * home app", which shows the way to fix it rather than hiding it.
 */
fun Context.isDefaultHome(): Boolean {
    val roleHeld = runCatching {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) rm.isRoleHeld(RoleManager.ROLE_HOME) else null
    }.onFailure { Log.w(TAG, "home role read failed; asking the resolver", it) }.getOrNull()
    return HomeRole.isDefault(roleHeld, ownPackage = packageName) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.onFailure { Log.w(TAG, "home resolution failed", it) }.getOrNull()
    }
}
