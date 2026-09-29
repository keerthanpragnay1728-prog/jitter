package dev.molasses.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * SS1.2: the gravity filter's time constant must be a property of the filter,
 * not of whatever rate the device happens to deliver at.
 *
 * `SENSOR_DELAY_GAME` is a hint. Devices deliver ~16-25 ms in practice, some
 * OEMs ignore it under battery saver, and a hardcoded alpha turns that
 * variation directly into a moving corner frequency -- at 200 Hz an alpha of
 * 0.97 puts the corner at 0.98 Hz, back inside the gait band.
 */
class IirFilterTest {

    private val rates = listOf(25, 50, 100, 200)

    /** tau realised from the alpha the filter actually used. */
    private fun realisedTau(rateHz: Int, tau: Double = GravitySplitter.DEFAULT_TAU_SECONDS): Double {
        val s = GravitySplitter(tau)
        val dtNs = (1_000_000_000L / rateHz)
        var t = 0L
        repeat(200) {
            s.update(t, 0.0, 0.0, 9.81)
            t += dtNs
        }
        return GravitySplitter.tauFor(s.lastAlpha, s.lastDtSeconds)
    }

    /**
     * End-to-end check: step response reaches 63.2% of its final value at t =
     * tau. Measured rather than derived from alpha, so an error in the update
     * rule itself would be caught, not just an error in the alpha formula.
     */
    private fun stepResponseTauMs(rateHz: Int): Long {
        val s = GravitySplitter()
        val dtNs = 1_000_000_000L / rateHz
        var t = 0L
        // Settle at 0, then step to 9.81 on the z axis.
        s.update(t, 0.0, 0.0, 0.0)
        t += dtNs
        repeat(rateHz) { s.update(t, 0.0, 0.0, 0.0); t += dtNs }
        val stepStart = t
        val target = 9.81 * 0.632
        repeat(rateHz * 3) {
            s.update(t, 0.0, 0.0, 9.81)
            if (s.gravityZ >= target) return (t - stepStart) / 1_000_000
            t += dtNs
        }
        return -1
    }

    @Test
    fun `realised tau stays within five percent of 650 ms at every rate`() {
        println()
        println("| rate Hz | dt ms | derived alpha | realised tau | error | step-response tau |")
        println("|---|---|---|---|---|---|")
        for (rate in rates) {
            val s = GravitySplitter()
            val dtNs = 1_000_000_000L / rate
            var t = 0L
            repeat(200) { s.update(t, 0.0, 0.0, 9.81); t += dtNs }

            val tau = realisedTau(rate)
            val err = abs(tau - GravitySplitter.DEFAULT_TAU_SECONDS) /
                GravitySplitter.DEFAULT_TAU_SECONDS
            val step = stepResponseTauMs(rate)
            println(
                "| %d | %.1f | %.5f | %.4f s | %.2f%% | %d ms |".format(
                    rate, 1000.0 / rate, s.lastAlpha, tau, err * 100, step,
                ),
            )
            assertTrue(
                "tau at $rate Hz was ${tau}s, ${err * 100}% off 0.65s",
                err <= 0.05,
            )
            assertTrue(
                "step-response tau at $rate Hz was ${step}ms, expected 650 +/- 5%",
                step in 617..683,
            )
        }
    }

    @Test
    fun `a hardcoded alpha would drift with the delivery rate`() {
        // The bug this patch exists to prevent, stated as an assertion so the
        // rationale cannot quietly rot.
        println()
        println("| rate Hz | tau with fixed alpha=0.97 | corner Hz |")
        println("|---|---|---|")
        val observed = rates.map { rate ->
            val tau = GravitySplitter.tauFor(0.97, 1.0 / rate)
            println("| %d | %.3f s | %.2f |".format(rate, tau, GravitySplitter.cornerHz(tau)))
            tau
        }
        // 25 Hz and 200 Hz differ by 8x.
        assertTrue(
            "fixed alpha should drift by ~8x across 25-200 Hz",
            observed.first() / observed.last() > 7.0,
        )
        // And at 200 Hz the corner is back inside the gait band.
        assertTrue(
            "fixed alpha at 200 Hz puts the corner at ${GravitySplitter.cornerHz(observed.last())} Hz",
            GravitySplitter.cornerHz(observed.last()) > 0.9,
        )
        // Whereas the derived alpha holds the corner an order of magnitude
        // below the band at every rate.
        for (rate in rates) {
            assertTrue(GravitySplitter.cornerHz(realisedTau(rate)) < 0.30)
        }
    }

    @Test
    fun `the first sample seeds gravity instead of starting from zero`() {
        val s = GravitySplitter()
        s.update(0, 0.0, 0.0, 9.81)
        // Seeded exactly, and no phantom linear acceleration on frame one.
        assertEquals(9.81, s.gravityZ, 1e-9)
        assertEquals(0.0, s.linearZ, 1e-9)
        assertTrue(s.primed)
    }

    @Test
    fun `starting from zero would take about two seconds to converge`() {
        // Demonstrates what the seeding above avoids: three time constants of
        // garbage, during which the gate's tilt reading and linear
        // acceleration are both badly wrong.
        val s = GravitySplitter()
        var t = 0L
        val dtNs = 20_000_000L
        s.update(t, 0.0, 0.0, 9.81) // primes at 9.81
        // Force the un-seeded case by hand.
        var g = 0.0
        var elapsed = 0L
        while (g < 9.81 * 0.95) {
            val alpha = 0.65 / (0.65 + 0.02)
            g = alpha * g + (1 - alpha) * 9.81
            elapsed += 20
            if (elapsed > 10_000) break
        }
        assertTrue("expected ~2 s to reach 95%, got ${elapsed}ms", elapsed in 1_600..2_400)
        t += dtNs
    }

    @Test
    fun `dt is clamped so a burst or a doze gap cannot swing alpha`() {
        val s = GravitySplitter()
        s.update(0, 0.0, 0.0, 9.81)
        // Batched replay: two samples with a 1 us gap.
        s.update(1_000, 1.0, 1.0, 9.81)
        assertTrue(
            "dt should clamp to ${GravitySplitter.MIN_DT_SECONDS}, was ${s.lastDtSeconds}",
            s.lastDtSeconds >= GravitySplitter.MIN_DT_SECONDS - 1e-9,
        )
        // Doze gap: 30 s.
        s.update(30_001_000_000, 1.0, 1.0, 9.81)
        assertEquals(GravitySplitter.MAX_DT_SECONDS, s.lastDtSeconds, 1e-9)
    }

    @Test
    fun `the analyzer verdict is stable across delivery rates`() {
        // The payoff: identical physical motion must produce the same verdict
        // whether the device delivers at 25 Hz or 200 Hz.
        val verdicts = rates.map { rate ->
            rate to SignalGen.verdictIir(SignalGen.gait(12_000, sampleHz = rate, jitterFrac = 0.08))
        }
        for ((rate, v) in verdicts) {
            assertTrue("$rate Hz gave ${v.reason}", v.ok)
        }
        val rmsValues = verdicts.map { it.second.rms }
        val spread = (rmsValues.max() - rmsValues.min()) / rmsValues.average()
        assertTrue("RMS spread across rates was ${spread * 100}%", spread < 0.05)
    }
}
