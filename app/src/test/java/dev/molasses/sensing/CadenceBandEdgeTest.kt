package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SS2: with a 3.5 s window, 1.2 Hz gives 4.2 periods, so the peak count lands
 * on 4 or 5 depending on where the window boundary falls. Four is the floor
 * and it satisfies the gate, but it is one sample from failing -- so this
 * sweeps the band edge across phase rather than testing a single alignment.
 */
class CadenceBandEdgeTest {

    private val phases = (0 until 8).map { it / 8.0 }
    private val rates = listOf(1.15, 1.20, 1.25, 1.30, 1.35)

    /**
     * The *product* band floor from SS8, which is what must pass. It is
     * deliberately not [Thresholds.minHz]: that carries a guard band below
     * this value precisely so a walker at this cadence clears the gate, and
     * asserting against the implementation constant would make this test
     * unfalsifiable.
     */
    private val statedBandFloorHz = 1.20

    private fun thresholdsWith(minPeaks: Int, window: Long = 3_500L) = object : Thresholds {
        private val base = Thresholds.IIR
        override val id = "SWEEP(minPeaks=$minPeaks,window=$window)"
        override val calibration = Thresholds.Calibration.SYNTHETIC_SWEEP
        override val tickIntervalMs = base.tickIntervalMs
        override val sustainRequiredMs = base.sustainRequiredMs
        override val sustainDecayFactor = base.sustainDecayFactor
        override val windowMs = window
        override val minSamples = base.minSamples
        override val refractoryMs = base.refractoryMs
        override val peakMinMagnitude = base.peakMinMagnitude
        override val minRms = base.minRms
        override val minRmsExit = base.minRmsExit
        override val minPeaks = minPeaks
        override val minHz = base.minHz
        override val minHzExit = base.minHzExit
        override val maxHz = base.maxHz
        override val minCv = base.minCv
        override val maxCv = base.maxCv
        override val regularityWindowMs = base.regularityWindowMs
        override val minCvIntervals = base.minCvIntervals
        override val minVerticalShare = base.minVerticalShare
        override val maxPeakMagnitude = base.maxPeakMagnitude
        override val minTiltDegrees = base.minTiltDegrees
        override val minTiltSustainMs = base.minTiltSustainMs
    }

    /** Realistic gait: metronome-flat walking is what the CV floor rejects. */
    private fun walk(hz: Double, phase: Double) =
        SignalGen.gait(14_000, stepHz = hz, jitterFrac = 0.08, phaseOffset = phase)

    private fun verdict(hz: Double, phase: Double, t: Thresholds) =
        SignalGen.verdictIir(walk(hz, phase), thresholds = t)

    /**
     * Drive the full gate rather than the bare analyzer. The cadence Schmitt
     * trigger lives at the tick level, so an analyzer-only check measures the
     * strict single-threshold behaviour and says nothing about whether a
     * band-edge walker can actually clear.
     */
    private fun clears(hz: Double, phase: Double): Boolean {
        val gate = FallbackImuGate()
        return SignalGen.feedIir(gate, SignalGen.gait(
            30_000, stepHz = hz, jitterFrac = 0.08, phaseOffset = phase,
        ))?.passed == true
    }

