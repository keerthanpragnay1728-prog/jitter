package dev.molasses.sensing

import dev.molasses.legacy.LegacyCadenceAnalyzer
import org.junit.Test

/**
 * The SS4 recalibration evidence.
 *
 * Removing the old gravity filter's mid-band attenuation invalidates the
 * calibration of every threshold that was measured through it, so this sweeps
 * all seven tests across every signal class under both pipelines and prints a
 * markdown table. It asserts nothing -- it is the measurement the thresholds
 * are chosen from, and a test that asserted the numbers it produced would be
 * circular.
 *
 * "old" is the frozen [LegacyCadenceAnalyzer] (alpha = 0.8 fixed, 3.0 s
 * window, population CV) -- literally the shipped code. "new" is the current
 * [CadenceAnalyzer] via [GravitySplitter] at tau = 0.65 s with a 3.5 s window
 * and Bessel-corrected CV.
 */
class CalibrationSweep {

    private data class Signal(val name: String, val samples: List<SignalGen.Sample>)

    /**
     * Note the jitter on the walk signals. Before the CV floor existed, a
     * zero-jitter synthetic was a fine model of a walker; now it is a cheat
     * class in its own right ("metronome" below), because no human gait is
     * that regular. Every "walk" entry therefore carries realistic jitter.
     */
    private val signals = listOf(
        Signal("walk A=12", SignalGen.gait(12_000, peakA = 12.0, jitterFrac = 0.08)),
        Signal("walk A=6", SignalGen.gait(12_000, peakA = 6.0, jitterFrac = 0.08)),
        Signal("walk A=4", SignalGen.gait(12_000, peakA = 4.0, jitterFrac = 0.08)),
        Signal("walk jitter .25", SignalGen.gait(12_000, jitterFrac = 0.25)),
        Signal("metronome (j=0)", SignalGen.gait(12_000, peakA = 12.0, jitterFrac = 0.0)),
        Signal("desk tap", SignalGen.deskTap(12_000)),
        Signal("lateral wave", SignalGen.lateralWave(12_000)),
        Signal("fast shake", SignalGen.fastShake(12_000)),
        Signal("violent shake", SignalGen.violentShake(12_000)),
        Signal("thumb tremor", SignalGen.thumbTremor(12_000)),
        Signal("static", SignalGen.static(12_000)),
    )

    /** Legacy pipeline: raw samples straight into the frozen analyzer. */
    private fun legacy(samples: List<SignalGen.Sample>): LegacyCadenceAnalyzer.Verdict {
        val a = LegacyCadenceAnalyzer()
        var v: LegacyCadenceAnalyzer.Verdict? = null
        for (s in samples) v = a.onSample(s.tMs, s.x, s.y, s.z)
        return v!!
    }

    @Test
    fun sweep() {
        println()
        println("### Per-signal measurements: old pipeline vs new")
        println()
        println(
            "| signal | pipe | rms | peaks | hz | cv | vert | peak\\|a\\| | tilt | verdict |",
        )
        println("|---|---|---|---|---|---|---|---|---|---|")
        for (sig in signals) {
            val o = legacy(sig.samples)
            val n = SignalGen.verdictIir(sig.samples)
            println(
                "| %s | old | %.2f | %d | %.2f | %s | %.2f | %.2f | %.1f | %s |".format(
                    sig.name, o.rms, o.peaks, o.dominantHz, fmtCv(o.cv),
                    o.verticalShare, o.peakMagnitude, o.tiltDegrees, o.reason,
                ),
            )
            println(
                "| | **new** | **%.2f** | **%d** | **%.2f** | **%s** | **%.2f** | **%.2f** | **%.1f** | **%s** |".format(
                    n.rms, n.peaks, n.dominantHz, fmtCv(n.cv),
                    n.verticalShare, n.peakMagnitude, n.tiltDegrees, n.reason,
                ),
            )
        }

        println()
        println("### Passband retention (input peak -> measured peak |a|)")
        println()
        println("| input peakA | old measured | old retention | new measured | new retention |")
        println("|---|---|---|---|---|")
        for (peakA in listOf(4.0, 6.0, 8.0, 12.0, 20.0)) {
            val s = SignalGen.gait(12_000, peakA = peakA, jitterFrac = 0.08)
            val o = legacy(s).peakMagnitude
            val n = SignalGen.verdictIir(s).peakMagnitude
            println(
                "| %.1f | %.2f | %.0f%% | %.2f | %.0f%% |".format(
                    peakA, o, 100 * o / peakA, n, 100 * n / peakA,
                ),
            )
        }

        println()
        println("### RMS floor sweep (new pipeline, walk at 1.8 Hz)")
        println()
        println("| input peakA | new rms | old floor 1.2 | chosen 1.5 | suggested 2.2 | 2.6 |")
        println("|---|---|---|---|---|---|")
        for (peakA in listOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0)) {
            val v = SignalGen.verdictIir(SignalGen.gait(12_000, peakA = peakA, jitterFrac = 0.08))
            println(
                "| %.1f | %.2f | %s | %s | %s | %s |".format(
                    peakA, v.rms,
                    yn(v.rms > 1.2), yn(v.rms > 1.5), yn(v.rms > 2.2), yn(v.rms > 2.6),
                ),
            )
        }

