package dev.molasses.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.molasses.core.friction.NextScroll
import dev.molasses.core.lease.GateControls
import dev.molasses.core.lease.GateReadout
import dev.molasses.core.safety.MediaPause
import dev.molasses.core.safety.OverlayKind
import dev.molasses.core.lock.LockEnforcement
import dev.molasses.core.model.EventType
import dev.molasses.engine.FrictionLedger
import dev.molasses.ui.gate.LeaseGateScreen
import dev.molasses.ui.theme.MolassesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The three numbers this gate shows. Null is `--` and never a zero.
 *
 * Read once, when the gate attaches. They are a day's totals and a cycle
 * total: none of them can move enough in eight seconds to be worth a second
 * query, and the countdown has to be the only thing on the screen that moves.
 */
data class GateStats(
    val todayMs: Long?,
    val cycleMs: Long?,
    val opensToday: Int?,
)

/**
 * The launch gate: shown on entering a target app with no lease, torn down
 * when the user takes one or leaves.
 *
 * ## Why it is its own manager and not a mode of [GateOverlayManager]
 * That one owns sensors, a pass/fail outcome flow and a 90 second timeout,
 * because a movement gate is a thing the user has to *succeed* at. This gate
 * cannot be failed. It runs a clock, then offers four answers, and every one
 * of them resolves it. Folding the two together would mean a class where half
 * the states are unreachable depending on a mode flag, which is how the
 * sensors end up running for a gate that does not use them.
 *
 * ## The countdown is recomputed, never decremented
 * Every tick reads `elapsedRealtime` and subtracts. A counter stepped by the
 * tick would drift, and worse, it would stop while the device slept: the user
 * could put the screen to sleep for a minute and come back to the same eight
 * seconds. `elapsedRealtime` counts sleep, so the seconds on this screen are
 * real seconds whatever the device did with them.
 *
 * ## Every way out goes home, and goes home the same way
 * `[ ARCHITECT'S SPACE ]`, the back key and the safety timeout all run the
 * same path: fire the home action, then hold the window up across the transition
 * exactly as the lock flash does. Dismissing first would reveal the target app
 * for a frame or leave it resumed underneath, which is the half-resumed state
 * that makes a user tap back into it without meaning to.
 *
 * ## Both lease gates send the app home under themselves
 * See `OverlayKind`. After its first draw the gate fires home, so the target
 * goes to the background and stops its own playback, and the gate stays up
 * over the launcher with the countdown running. That is the entry gate as
 * much as the LEASE EXPIRED one: an app under an overlay is resumed, and
 * Instagram started a Reel with sound behind the entry gate. The session
 * closes on that launcher exit as it always did, but the gate survives it:
 * The service's leaveTarget leaves a showing gate alone.
 * Its way out is [ ARCHITECT'S SPACE ], from the first frame, and back does
 * the same. A lease taken here is granted first, then the target is
 * relaunched, which restores its task, then the gate comes down.
 */
