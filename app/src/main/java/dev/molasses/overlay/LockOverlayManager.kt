package dev.molasses.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import dev.molasses.core.command.CommandRender
import dev.molasses.core.lock.LockEnforcement
import dev.molasses.core.lock.LockReason
import dev.molasses.core.model.EventType
import dev.molasses.core.safety.OverlayAudio
import dev.molasses.engine.FrictionLedger
import dev.molasses.ui.lock.LockScreen
import dev.molasses.ui.lock.lockOpensAtText
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
    private var callJob: Job? = null
    private var currentPkg: String? = null

    /** See [watchForCall]. The same permission-free check the shutter uses. */
    private val calls = CallDetector(service)
    private val focus = AudioFocusHold(service)

    val isShowing: Boolean get() = host?.isShowing == true

    /** The package this window is up for, or null when it is not showing. */
    val showingFor: String? get() = if (isShowing) currentPkg else null

    /**
     * Show the message. It stays until the user presses the way out.
     *
     * ## Why it is not a flash any more
     * It used to hold about 1.4 s, fire home, and hold another 0.4 s to cover
     * the transition. On a device that reads as the screen changing
     * underneath you while you are still reading why it changed. The message
     * is the whole point of drawing anything at all, and the reader decides
     * when they have finished with it.
     *
     * The name stays because every call site and the ledger row say flash,
     * and renaming it would touch more than it clarifies.
     *
     * Idempotent while showing: a second scroll, or a duplicate foreground
     * event, must not stack two windows.
     *
     * @param atEntry raised as the locked app opens, rather than over a
     *   session already running. Decides audio focus and the media pause
     *   key together; see `OverlayAudio`.
     */
    fun flash(
        pkg: String,
        label: String,
        reason: LockReason,
        remainingMs: Long,
        opensAtWallMs: Long,
        atEntry: Boolean,
    ) {
        if (isShowing) return

        currentPkg = pkg
        val h = OverlayHost(service, windowManager)
        host = h

        // For the ledger row. The screen shows the opening instant instead,
        // from the stored lock through the restriction clamp: see LockOpensAt.
        val remainingText = CommandRender.duration(remainingMs)
        val opensAtText = lockOpensAtText(service, opensAtWallMs)

        // No onFirstDraw callback any more, and the reason it existed is
        // worth keeping written down. It was there because GLOBAL_ACTION_HOME
        // fired from a timer started at show(): sending home from the call
        // that added the window meant the frame never composited, so the user
        // was thrown to the launcher with no explanation and it read as a
        // crash. Once the user is the one pressing home, the frame has
        // demonstrably been composited, because they looked at it and pressed
        // a button on it.
        //
        // The settle hold on the way out is a different thing and survives:
        // it is about not revealing the locked app for a frame during the
        // transition, and that is still true however home was triggered.
        h.show {
            MolassesTheme(fontScale = fontScale()) {
                LockScreen(
                    label = label,
                    opensAtText = opensAtText,
                    onExit = { exit("user") },
                )
            }
        }

        if (!h.isShowing) {
            // addView failed. Enforce anyway, and this fallback matters more
            // than it did: the bounce used to happen on every path, so a
            // failed attach cost the message and nothing else. It is now the
            // only thing standing between a failed window and a user sitting
            // inside a locked app with nothing stopping them, which is the
            // same fail-open shape the launch gate had. The bounce is removed
            // from the success path, never from this one.
            Log.w(TAG, "lock overlay addView failed for $pkg; bouncing without the message")
            host = null
            currentPkg = null
            goHome()
            return
        }

        // After the addView check, for the reason the lease gate gives: the
        // failure path above returns without reaching dismiss(), so a request
        // made earlier would never be given back.
        //
        // This window needs it more than the gate does. The gate runs for at
        // most thirty seconds; this one lives until the user presses the way
        // out, over an app they are not allowed to use at all, so a locked app
        // playing audio behind a full-screen refusal is the same defect with
        // no upper bound on it.
        focus.take("lock overlay for $pkg", overlay = OverlayAudio.lock(atEntry))

        onWindowsChanged()
        ledger.log(pkg, EventType.LOCK_ENFORCED, "remaining=${remainingText} reason=${reason.name}")
        watchForCall()
    }

    /**
     * Take the window down and go home.
     *
     * The settle hold is what keeps the locked app from being revealed for a
     * frame while the transition runs, so dismissal comes after home rather
     * than with it.
     */
    private fun exit(reason: String) {
        if (host == null) return
        job?.cancel()
        job = scope.launch(Dispatchers.Main.immediate) {
            goHome()
            delay(LockEnforcement.HOME_SETTLE_MS)
            job = null
            dismiss(reason)
        }
    }

    /**
     * The panic path this window did not need while it lived 1.8 seconds.
     *
     * A full-screen overlay that stays until the user acts is a full-screen
     * overlay over the incoming call the user is trying to answer. The
     * shutter has had this check since it existed; this window now lives long
     * enough to need it too.
     *
     * `whenUnknown = true`, which is the shutter's answer and the opposite of
     * the launch gate's, and the asymmetry is the point. Taking this window
     * down costs nothing: the lock lives in `LockRegistry`, not in this
     * window, so the next entry or scroll enforces it again. Dismissing is
     * not unlocking. A detector that cannot answer therefore degrades this
     * screen to the timed bounce it used to be, rather than to a bypass.
     */
    private fun watchForCall() {
        callJob?.cancel()
        callJob = scope.launch(Dispatchers.Main.immediate) {
            while (isShowing) {
                delay(CALL_POLL_MS)
                if (!isShowing) return@launch
                if (calls.inProgress(whenUnknown = true)) {
                    Log.i(TAG, "lock flash yielding: ${calls.describe()}")
                    exit("call in progress")
                    return@launch
                }
            }
        }
    }

    /** Idempotent and exception-safe. Safe to call from any teardown path. */
    fun dismiss(reason: String) {
        job?.cancel()
        job = null
        callJob?.cancel()
        callJob = null
        val h = host ?: return
        // Past the null check deliberately. Focus is only ever taken once a
        // window is genuinely attached, so a null host means there is nothing
        // held, and releasing above this line would be a claim about state
        // this method has not established yet.
        focus.release("lock overlay down ($reason)")
        host = null
        currentPkg = null
        Log.i(TAG, "lock flash down ($reason)")
        h.dismiss()
        onWindowsChanged()
    }

    private companion object {
        const val TAG = "Molasses.LockOverlay"

        /**
         * How often the call check runs while this window is up.
         *
         * Coarse on purpose. The check is a cached binder read, but this is a
         * window the user is reading rather than interacting with, and a
         * second of latency on yielding to a call is not the difference
         * between answering it and missing it.
         */
        const val CALL_POLL_MS = 1_000L
    }
}
