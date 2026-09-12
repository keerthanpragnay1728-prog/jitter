package dev.molasses.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.molasses.core.model.GateOutcome
import dev.molasses.core.model.GateProgress
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Movement verification, with a three-level sensor hierarchy plus the
 * accessibility escape hatch.
 *
 * ```
 * 1. TYPE_STEP_DETECTOR                        (needs ACTIVITY_RECOGNITION)
 * 2. TYPE_LINEAR_ACCELERATION + TYPE_GRAVITY   (platform fusion)
 * 3. TYPE_ACCELEROMETER + GravitySplitter      (our IIR)
 * ```
 *
 * Level 1 is preferred because the OEM's own fusion has already done the hard
 * part and it costs almost no power.
 *
 * Level 2 skips our gravity IIR entirely, but it requires *both* fused
 * sensors. `TYPE_LINEAR_ACCELERATION` alone is not enough: the
 * vertical-energy-share and gravity-angle tests both need a gravity vector,
 * and re-deriving one would reintroduce the filter this level exists to avoid.
 * A device with linear acceleration but no gravity sensor therefore drops to
 * level 3 rather than running a half-fused pipeline.
 *
 * Levels 2 and 3 are separate *calibration domains* and carry separate
 * threshold sets -- see [Thresholds]. The active path is published on
 * [progress] and stamped on every ledger row via [onPathChanged].
 *
 * Sensors are registered in [start] and unregistered in [stop], which
 * [dev.molasses.overlay.GateOverlayManager] calls on pass, abandon, or
 * timeout. Nothing here samples outside an open gate.
 */