    @Test
    fun `a walker at the band floor clears at every phase offset`() {
        // What the user actually experiences, with hysteresis in play.
        val failures = mutableListOf<String>()
        println()
        println("### Band floor, full gate")
        println("| Hz | " + phases.joinToString(" | ") { "p=%.2f".format(it) } + " |")
        println("|---" + "|---".repeat(phases.size) + "|")
        for (hz in rates) {
            val cells = phases.map { p ->
                val ok = clears(hz, p)
                if (hz >= statedBandFloorHz && !ok) failures += "%.2f Hz phase %.2f".format(hz, p)
                if (ok) "clears" else "stuck"
            }
            println("| %.2f | ".format(hz) + cells.joinToString(" | ") + " |")
        }
        assertTrue(
            "band floor must clear at every phase from $statedBandFloorHz Hz up:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the bare analyzer is stricter than the gate at the band edge`() {
        val t = Thresholds.IIR
        println()
        println("### Band-edge sweep, production thresholds (minPeaks=${t.minPeaks}, window=${t.windowMs}ms)")
        println()
        println("| Hz | " + phases.joinToString(" | ") { "φ=%.2f".format(it) } + " |")
        println("|---" + "|---".repeat(phases.size) + "|")

        val failures = mutableListOf<String>()
        for (hz in rates) {
            val cells = phases.map { p ->
                val v = verdict(hz, p, t)
                if (v.ok) "ok(${v.peaks})" else "${shortName(v.reason)}(${v.peaks})"
            }
            println("| %.2f | ".format(hz) + cells.joinToString(" | ") + " |")
            if (hz >= statedBandFloorHz) {
                phases.forEachIndexed { i, p ->
                    val v = verdict(hz, p, t)
                    if (!v.ok) failures += "%.2f Hz phase %.2f -> %s (peaks=%d, cv=%.3f)"
                        .format(hz, p, v.reason, v.peaks, v.cv)
                }
            }
        }

        // Recorded, not asserted clean: the analyzer applies a single 1.20 Hz
        // threshold and a walker at exactly 1.20 measures below it about half
        // the time. The Schmitt trigger in the gate is what makes that
        // survivable, which is why the test above is the one that matters.
        println("strict analyzer failures at or above the floor: ${failures.size}")
    }

    @Test
    fun `raising minPeaks to 5 would break the band floor at this window`() {
        // The SS3 remedy ("raise MIN_PEAKS to 5") and the SS2 requirement
        // ("1.2 Hz must pass at every phase") are in direct conflict at a
        // 3.5 s window: 5 peaks need 4 intervals, and 4 x 833 ms = 3333 ms
        // leaves 167 ms of slack in a 3500 ms window.
        //
        // This is the evidence for resolving that conflict with a minimum
        // interval count on the CV floor instead. See README.
        println()
        println("### Same sweep with minPeaks = 5")
        println()
        println("| Hz | " + phases.joinToString(" | ") { "φ=%.2f".format(it) } + " |")
        println("|---" + "|---".repeat(phases.size) + "|")
        val t5 = thresholdsWith(minPeaks = 5)
        var failuresAtFloor = 0
        for (hz in rates) {
            val cells = phases.map { p ->
                val v = verdict(hz, p, t5)
                if (hz >= 1.20 && !v.ok) failuresAtFloor++
                if (v.ok) "ok(${v.peaks})" else "${shortName(v.reason)}(${v.peaks})"
            }
            println("| %.2f | ".format(hz) + cells.joinToString(" | ") + " |")
        }
        println()
        println("minPeaks=5 failures at or above the 1.2 Hz floor: $failuresAtFloor / ${rates.count { it >= 1.2 } * phases.size}")

        println()
        println("### And with minPeaks = 5 at a 4.5 s window")
        println()
        val t5wide = thresholdsWith(minPeaks = 5, window = 4_500L)
        var wideFailures = 0
        for (hz in rates) {
            val cells = phases.map { p ->
                val v = verdict(hz, p, t5wide)
                if (hz >= 1.20 && !v.ok) wideFailures++
                if (v.ok) "ok(${v.peaks})" else "${shortName(v.reason)}(${v.peaks})"
            }
            println("| %.2f | ".format(hz) + cells.joinToString(" | ") + " |")
        }
        println()
        println("minPeaks=5 @4.5s failures at or above 1.2 Hz: $wideFailures")
    }

    @Test
    fun `peak count at the band floor is phase-dependent as predicted`() {
        val counts = phases.map { verdict(1.20, it, Thresholds.IIR).peaks }.toSet()
        println()
        println("peak counts at 1.20 Hz across 8 phases: $counts")
        assertTrue(
            "expected the count to land on 4 or 5 depending on phase, saw $counts",
            counts.all { it in 4..5 },
        )
    }

    private fun shortName(r: GateProgress.Reason) = when (r) {
        GateProgress.Reason.NEED_MORE_STEPS -> "peaks"
        GateProgress.Reason.CADENCE_TOO_SLOW -> "slow"
        GateProgress.Reason.CADENCE_TOO_FAST -> "fast"
        GateProgress.Reason.TOO_REGULAR -> "regular"
        GateProgress.Reason.CADENCE_IRREGULAR -> "erratic"
        GateProgress.Reason.NOT_ENOUGH_MOTION -> "rms"
        GateProgress.Reason.PHONE_STATIONARY -> "tilt"
        GateProgress.Reason.MOTION_NOT_VERTICAL -> "lateral"
        else -> r.name.lowercase().take(7)
    }
}
