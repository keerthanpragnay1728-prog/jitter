package dev.molasses.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import dev.molasses.core.command.CommandRender
import dev.molasses.core.lock.LockEnforcement
import dev.molasses.core.lock.LockReason
import dev.molasses.core.model.EventType
import dev.molasses.engine.FrictionLedger
import dev.molasses.ui.lock.LockScreen
import dev.molasses.ui.theme.MolassesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock flash, and the home action that follows it.
 *
 * ## The ordering this class exists to get right
 * Sending `GLOBAL_ACTION_HOME` from the same call that added the window means
 * the frame is never composited: the user is thrown to the launcher with no
 * explanation, which is indistinguishable from a crash and makes them open the
 * app again. So the hold is started from the window's first draw callback,
 * not from the call that showed it, and the window stays up across the
 * transition rather than vanishing to reveal the locked app for a frame.
 *
 * ## Why it is its own manager
 * The shutter absorbs a gesture and the gate asks for something. This does
 * neither: it is terminal, it takes no input, and it ends the session it is
 * shown over. Folding it into either would mean a third set of states in a
 * class that already has enough.
 */
class LockOverlayManager(
    private val service: Context,
    private val windowManager: WindowManager,
    private val ledger: FrictionLedger,
    private val scope: CoroutineScope,
    /** Fires `GLOBAL_ACTION_HOME`. Passed in so this class holds no service. */
    private val goHome: () -> Unit,
    /**
     * Fired after the window is added or removed. This window belongs to our
     * own package, so without it the service reads the flash as the user
     * going home and closes the session under it.
     */
    /**
     * The user's chosen text size, read at the moment this window is shown.
     *
     * A lambda and not a value. These managers are constructed once when the
     * service connects and shown many times over the hours that follow, so a
     * captured Float would freeze whatever the setting was at boot. That is
     * the same bug this parameter exists to fix, arriving by a second route.
     *
     * It matters most here and not least. An overlay is the one surface a
     * user cannot scroll, pinch or dismiss to cope with, so a gate rendered
     * at 1.0 while the user chose VERY_LARGE is the accessibility case the
     * whole setting exists for, failing in the one place it cannot be worked
     * around.
     */
    private val fontScale: () -> Float,
    private val onWindowsChanged: () -> Unit = {},
) {
    private var host: OverlayHost? = null
    private var job: Job? = null
    private var currentPkg: String? = null

    val isShowing: Boolean get() = host?.isShowing == true

    /**
     * Show the message for [LockEnforcement.FLASH_HOLD_MS], then go home.
     *
     * Idempotent while showing: a second scroll inside the hold window, or a
     * duplicate foreground event, must not stack two flashes or fire home
     * twice.
     */
    fun flash(pkg: String, label: String, reason: LockReason, remainingMs: Long) {
        if (isShowing) return

        currentPkg = pkg
        val h = OverlayHost(service, windowManager)
        host = h

        val remainingText = CommandRender.duration(remainingMs)

        h.show(
            onFirstDraw = { startHold() },
        ) {
            MolassesTheme(fontScale = fontScale()) {
                LockScreen(label = label, reason = reason, remainingText = remainingText)
            }
        }

        if (!h.isShowing) {
            // addView failed. Enforce anyway: a lock that stops working
            // because a window could not be added is worse than an
            // unexplained bounce, and the log says which happened.
            Log.w(TAG, "lock overlay addView failed for $pkg; bouncing without the message")
            host = null
            currentPkg = null
            goHome()
            return
        }

        onWindowsChanged()
        ledger.log(pkg, EventType.LOCK_ENFORCED, "remaining=${remainingText} reason=${reason.name}")
    }

    private fun startHold() {
        job?.cancel()
        job = scope.launch(Dispatchers.Main.immediate) {
            delay(LockEnforcement.FLASH_HOLD_MS)
            goHome()
            // The window covers the transition rather than vanishing to
            // reveal the locked app for a frame.
            delay(LockEnforcement.HOME_SETTLE_MS)
            job = null
            dismiss("hold elapsed")
        }
    }

    /** Idempotent and exception-safe. Safe to call from any teardown path. */
    fun dismiss(reason: String) {
        job?.cancel()
        job = null
        val h = host ?: return
        host = null
        currentPkg = null
        Log.i(TAG, "lock flash down ($reason)")
        h.dismiss()
        onWindowsChanged()
    }

    private companion object { const val TAG = "Molasses.LockOverlay" }
}
