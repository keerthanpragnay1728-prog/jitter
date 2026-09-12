package dev.molasses.overlay

import android.content.Context
import android.view.WindowManager
import dev.molasses.core.model.EventType
import dev.molasses.engine.FrictionLedger
import dev.molasses.sensing.MovementDetector
import dev.molasses.ui.gate.GateScreen
import dev.molasses.ui.theme.MolassesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
) {
    private var host: OverlayHost? = null
    private var currentPkg: String? = null
    private var currentTier: Int = 0
    private var timeoutJob: Job? = null
    private var watchJob: Job? = null

    val isShowing: Boolean get() = host?.isShowing == true

    /** Idempotent: a second call for the same package and tier is a no-op. */
    fun show(pkg: String, tier: Int, alternativeChallenge: Boolean) {
        if (isShowing && currentPkg == pkg && currentTier == tier) return
        if (isShowing) dismissInternal()

        currentPkg = pkg
        currentTier = tier

        // A fresh host per gate: OverlayHost is single-use by construction.
        val h = OverlayHost(service, windowManager)
        host = h

        detector.start(alternativeChallenge)

        h.show {
            MolassesTheme {
                GateScreen(
                    tier = tier,
                    pkg = pkg,
                    progressFlow = detector.progress,
                    alternativeChallenge = alternativeChallenge,
                    challengePhrase = detector.challengePhrase,
                    onChallengeAnswer = { detector.submitChallenge(it) },
                )
            }
        }

        watchJob = scope.launch(Dispatchers.Main.immediate) {
            detector.progress.collect { p ->
                if (p.passed) pass()
            }
        }

        timeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(GATE_TIMEOUT_MS)
            // A gate left open forever would keep the IMU running. Timing out
            // is an abandon, not a pass: the toll is still owed.
            abandon("timeout")
        }
    }

    private fun pass() {
        val pkg = currentPkg ?: return
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
        ledger.log(pkg, EventType.GATE_ABANDONED, "reason=$reason tier=$currentTier")
        dismissInternal()
        onAbandoned(pkg)
    }

    fun dismiss() = dismissInternal()

    private fun dismissInternal() {
        timeoutJob?.cancel(); timeoutJob = null
        watchJob?.cancel(); watchJob = null
        detector.stop()
        host?.dismiss()
        host = null
        currentPkg = null
        currentTier = 0
    }

    companion object {
        /** SS8: unregister sensors on pass, abandon, or this timeout. */
        const val GATE_TIMEOUT_MS = 90_000L
    }
}