class MovementDetector(
    context: Context,
    /**
     * Called when the active pipeline is decided, so the ledger can stamp
     * which calibration domain produced every subsequent row. Null on [stop].
     */
    private val onPathChanged: (GateProgress.Path?) -> Unit = {},
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    private val stepGate = StepGate()

    /** Rebuilt per gate, because the mode is decided at [start]. */
    private var imuGate: FallbackImuGate = FallbackImuGate(FallbackImuGate.Mode.IIR)

    /**
     * Sampled state, for the UI. Conflating is correct here: only the newest
     * value matters and dropping an intermediate one costs nothing.
     */
    private val _progress = MutableStateFlow(GateProgress())
    val progress: StateFlow<GateProgress> = _progress.asStateFlow()

    /**
     * The terminal event, on a channel that cannot conflate it away.
     *
     * `replay = 1` so a collector that attaches a moment late still sees it,
     * and `extraBufferCapacity = 1` so [MutableSharedFlow.tryEmit] always
     * succeeds from the sensor callback without suspending. The replay cache
     * is cleared in [start], otherwise the previous session's pass would be
     * delivered instantly to the next gate and clear it for free.
     *
     * Exactly one emission per session, guarded by [outcomeEmitted].
     */
    private val _outcome = MutableSharedFlow<GateOutcome>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val outcome: SharedFlow<GateOutcome> = _outcome.asSharedFlow()

    private var outcomeEmitted = false

    private var active = false
    private var usingStepDetector = false
    private var alternativeMode = false

    /** The pipeline actually in use, once [start] has chosen. */
    var activePath: GateProgress.Path = GateProgress.Path.NONE
        private set

    /** Thresholds backing [activePath]; surfaced in the debug screen. */
    val activeThresholds: Thresholds?
        get() = if (activePath.isImu) imuGate.thresholds else null

    /** Phrase for the alternative challenge; regenerated per gate. */
    var challengePhrase: String = ""
        private set

    private val stepSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearSensor: Sensor?
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val pressureSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** Latest gravity reading on the fused path. */
    private var fusedGx = 0.0
    private var fusedGy = 0.0
    private var fusedGz = 0.0
    private var haveFusedGravity = false

    private fun hasActivityRecognition() = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    fun start(alternativeChallenge: Boolean) {
        if (active) return
        active = true
        alternativeMode = alternativeChallenge
        stepGate.reset()
        haveFusedGravity = false

        // Without this the previous session's Passed sits in the replay cache
        // and the next gate clears itself the instant a collector attaches.
        outcomeEmitted = false
        _outcome.resetReplayCache()

        if (alternativeChallenge) {
            // Mandatory escape hatch. A friction app whose only unlock is
            // walking is unusable for wheelchair users, and one built on the
            // AccessibilityService API that excludes disabled users is
            // indefensible. No sensors are registered in this mode.
            challengePhrase = CHALLENGE_PHRASES.random(Random(System.nanoTime()))
            activePath = GateProgress.Path.ALTERNATIVE_CHALLENGE
            onPathChanged(activePath)
            _progress.value = GateProgress(
                fraction = 0f,
                reason = GateProgress.Reason.WAITING_TO_START,
                path = activePath,
            )
            return
        }

        val sm = sensorManager
        if (sm == null) {
            activePath = GateProgress.Path.NONE
            onPathChanged(null)
            _progress.value = GateProgress(reason = GateProgress.Reason.WAITING_TO_START)
            return
        }

        val step = stepSensor
        usingStepDetector = step != null && hasActivityRecognition()

        if (usingStepDetector) {
            sm.registerListener(this, step, SensorManager.SENSOR_DELAY_NORMAL)
            activePath = GateProgress.Path.STEP_DETECTOR
        } else {
            val linear = linearSensor
            val gravity = gravitySensor
            // Both fused sensors or neither: see the class doc.
            val fused = linear != null && gravity != null
            imuGate = FallbackImuGate(
                if (fused) FallbackImuGate.Mode.FUSED else FallbackImuGate.Mode.IIR,
            )
            imuGate.reset()

            if (fused) {
                sm.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
                sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
                activePath = GateProgress.Path.IMU_FUSED
            } else {
                accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                activePath = GateProgress.Path.IMU_IIR
            }

            // Corroboration only, never a requirement: most budget devices
            // have no barometer.
            pressureSensor?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        onPathChanged(activePath)
        _progress.value = GateProgress(path = activePath)
        Log.d(
            TAG,
            "gate sensors up, path=$activePath thresholds=${activeThresholds?.id ?: "n/a"} " +
                "calibration=${activeThresholds?.calibration ?: "n/a"}",
        )
    }

    fun stop() {
        if (!active) return
        active = false
        outcomeEmitted = true // no late emission after teardown
        runCatching { sensorManager?.unregisterListener(this) }
        activePath = GateProgress.Path.NONE
        haveFusedGravity = false
        onPathChanged(null)
        _progress.value = GateProgress()
        Log.d(TAG, "gate sensors down")
    }

    /** Alternative challenge: exact, case-insensitive, whitespace-tolerant. */
    fun submitChallenge(answer: String) {
        if (!alternativeMode) return
        val ok = answer.trim().replace(Regex("\\s+"), " ")
            .equals(challengePhrase, ignoreCase = true)
        _progress.value = GateProgress(
            fraction = if (ok) 1f else 0f,
            reason = if (ok) GateProgress.Reason.PASSED else GateProgress.Reason.WAITING_TO_START,
            path = GateProgress.Path.ALTERNATIVE_CHALLENGE,
        )
        if (ok && !outcomeEmitted) {
            outcomeEmitted = true
            _outcome.tryEmit(
                GateOutcome.Passed(
                    path = GateProgress.Path.ALTERNATIVE_CHALLENGE,
                    creditMs = 0,
                    ticks = 0,
                ),
            )
        }
    }

    /**
     * Debug builds only. Ends the session through the same channel a real pass
     * uses, tagged so the ledger can keep the two apart.
     */
    fun bypassForDebug() {
        if (outcomeEmitted) return
        outcomeEmitted = true
        _outcome.tryEmit(GateOutcome.BypassedForDebug(activePath))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // SensorEvent.timestamp is nanoseconds on the elapsedRealtime base.
                publish(stepGate.onStep(event.timestamp / 1_000_000))
            }

            Sensor.TYPE_GRAVITY -> {
                fusedGx = event.values[0].toDouble()
                fusedGy = event.values[1].toDouble()
                fusedGz = event.values[2].toDouble()
                haveFusedGravity = true
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Wait for the first gravity reading rather than assuming an
                // orientation: the tilt test is measured against the gravity
                // vector at gate start, and seeding it with a guess would
                // produce a spurious tilt reading for the first second.
                if (!haveFusedGravity) return
                publish(
                    imuGate.onFusedSample(
                        event.timestamp,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                        fusedGx, fusedGy, fusedGz,
                    ),
                )
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // Full nanosecond timestamp: GravitySplitter derives alpha from
                // the inter-sample delta, so millisecond truncation would
                // quantise the time constant at high delivery rates.
                publish(
                    imuGate.onRawSample(
                        event.timestamp,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                    ),
                )
            }

            Sensor.TYPE_PRESSURE -> {
                imuGate.onPressure(event.timestamp / 1_000_000, event.values[0].toDouble())
            }
        }
    }

    /**
     * Publish one evaluation. Progress goes to the conflating flow, a pass goes
     * to the one-shot channel, and the tick goes to logcat.
     *
     * Null means the sample did not close a tick, which is the common case.
     */
    private fun publish(evaluation: GateEvaluation?) {
        if (evaluation == null) return
        _progress.value = evaluation.progress

        evaluation.tick?.let { Log.d(EVAL_TAG, it.logLine()) }

        if (!evaluation.passed || outcomeEmitted) return
        outcomeEmitted = true
        _outcome.tryEmit(
            GateOutcome.Passed(
                path = activePath,
                creditMs = imuGate.creditMs,
                ticks = imuGate.ticks,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "Molasses.Movement"

        /**
         * One line per evaluation tick, every measured value and every
         * per-test verdict. The thresholds here came from synthetic gait that
         * the first device session showed to be unrepresentative, so this is
         * how they get set from real walking.
         *
         * `adb logcat -s GATE_EVAL`
         */
        const val EVAL_TAG = "GATE_EVAL"

        /**
         * 25 s untimed transcription task. Phrases are deliberately mundane
         * and a little long: the cost should be attention, not dexterity.
         */
        val CHALLENGE_PHRASES = listOf(
            "the kettle boiled twice before anyone noticed",
            "seven paper cranes on a windowsill in March",
            "the last bus leaves from the far side of the bridge",
            "a borrowed umbrella left drying in the hallway",
            "the quiet carriage is four doors down the platform",
        )
    }
}
