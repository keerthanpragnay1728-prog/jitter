package dev.molasses.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import dev.molasses.core.model.EventType
import dev.molasses.core.model.GateOutcome
import dev.molasses.core.safety.HomeFirst
import dev.molasses.engine.FrictionLedger
import dev.molasses.sensing.MovementDetector
import dev.molasses.ui.gate.GateScreen
import dev.molasses.ui.theme.MolassesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows and tears down the movement gate, and owns the sensor lifetime.
 *
 * Sensors are registered only while a gate is open and unregistered on pass,
 * abandon, or the 90 s timeout. Continuous IMU sampling is by a wide margin
 * the largest battery risk in this app, and a friction tool that visibly eats
 * battery gets uninstalled, which defeats the point.
 */
class GateOverlayManager(
    private val service: Context,
    private val windowManager: WindowManager,
    private val detector: MovementDetector,
    private val ledger: FrictionLedger,
    private val scope: CoroutineScope,
    private val onCleared: (pkg: String) -> Unit,
    private val onAbandoned: (pkg: String) -> Unit,
    /**
     * Fires `GLOBAL_ACTION_HOME`. This gate sends its app home after its
     * first draw and runs over the launcher; see `HomeFirst`.
     */
    private val goHome: () -> Unit,
    /**
     * Fired after the gate window is added or removed. The gate belongs to our
     * own package, so without this the service reads showing it as the user
     * going home and closes the session it is gating.
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
    private var currentPkg: String? = null
    private var currentTier: Int = 0

    private var timeoutJob: Job? = null
    private var watchJob: Job? = null

    /**
     * One resolution per session. Without it a duplicate outcome, or a timeout
     * racing a pass, would clear the same gate twice and put the lease panel
     * up twice.
     */
    private var resolved = false

    val isShowing: Boolean get() = host?.isShowing == true

    /**
     * Always, while showing: this gate sends its app home under itself, so
     * leaving the app must not abandon it. See [HomeFirst].
     */
    val homeFirst: Boolean get() = isShowing && HomeFirst.sendsHome(HomeFirst.Overlay.WALK_GATE)

    /** The package this window is up for, or null when it is not showing. */
    val showingFor: String? get() = if (isShowing) currentPkg else null

    /**
     * Idempotent: a second call for the same package and tier is a no-op.
     *
     * @return whether a window is genuinely on the glass afterwards. False
     *   means the app is uncovered and the caller must fall through to
     *   ordinary friction. See `LaunchGate.Outcome`.
     */
    fun show(pkg: String, tier: Int, alternativeChallenge: Boolean): Boolean {
        if (isShowing && currentPkg == pkg && currentTier == tier) return true
        if (isShowing) dismissInternal()

        currentPkg = pkg
        currentTier = tier
        resolved = false

        // A fresh host per gate: OverlayHost is single-use by construction.
        val h = OverlayHost(service, windowManager)
        host = h

        detector.start(alternativeChallenge)

        // After the first draw, never from this call: the frame has to reach
        // the display first or the user is bounced with nothing explaining it.
        h.show(
            onFirstDraw = if (HomeFirst.sendsHome(HomeFirst.Overlay.WALK_GATE)) {
                {
                    if (currentPkg == pkg && !resolved) {
                        goHome()
                        Log.i(TAG, "overlay=${HomeFirst.Overlay.WALK_GATE} home sent for $pkg")
                    }
                }
            } else {
                null
            },
        ) {
            MolassesTheme(fontScale = fontScale()) {
                GateScreen(
                    tier = tier,
                    pkg = pkg,
                    progressFlow = detector.progress,
                    alternativeChallenge = alternativeChallenge,
                    challengePhrase = detector.challengePhrase,
                    onChallengeAnswer = { detector.submitChallenge(it) },
                    onDebugBypass = { detector.bypassForDebug() },
                )
            }
        }

        if (!h.isShowing) {
            // addView failed. Same rule as the lease gate: this did not
            // happen, so it is not reported as though it did, and the sensors
            // come straight back down rather than running for a gate nobody
            // can see or clear.
            Log.e(TAG, "movement gate window could not be added for $pkg; friction falls through")
            detector.stop()
            host = null
            currentPkg = null
            currentTier = 0
            return false
        }

        // first() rather than collect{}: it completes the collection before
        // the handler runs, so teardown is not executing inside the very
        // coroutine it is about to cancel. watchJob is nulled first for the
        // same reason, so dismissInternal has nothing to cancel here.
        onWindowsChanged()

        watchJob = scope.launch(Dispatchers.Main.immediate) {
            val outcome = detector.outcome.first()
            watchJob = null
            resolve(outcome)
        }

        timeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(GATE_TIMEOUT_MS)
            timeoutJob = null
            // A gate left open forever would keep the IMU running. Timing out
            // is an abandon, not a pass: the toll is still owed.
            abandon("timeout")
        }
        return true
    }

    /**
     * The gate ended successfully. Idempotent: a second outcome, or an outcome
     * racing the timeout, is dropped.
     *
     * Everything teardown needs is captured before [dismissInternal] runs,
     * because that clears [currentPkg] and [currentTier].
     */
    private fun resolve(outcome: GateOutcome) {
        val pkg = currentPkg ?: return
        if (resolved) return
        resolved = true
        val tier = currentTier

        // No row for the walk itself. Clearing it does not buy anything on
        // its own: it puts the lease panel up, and LEASE_TAKEN is written by
        // FrictionEngine when a duration is actually chosen. A row here would
        // say a toll was paid at a moment when it might still be declined.
        // The per-tick detail is in the GATE_EVAL logcat stream, and every
        // ledger row already carries the sensing path.
        if (outcome is GateOutcome.BypassedForDebug) {
            ledger.log(
                pkg,
                EventType.GATE_BYPASSED_DEBUG,
                "tier=$tier path=${outcome.path}",
            )
        }

        dismissInternal()
        onCleared(pkg)
    }

    /**
     * The target app left the foreground, the screen went off, or the gate
     * timed out. Per SS7 this *pauses* the gate rather than clearing it: HOME
     * and RECENTS cannot be blocked from an overlay, and pretending otherwise
     * would just mean a gate that vanishes on a home press and grants free
     * usage on return.
     */
    fun abandon(reason: String) {
        val pkg = currentPkg ?: return
        if (resolved) return
        resolved = true
        ledger.log(pkg, EventType.GATE_ABANDONED, "reason=$reason tier=$currentTier")
        dismissInternal()
        onAbandoned(pkg)
    }

    fun dismiss() {
        resolved = true
        dismissInternal()
    }

    private fun dismissInternal() {
        // The one choke point every way out runs through: a pass, an abandon,
        // the timeout, a dismiss from the service, and a replacement by a new
        // show.
        //
        // Both jobs null themselves out before invoking a handler, so a cancel
        // here never targets the coroutine that is currently running.
        timeoutJob?.cancel(); timeoutJob = null
        watchJob?.cancel(); watchJob = null
        detector.stop()
        host?.dismiss()
        host = null
        onWindowsChanged()
        currentPkg = null
        currentTier = 0
    }

    companion object {
        /** SS8: unregister sensors on pass, abandon, or this timeout. */
        const val GATE_TIMEOUT_MS = 90_000L

        private const val TAG = "Molasses.Gate"
    }
}
