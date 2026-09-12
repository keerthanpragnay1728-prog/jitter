package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GATE_EVAL line. Format-tested because a capture from a real walk is only
 * useful if it parses, and the capture is the only way these thresholds get
 * set from real gait rather than from synthetics.
 */
class TickEvaluationTest {

    private fun sample(allPass: Boolean = true) = TickEvaluation(
        tick = 7, tMs = 1750, dtMs = 250,
        path = GateProgress.Path.IMU_IIR, thresholdsId = "IIR",
        rms = 3.2941, peaks = 6, hz = 1.7361, cv = 0.0294,
        verticalShare = 0.9712, peakMagnitude = 10.5543, tiltDegrees = 40.04,
        regularityCv = 0.0531, regularityIntervals = 9,
        tiltSustainedMs = 1400, suppressedPeaks = 0,
        passRms = true, passPeaks = true, passHz = allPass, passCv = true,
        passVertical = true, passPeakCeiling = true, passTilt = true,
        hzLatched = true, rmsLatched = true,
        allPass = allPass, creditMs = 1750.0, requiredMs = 8000,
        reason = if (allPass) GateProgress.Reason.SUSTAINING
        else GateProgress.Reason.CADENCE_TOO_SLOW,
    )

    @Test
    fun `the line carries all seven measurements and all seven verdicts`() {
        val line = sample().logLine()
        println(line)
        for (key in listOf("rms=", "peaks=", "hz=", "cv=", "vert=", "pk=", "tilt=")) {
            assertTrue("missing $key in: $line", line.contains(key))
        }
        assertTrue("missing the verdict flags", line.contains("[RPHCVMT]"))
        assertTrue("missing credit", line.contains("credit=1750/8000"))
    }

    @Test
    fun `a failing test shows as a lowercase flag`() {
        val line = sample(allPass = false).logLine()
        println(line)
        assertTrue("failing hz must be lowercase h: $line", line.contains("[RPhCVMT]"))
        assertTrue(line.contains("pass=0"))
        assertTrue(line.contains("reason=CADENCE_TOO_SLOW"))
    }

    @Test
    fun `field order is stable so a capture can be column-split`() {
        val keys = sample().logLine()
            .split(" ")
            .filter { it.contains('=') }
            .map { it.substringBefore('=') }
        assertEquals(
            listOf(
                "t", "ms", "dt", "path", "thr", "rms", "peaks", "hz", "cv", "rcv", "rn",
                "vert", "pk", "tilt", "tiltms", "sup", "latch", "pass", "credit", "reason",
            ),
            keys,
        )
    }

    @Test
    fun `a NaN reads as nan rather than as a number`() {
        // Regularity CV is NaN until enough intervals exist, and a capture that
        // printed it as 0.000 would look like a metronome.
        val line = sample().copy(regularityCv = Double.NaN).logLine()
        assertTrue(line, line.contains("rcv=nan"))
    }

    @Test
    fun `the latch state is visible so hysteresis can be read off a capture`() {
        assertTrue(sample().logLine().contains("latch=HR"))
        assertTrue(
            sample().copy(hzLatched = false, rmsLatched = false).logLine().contains("latch=--"),
        )
    }

    @Test
    fun `one line per tick with no embedded newline`() {
        assertEquals(1, sample().logLine().lines().size)
    }
}