class LeaseGateOverlayManager(
    private val service: Context,
    private val windowManager: WindowManager,
    private val ledger: FrictionLedger,
    private val scope: CoroutineScope,
    /** `SystemClock.elapsedRealtime`. Injected so this class holds no clock. */
    private val monotonicMs: () -> Long,
    /** Fires `GLOBAL_ACTION_HOME`. Passed in so this class holds no service. */
    private val goHome: () -> Unit,
    /**
     * Sends one media PAUSE key, after home, on the LEASE EXPIRED gate only.
     * See `MediaPause`. True when it was dispatched.
     */
    private val pauseMedia: () -> Boolean,
    private val onLeaseTaken: (pkg: String, durationMs: Long) -> Unit,
    private val onDeclined: (pkg: String, reason: String) -> Unit,
    /**
     * A rung was chosen from [ BLOCK THIS APP ]. The service arms the lock
     * through `LockRequest` and the same registry write as every other path,
     * then shows the lock screen. See `GateBlock`.
     */
    private val onBlock: (pkg: String, durationMs: Long) -> Unit,
    /**
     * Bring [pkg] back after a lease is taken. The gate sent it to the
     * background. True when the launch was started.
     */
    private val relaunch: (pkg: String) -> Boolean,
    /**
     * Fired after the window is added or removed. This window belongs to our
     * own package, so without it the service reads the gate as the user going
     * home and closes the session it is gating.
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
    private val calls = CallDetector(service)

    private var host: OverlayHost? = null
    private var currentPkg: String? = null
    private var ticker: Job? = null
    private var holdJob: Job? = null

    /**
     * The numbers, as Compose state, so the two the system owns can land
     * after the window is already up. See the service's `showLeaseGate` for
     * why they are not waited for.
     */
    private var stats by mutableStateOf(GateStats(null, null, null))

    /**
     * One resolution per gate. Without it the back key racing a tap on a
     * lease button would both take the lease and send the user home.
     */
    private var resolved = false

    /**
     * The block rungs are showing in place of [ BLOCK THIS APP ]. Compose
     * state, and held here rather than in the screen, because the back key is
     * handled here: back from the rung row closes the row and leaves the gate
     * up, and only back from the gate itself declines.
     */
    private var blockPickerOpen by mutableStateOf(false)

    val isShowing: Boolean get() = host?.isShowing == true

    /** The package this window is up for, or null when it is not showing. */
    val showingFor: String? get() = if (isShowing) currentPkg else null

    /**
     * Idempotent: a second call for the package already gated is a no-op.
     *
     * @return whether a window is genuinely on the glass afterwards. False
     *   means the app is uncovered and the caller must not treat the gate as
     *   having happened. See `LaunchGate.Outcome`.
     */
    fun show(
        pkg: String,
        label: String,
        countdownMs: Long,
        expired: Boolean,
        stats: GateStats,
        /**
         * What the next scroll costs, read once when the window goes up.
         *
         * Not a [GateStats] field and not refreshed by [updateStats], because
         * it is not one of the numbers the system owns and answers late. It is
         * in hand immediately, and it barely moves across thirty seconds of a
         * curve measured in minutes. The countdown stays the only thing on
         * this screen that changes.
         */
        nextScroll: NextScroll.Reading,
    ): Boolean {
        if (isShowing && currentPkg == pkg) return true
        if (isShowing) dismissInternal()

        currentPkg = pkg
        resolved = false
        blockPickerOpen = false
        val kind = OverlayKind.leaseGate(expired)

        this.stats = stats
        val deadline = monotonicMs() + countdownMs
        // A Compose state read by the composition and written by the ticker.
        // mutableLongStateOf rather than mutableStateOf<Long> so the tick does
        // not box a Long five times a second for the life of the gate.
        var remaining by mutableLongStateOf(countdownMs)

        val h = OverlayHost(service, windowManager)
        host = h

        h.show(
            onFirstDraw = { sendHome(pkg, kind) },
            onBackPressed = {
                // Back from the rung row returns to the gate; it does not
                // answer it. Otherwise back is the exit, on either gate, the
                // same handler as [ ARCHITECT'S SPACE ].
                when {
                    blockPickerOpen -> blockPickerOpen = false
                    else -> exit()
                }
            },
        ) {
            MolassesTheme(fontScale = fontScale()) {
                LeaseGateScreen(
                    appLabel = label,
                    expired = expired,
                    fields = GateReadout.fields(
                        todayMs = this@LeaseGateOverlayManager.stats.todayMs,
                        cycleMs = this@LeaseGateOverlayManager.stats.cycleMs,
                        opensToday = this@LeaseGateOverlayManager.stats.opensToday,
                        remainingMs = remaining,
                    ),
                    nextScroll = nextScroll,
                    controls = GateControls.visible(expired, remaining),
                    onTakeLease = { ms -> takeLease(ms) },
                    onExit = { exit() },
                    blockPickerOpen = blockPickerOpen,
                    onOpenBlock = { blockPickerOpen = true },
                    onBlock = { ms -> block(ms) },
                )
            }
        }

        if (!h.isShowing) {
            // addView failed. The app is uncovered, so this did not happen
            // and must not be reported as though it did: the caller falls
            // through to ordinary friction on a false return. Bouncing the
            // user from a gate they were never shown is not the alternative,
            // because an unexplained bounce reads as a crash.
            Log.e(TAG, "lease gate window could not be added for $pkg; friction falls through")
            host = null
            currentPkg = null
            return false
        }

        onWindowsChanged()
        ledger.log(
            pkg,
            EventType.LEASE_GATE_SHOWN,
            "countdown=${countdownMs}ms expired=$expired",
        )

        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(TICK_MS)
                // Recomputed from the clock, never decremented. See the class
                // doc: a counter would drift and would stop while asleep.
                remaining = (deadline - monotonicMs()).coerceAtLeast(0L)
                // A call outranks the gate. Checked on the tick rather than
                // with a listener because the permission-free check is a
                // cached binder read and a listener needs READ_PHONE_STATE,
                // which this app deliberately does not request.
                //
                // `whenUnknown = false`, the opposite of the shutter's answer,
                // and for the opposite reason. Treating "cannot tell" as a
                // call here would take the gate down on its first tick, every
                // time, on any device where the detector is broken: every
                // target app would open free and nothing would say so. The
                // ordinary case is still covered, because a dialer coming to
                // the foreground takes the gate down through the watchdog.
                // This check exists for the VoIP call that never does.
                if (calls.inProgress(whenUnknown = false)) {
                    dismiss("call in progress")
                    return@launch
                }
                if (monotonicMs() - deadline > STUCK_AFTER_MS) {
                    decline("stuck")
                    return@launch
                }
            }
        }
        return true
    }

    /**
     * Fill in the numbers that were not ready when the window went up.
     *
     * Keyed on the package so a query that lands after the gate has moved on
     * to a different app cannot write that app's numbers under this one's
     * name. A stale answer renders as `--`, which is what it is.
     *
     * Main thread only: it writes Compose state read by a live composition.
     */
    fun updateStats(pkg: String, stats: GateStats) {
        if (currentPkg != pkg) return
        this.stats = stats
    }

    private fun takeLease(durationMs: Long) {
        val pkg = currentPkg ?: return
        if (resolved) return
        resolved = true
        // LEASE_TAKEN belongs to FrictionEngine, which writes it from
        // onLeaseGranted along with the accounting it changes. Logging it
        // here too would put two rows in the ledger for one decision, the
        // same reason GateOverlayManager does not write GATE_PASSED.
        //
        // The app is in the background under this gate, so: grant, then
        // relaunch, then come down. Granted first so the enter event the
        // relaunch produces finds the lease and passes; the gate stays up
        // until the launch is started so the launcher is never shown bare
        // between the two.
        onLeaseTaken(pkg, durationMs)
        val launched = relaunch(pkg)
        Log.i(TAG, "relaunch on lease: pkg=$pkg success=$launched")
        dismissInternal()
    }

    /**
     * After the first draw: send the app home under this window. The frame
     * has composited by then, so the user sees the gate rather than a bounce.
     */
    private fun sendHome(pkg: String, kind: OverlayKind) {
        if (currentPkg != pkg || resolved) return
        goHome()
        // After home, so the key also reaches a video YouTube has just moved
        // into picture-in-picture. Never at entry: see MediaPause.
        val paused = MediaPause.sendsPause(kind) && pauseMedia()
        Log.i(TAG, "overlay=$kind home sent for $pkg; pause sent=$paused")
    }

    /**
     * [ ARCHITECT'S SPACE ], and back, on either gate: one handler.
     *
     * Leaving is never relief. It grants nothing, clears nothing, and does
     * not move the countdown escalation, which counts leases taken. Home is
     * sent again on the way out, which is harmless if the first one landed
     * and covers the case where it had not.
     */
    private fun exit() {
        Log.i(TAG, "exit taken for $currentPkg")
        decline("exit")
    }

    /**
     * A block rung was chosen. Resolves the gate like a lease does, and hands
     * the decision to the service, which arms the lock and puts the lock
     * screen up. No home action here: the lock screen replaces this window
     * and its own way out is the one that goes home.
     */
    private fun block(durationMs: Long) {
        val pkg = currentPkg ?: return
        if (resolved) return
        resolved = true
        ledger.log(pkg, EventType.LEASE_DECLINED, "reason=block ${durationMs}ms")
        dismissInternal()
        onBlock(pkg, durationMs)
    }

    /**
     * The way out, in every spelling. Fires home first and holds the window
     * across the transition, as [LockOverlayManager] does and for the same
     * reason.
     */
    private fun decline(reason: String) {
        val pkg = currentPkg ?: return
        if (resolved) return
        resolved = true
        ticker?.cancel(); ticker = null
        ledger.log(pkg, EventType.LEASE_DECLINED, "reason=$reason")
        goHome()
        holdJob = scope.launch(Dispatchers.Main.immediate) {
            delay(LockEnforcement.HOME_SETTLE_MS)
            holdJob = null
            dismissInternal()
            onDeclined(pkg, reason)
        }
    }

    /**
     * Take the gate down without answering it.
     *
     * The target app left the foreground, a call started, or the service is
     * tearing down. This resolves nothing: no lease was taken, so the next
     * entry gates again at the same length.
     */
    fun dismiss(reason: String) {
        if (!isShowing && host == null) return
        val pkg = currentPkg
        resolved = true
        Log.i(TAG, "lease gate down ($reason)")
        dismissInternal()
        if (pkg != null) onDeclined(pkg, reason)
    }

    private fun dismissInternal() {
        ticker?.cancel(); ticker = null
        holdJob?.cancel(); holdJob = null
        val h = host
        host = null
        currentPkg = null
        h?.dismiss()
        onWindowsChanged()
    }

    private companion object {
        const val TAG = "Molasses.LeaseGate"

        /**
         * Five a second. Fast enough that the digit changes on the second it
         * should, slow enough to be free next to a composition that is drawing
         * one number.
         */
        const val TICK_MS = 200L

        /**
         * A gate whose panel has been up this long with nothing touched is a
         * window the user has walked away from, not a decision in progress.
         * It goes home rather than dismissing, because dismissing would hand
         * them the app for free and waiting is then the cheapest answer on
         * the screen.
         */
        const val STUCK_AFTER_MS = 5L * 60 * 1000
    }
}