        println()
        println("### Cheat-class RMS headroom against candidate floors (new pipeline)")
        println()
        println("| signal | new rms |")
        println("|---|---|")
        for (sig in signals) {
            println("| %s | %.2f |".format(sig.name, SignalGen.verdictIir(sig.samples).rms))
        }

        println()
        println("### Fused path (perfect gravity), same signals")
        println()
        println("| signal | rms | peaks | hz | cv | vert | peak\\|a\\| | verdict |")
        println("|---|---|---|---|---|---|---|---|")
        for (sig in signals) {
            val f = SignalGen.verdictFused(sig.samples)
            println(
                "| %s | %.2f | %d | %.2f | %s | %.2f | %.2f | %s |".format(
                    sig.name, f.rms, f.peaks, f.dominantHz, fmtCv(f.cv),
                    f.verticalShare, f.peakMagnitude, f.reason,
                ),
            )
        }

        println()
        println("### Gravity-angle tracking: tilt reported after a 40 deg change")
        println()
        println("| pipeline | tilt at 1 s | tilt at 2 s | tilt at 3 s | tilt at 6 s |")
        println("|---|---|---|---|---|")
        for ((label, tau) in listOf("old (tau=80ms)" to 0.08, "new (tau=650ms)" to 0.65)) {
            val tilts = listOf(1_000L, 2_000L, 3_000L, 6_000L).map { at ->
                tiltAt(SignalGen.gait(12_000, tiltDeg = 40.0, tiltRampMs = 400), tau, at)
            }
            println(
                "| %s | %.1f | %.1f | %.1f | %.1f |".format(
                    label, tilts[0], tilts[1], tilts[2], tilts[3],
                ),
            )
        }

        println()
        println("### Jitter -> CV: what the TOO_REGULAR floor actually rejects")
        println()
        println("| jitterFrac | cv new (n-1) | cv old (n) | verdict |")
        println("|---|---|---|---|")
        for (j in listOf(0.0, 0.02, 0.05, 0.08, 0.10, 0.15, 0.20, 0.25, 0.40, 0.60, 1.0, 1.6)) {
            val sig = SignalGen.gait(12_000, jitterFrac = j)
            val n = SignalGen.verdictIir(sig)
            val o = legacy(sig)
            println("| %.2f | %s | %s | %s |".format(j, fmtCv(n.cv), fmtCv(o.cv), n.reason))
        }

        println()
        println("### Gravity-angle convergence: ms until reported tilt exceeds 25 deg")
        println()
        println("| true tilt | old (tau=80ms) | new (tau=650ms) |")
        println("|---|---|---|")
        for (trueTilt in listOf(26.0, 28.0, 30.0, 35.0, 40.0, 60.0)) {
            val sig = SignalGen.gait(12_000, tiltDeg = trueTilt, tiltRampMs = 400)
            println(
                "| %.0f | %s | %s |".format(
                    trueTilt, msOrNever(crossMs(sig, 0.08)), msOrNever(crossMs(sig, 0.65)),
                ),
            )
        }

