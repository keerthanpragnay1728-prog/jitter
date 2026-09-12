package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import kotlin.math.sqrt

/**
 * Path A: `TYPE_STEP_DETECTOR`. Pure -- fed timestamps by [MovementDetector].
 *
 * Pass condition (SS8):
 *  - >= 12 step events within a 45 s window
 *  - inter-step intervals in [280 ms, 900 ms]  (0.9-2.2 Hz)
 *  - coefficient of variation of intervals < 0.35
 *
 * The CV test is the anti-cheat. Several OEM step fusions will happily emit
 * step events for a phone being shaken, but the intervals come out erratic;
 * real gait is metronomic. Rejecting on CV costs nothing for a genuine walker
 * and defeats the obvious bypass.
 */
class StepGate(
    private val requiredSteps: Int = REQUIRED_STEPS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val stepsMs = ArrayDeque<Long>()

    fun reset() = stepsMs.clear()

    fun onStep(elapsedMs: Long): GateEvaluation {
        stepsMs.addLast(elapsedMs)
        prune(elapsedMs)
        return evaluate(elapsedMs)
    }

    fun evaluate(nowMs: Long): GateEvaluation {
        prune(nowMs)
        val n = stepsMs.size
        val fraction = (n.toFloat() / requiredSteps).coerceIn(0f, 1f)

        if (n < 2) {
            return GateEvaluation(
                progress = GateProgress(
                    fraction = fraction,
                    reason = if (n == 0) GateProgress.Reason.WAITING_TO_START
                    else GateProgress.Reason.NEED_MORE_STEPS,
                    path = GateProgress.Path.STEP_DETECTOR,
                    events = n,
                ),
                passed = false,
            )
        }

        val intervals = DoubleArray(n - 1)
        val list = stepsMs.toList()
        for (i in 1 until n) intervals[i - 1] = (list[i] - list[i - 1]).toDouble()

        val mean = intervals.average()
        val sd = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
        val cv = if (mean > 0) sd / mean else Double.MAX_VALUE

        val reason = when {
            mean > MAX_INTERVAL_MS -> GateProgress.Reason.CADENCE_TOO_SLOW
            mean < MIN_INTERVAL_MS -> GateProgress.Reason.CADENCE_TOO_FAST
            cv >= MAX_CV -> GateProgress.Reason.CADENCE_IRREGULAR
            n < requiredSteps -> GateProgress.Reason.NEED_MORE_STEPS
            else -> GateProgress.Reason.PASSED
        }

        return GateEvaluation(
            progress = GateProgress(
                fraction = fraction,
                reason = reason,
                path = GateProgress.Path.STEP_DETECTOR,
                events = n,
            ),
            passed = reason == GateProgress.Reason.PASSED,
        )
    }

    private fun prune(nowMs: Long) {
        while (stepsMs.isNotEmpty() && nowMs - stepsMs.first() > windowMs) {
            stepsMs.removeFirst()
        }
    }

    companion object {
        const val REQUIRED_STEPS = 12
        const val WINDOW_MS = 45_000L
        const val MIN_INTERVAL_MS = 280.0
        const val MAX_INTERVAL_MS = 900.0
        const val MAX_CV = 0.35
    }
}
