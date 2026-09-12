package dev.molasses.sensing

import dev.molasses.core.model.GateProgress

/**
 * One evaluation tick, with every measured value, every per-test verdict and
 * the accumulator credit. Pure, so [logLine] is unit-testable.
 *
 * This is the GATE_EVAL payload. The thresholds in this app were derived from
 * synthetic gait that the first device session showed to be unrepresentative,
 * so the only way to set them properly is to log what real walking actually
 * measures. One line per tick, every field, no sampling.
 */
data class TickEvaluation(
    val tick: Int,
    val tMs: Long,
    val dtMs: Long,
    val path: GateProgress.Path,
    val thresholdsId: String,

    // The seven measurements.
    val rms: Double,
    val peaks: Int,
    val hz: Double,
    val cv: Double,
    val verticalShare: Double,
    val peakMagnitude: Double,
    val tiltDegrees: Double,

    // Supporting measurements that explain a verdict.
    val regularityCv: Double,
    val regularityIntervals: Int,
    val tiltSustainedMs: Long,
    val suppressedPeaks: Int,

    // The seven verdicts, after hysteresis.
    val passRms: Boolean,
    val passPeaks: Boolean,
    val passHz: Boolean,
    val passCv: Boolean,
    val passVertical: Boolean,
    val passPeakCeiling: Boolean,
    val passTilt: Boolean,

    /** True while the cadence Schmitt trigger is latched inside the band. */
    val hzLatched: Boolean,
    /** True while the RMS Schmitt trigger is latched above the floor. */
    val rmsLatched: Boolean,

    val allPass: Boolean,
    val creditMs: Double,
    val requiredMs: Long,
    val reason: GateProgress.Reason,
) {
    /**
     * One fixed-width line. Field order never changes, so a capture can be
     * parsed with a fixed column split rather than by guessing.
     */
    fun logLine(): String = buildString {
        append("t=").append(tick)
        append(" ms=").append(tMs)
        append(" dt=").append(dtMs)
        append(" path=").append(path.name)
        append(" thr=").append(thresholdsId)
        append(" rms=").append(f2(rms))
        append(" peaks=").append(peaks)
        append(" hz=").append(f2(hz))
        append(" cv=").append(f3(cv))
        append(" rcv=").append(f3(regularityCv))
        append(" rn=").append(regularityIntervals)
        append(" vert=").append(f2(verticalShare))
        append(" pk=").append(f2(peakMagnitude))
        append(" tilt=").append(f1(tiltDegrees))
        append(" tiltms=").append(tiltSustainedMs)
        append(" sup=").append(suppressedPeaks)
        append(" [")
        append(if (passRms) 'R' else 'r')
        append(if (passPeaks) 'P' else 'p')
        append(if (passHz) 'H' else 'h')
        append(if (passCv) 'C' else 'c')
        append(if (passVertical) 'V' else 'v')
        append(if (passPeakCeiling) 'M' else 'm')
        append(if (passTilt) 'T' else 't')
        append("]")
        append(" latch=").append(if (hzLatched) 'H' else '-').append(if (rmsLatched) 'R' else '-')
        append(" pass=").append(if (allPass) 1 else 0)
        append(" credit=").append(creditMs.toLong()).append('/').append(requiredMs)
        append(" reason=").append(reason.name)
    }

    private fun f1(v: Double) = if (v.isNaN()) "nan" else "%.1f".format(v)
    private fun f2(v: Double) = if (v.isNaN()) "nan" else "%.2f".format(v)
    private fun f3(v: Double) = if (v.isNaN()) "nan" else "%.3f".format(v)

    companion object {
        /**
         * Uppercase means the test passed on this tick. The letters are in the
         * same order as the fields above: Rms, Peaks, Hz, Cv, Vertical,
         * Magnitude ceiling, Tilt.
         */
        const val FLAG_LEGEND = "RPHCVMT, uppercase passed"
    }
}