        println()
        println("### Refractory aliasing: shaking near 1/REFRACTORY (4 Hz)")
        println()
        println("| shake Hz | jitter | IIR hz | IIR sup/pk | IIR verdict | FUSED hz | FUSED sup/pk | FUSED verdict |")
        println("|---|---|---|---|---|---|---|---|")
        for (hz in listOf(3.0, 3.5, 4.0, 4.5, 5.0, 6.0)) {
            for (j in listOf(0.0, 0.15)) {
                val v = SignalGen.verdictIir(
                    SignalGen.gait(
                        12_000, stepHz = hz, peakA = 10.0, jitterFrac = j,
                        tiltDeg = 45.0, burstWidthMs = 100.0,
                    ),
                )
                val fu = SignalGen.verdictFused(
                    SignalGen.gait(
                        12_000, stepHz = hz, peakA = 10.0, jitterFrac = j,
                        tiltDeg = 45.0, burstWidthMs = 100.0,
                    ),
                )
                println(
                    "| %.1f | %.2f | %.2f | %d/%d | %s | %.2f | %d/%d | %s |".format(
                        hz, j, v.dominantHz, v.suppressedPeaks, v.peaks, v.reason,
                        fu.dominantHz, fu.suppressedPeaks, fu.peaks, fu.reason,
                    ),
                )
            }
        }

        println()
        println("### Aliasing guard headroom: suppressed/accepted for real gait")
        println()
        println("| signal | IIR sup/pk | ratio | FUSED sup/pk | ratio |")
        println("|---|---|---|---|---|")
        for (sig in signals) {
            val v = SignalGen.verdictIir(sig.samples)
            val f = SignalGen.verdictFused(sig.samples)
            println(
                "| %s | %d/%d | %s | %d/%d | %s |".format(
                    sig.name, v.suppressedPeaks, v.peaks, ratio(v.suppressedPeaks, v.peaks),
                    f.suppressedPeaks, f.peaks, ratio(f.suppressedPeaks, f.peaks),
                ),
            )
        }

        println()
        println("### Sample-rate independence (walk A=12, jitter .08)")
        println()
        println("| rate Hz | rms | peaks | hz | cv | peak | verdict |")
        println("|---|---|---|---|---|---|---|")
        for (rate in listOf(25, 50, 100, 200)) {
            val v = SignalGen.verdictIir(SignalGen.gait(12_000, sampleHz = rate, jitterFrac = 0.08))
            println(
                "| %d | %.2f | %d | %.2f | %s | %.2f | %s |".format(
                    rate, v.rms, v.peaks, v.dominantHz, fmtCv(v.cv), v.peakMagnitude, v.reason,
                ),
            )
        }
    }

    /** First moment the reported tilt exceeds the tilt threshold, or null. */
    private fun crossMs(samples: List<SignalGen.Sample>, tau: Double): Long? {
        val analyzer = CadenceAnalyzer(Thresholds.IIR)
        val splitter = GravitySplitter(tau)
        for (s in samples) {
            splitter.update(s.timestampNs, s.x, s.y, s.z)
            if (!splitter.primed) continue
            val v = analyzer.onSample(
                s.tMs,
                splitter.linearX, splitter.linearY, splitter.linearZ,
                splitter.gravityX, splitter.gravityY, splitter.gravityZ,
            )
            if (v.tiltDegrees > Thresholds.IIR.minTiltDegrees) return s.tMs
        }
        return null
    }

    private fun ratio(sup: Int, pk: Int) = if (pk == 0) "--" else "%.2f".format(sup.toDouble() / pk)

    private fun msOrNever(ms: Long?) = ms?.let { "$it ms" } ?: "never"


    /** Tilt the analyzer reports at [atMs], under a given gravity time constant. */
    private fun tiltAt(samples: List<SignalGen.Sample>, tau: Double, atMs: Long): Double {
        val analyzer = CadenceAnalyzer(Thresholds.IIR)
        val splitter = GravitySplitter(tau)
        var last = 0.0
        for (s in samples) {
            if (s.tMs > atMs) break
            splitter.update(s.timestampNs, s.x, s.y, s.z)
            if (!splitter.primed) continue
            last = analyzer.onSample(
                s.tMs,
                splitter.linearX, splitter.linearY, splitter.linearZ,
                splitter.gravityX, splitter.gravityY, splitter.gravityZ,
            ).tiltDegrees
        }
        return last
    }

    private fun fmtCv(cv: Double) = if (cv.isNaN() || cv > 9) "--" else "%.3f".format(cv)
    private fun yn(b: Boolean) = if (b) "yes" else "no"
}
