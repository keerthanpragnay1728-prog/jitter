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
import dev.molasses.core.model.GateProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Two-path movement verification, plus the accessibility escape hatch.
 *
 * Path A is `TYPE_STEP_DETECTOR` (see [StepGate]); it is preferred because the
 * OEM's own fusion has already done the hard part and it costs almost no
 * power. Path B ([FallbackImuGate]) analyses raw accelerometer cadence at
 * ~50 Hz and is used only when there is no step detector or no
 * `ACTIVITY_RECOGNITION` grant.
 *
 * Sensors are registered in [start] and unregistered in [stop], which
 * [dev.molasses.overlay.GateOverlayManager] calls on pass, abandon, or
 * timeout. Nothing here samples outside an open gate.
 */
class MovementDetector(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    private val stepGate = StepGate()
    private val imuGate = FallbackImuGate()

    private val _progress = MutableStateFlow(GateProgress())
    val progress: StateFlow<GateProgress> = _progress.asStateFlow()

    private var active = false
    private var usingStepDetector = false
    private var alternativeMode = false

    /** Phrase for the alternative challenge; regenerated per gate. */
    var challengePhrase: String = ""
        private set

    private val stepSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressureSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private fun hasActivityRecognition() = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    fun start(alternativeChallenge: Boolean) {
        if (active) return
        active = true
        alternativeMode = alternativeChallenge
        stepGate.reset()
        imuGate.reset()

        if (alternativeChallenge) {
            // Mandatory escape hatch. A friction app whose only unlock is
            // walking is unusable for wheelchair users, and one built on the
            // AccessibilityService API that excludes disabled users is
            // indefensible. No sensors are registered in this mode.
            challengePhrase = CHALLENGE_PHRASES.random(Random(System.nanoTime()))
            _progress.value = GateProgress(
                fraction = 0f,
                reason = GateProgress.Reason.WAITING_TO_START,
                path = GateProgress.Path.ALTERNATIVE_CHALLENGE,
            )
            return
        }

        val step = stepSensor
        usingStepDetector = step != null && hasActivityRecognition()

        val sm = sensorManager
        if (sm == null) {
            _progress.value = GateProgress(reason = GateProgress.Reason.WAITING_TO_START)
            return
        }

        if (usingStepDetector) {
            sm.registerListener(this, step, SensorManager.SENSOR_DELAY_NORMAL)
            _progress.value = GateProgress(path = GateProgress.Path.STEP_DETECTOR)
        } else {
            accelSensor?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            // Corroboration only, never a requirement: most budget devices
            // have no barometer.
            pressureSensor?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            _progress.value = GateProgress(path = GateProgress.Path.IMU_CADENCE)
        }
        Log.d(TAG, "gate sensors up, path=${if (usingStepDetector) "step" else "imu"}")
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { sensorManager?.unregisterListener(this) }
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
            passed = ok,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        // SensorEvent.timestamp is nanoseconds on the elapsedRealtime base.
        val tMs = event.timestamp / 1_000_000

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                _progress.value = stepGate.onStep(tMs)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                _progress.value = imuGate.onSample(
                    tMs,
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble(),
                )
            }
            Sensor.TYPE_PRESSURE -> {
                imuGate.onPressure(tMs, event.values[0].toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "Molasses.Movement"

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
