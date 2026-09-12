package dev.molasses.core.stats

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SS3: does the CV estimate at the `MIN_PEAKS` floor support a one-sided lower
 * bound at all?
 *
 * A CV *floor* is a different proposition from a CV ceiling. A ceiling is
 * forgiving of a noisy estimator -- an occasional over-estimate rejects a
 * cheater who would have been rejected anyway. A floor rejects the *most
 * regular* samples, and a small-n estimator produces spuriously small spreads
 * regularly. So the question is not "is the estimator unbiased" (Bessel fixes
 * that) but "how often does it land under 0.02 for a genuine walker".
 */
class CvEstimatorTest {

    /** Intervals from a walker with a known true CV. */
    private fun intervals(n: Int, meanMs: Double, trueCv: Double, rnd: Random): DoubleArray {
        val sd = meanMs * trueCv
        return DoubleArray(n) { meanMs + gaussian(rnd) * sd }
    }

    private fun gaussian(rnd: Random): Double {
        var u: Double
        do { u = rnd.nextDouble() } while (u <= 1e-12)
        val v = rnd.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    /**
     * `c4(n) = sqrt(2/(n-1)) * gamma(n/2) / gamma((n-1)/2)`: the factor by
     * which the Bessel-corrected standard deviation still under-reports sigma
     * at sample size n. Tabulated rather than computed, since Kotlin has no
     * lgamma and an approximation here would be another thing to verify.
     */
    private val C4 = mapOf(3 to 0.88623, 4 to 0.92132, 5 to 0.93999, 8 to 0.96503)

    private data class Stats(
        val mean: Double,
        val p05: Double,
        val p50: Double,
        val p95: Double,
        val belowFloor: Double,
    )

    private fun sweep(n: Int, trueCv: Double, trials: Int = 100_000): Stats {
        val rnd = Random(20260912 + n)
        val out = DoubleArray(trials) { Cv.sample(intervals(n, 550.0, trueCv, rnd)) }
        out.sort()
        fun pct(p: Double) = out[((trials - 1) * p).toInt()]
        return Stats(
            mean = out.average(),
            p05 = pct(0.05),
            p50 = pct(0.50),
            p95 = pct(0.95),
            belowFloor = out.count { it < 0.02 }.toDouble() / trials,
        )
    }

    @Test
    fun `Bessel removes the small-sample bias that the population estimator has`() {
        println()
        println("| n intervals | population CV | sample CV (n-1) | ratio | exact sqrt((n-1)/n) |")
        println("|---|---|---|---|---|")
        for (n in listOf(3, 4, 5, 8, 20)) {
            val rnd = Random(7)
            var pop = 0.0
            var samp = 0.0
            val trials = 50_000
            repeat(trials) {
                val iv = intervals(n, 550.0, 0.06, rnd)
                pop += Cv.population(iv)
                samp += Cv.sample(iv)
            }
            pop /= trials; samp /= trials
            println(
                "| %d | %.4f | %.4f | %.3f | %.3f |".format(
                    n, pop, samp, pop / samp, Cv.populationBiasFactor(n),
                ),
            )
            assertTrue("population must under-report at n=$n", pop < samp)
        }
        // At n = 3 the population estimator is ~18% low, which is the size of
        // the gap between a real walker at 0.06 and the 0.02 floor.
        assertTrue(Cv.populationBiasFactor(3) < 0.83)
    }

    @Test
    fun `the n equals 3 estimate straddles the floor for a real walker`() {
        println()
        println("### CV estimate distribution, true CV = 0.06, mean interval 550 ms")
        println()
        println("| n intervals | peaks needed | mean | p05 | p50 | p95 | P(estimate < 0.02) |")
        println("|---|---|---|---|---|---|---|")
        val results = listOf(3, 4, 5, 8).associateWith { sweep(it, 0.06) }
        for ((n, s) in results) {
            println(
                "| %d | %d | %.4f | %.4f | %.4f | %.4f | **%.1f%%** |".format(
                    n, n + 1, s.mean, s.p05, s.p50, s.p95, s.belowFloor * 100,
                ),
            )
        }

        val n3 = results.getValue(3)
        val n8 = results.getValue(8)

        assertTrue(
            "n=3 should straddle the floor materially; P(<0.02) was ${n3.belowFloor}",
            n3.belowFloor > 0.05,
        )
        assertTrue(
            "n=8 should be comfortably clear of it; P(<0.02) was ${n8.belowFloor}",
            n8.belowFloor < 0.01,
        )
        // Bessel makes the *variance* unbiased, not the standard deviation:
        // E[s] = sigma * c4(n). So the CV estimate still reads low at small n,
        // by exactly c4 -- 11.4% at 3 intervals, 3.5% at 8. Asserting against
        // c4 rather than against a loose tolerance keeps this honest about
        // what the correction actually buys.
        for ((n, s) in results) {
            val expected = 0.06 * C4.getValue(n)
            assertTrue(
                "n=$n mean was ${s.mean}, expected sigma*c4 = $expected",
                kotlin.math.abs(s.mean - expected) < 0.0015,
            )
        }
        assertTrue(
            "residual SD bias at n=3 should be ~11%",
            kotlin.math.abs((1 - n3.mean / 0.06) - 0.114) < 0.02,
        )
    }

    @Test
    fun `a genuinely metronomic source is rejected at every sample size`() {
        println()
        println("| n intervals | true CV 0.005 -> P(estimate < 0.02) |")
        println("|---|---|")
        for (n in listOf(3, 4, 5, 8)) {
            val s = sweep(n, 0.005)
            println("| %d | %.1f%% |".format(n, s.belowFloor * 100))
            assertTrue("floor must catch a metronome at n=$n", s.belowFloor > 0.90)
        }
    }

    @Test
    fun `degenerate inputs do not produce a spurious verdict`() {
        assertTrue(Cv.sample(doubleArrayOf(500.0)).isNaN())
        assertTrue(Cv.sample(doubleArrayOf()).isNaN())
        assertTrue(Cv.sample(doubleArrayOf(0.0, 0.0)).isNaN())
    }
}
