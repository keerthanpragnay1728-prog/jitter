package dev.molasses.core.stats

import kotlin.math.sqrt

/**
 * Coefficient of variation, separated out because the choice of estimator
 * materially changes the verdict at the sample sizes this app actually has.
 *
 * At `MIN_PEAKS = 4` there are n = 3 intervals. The population estimator
 * (divide by n) is biased low for small n -- it measures the spread of the
 * sample about its own mean, not the spread of the process the sample came
 * from. At n = 3 that bias is around 18%, which pushes real walkers down
 * toward a CV floor and manufactures false "too regular" rejections.
 *
 * Bessel's correction (divide by n-1) removes the bias in the *variance*.
 *
 * Note what that does and does not buy. It does not make the standard
 * deviation unbiased: `E[s] = sigma * c4(n)`, and `c4(3) = 0.886`, so a CV
 * estimated from 3 intervals still reads about 11% low even with the
 * correction. Measured in `CvEstimatorTest`: a walker with a true CV of 0.06
 * estimates at 0.0531 on 3 intervals and 0.0580 on 8.
 *
 * Nor does it make the estimate *stable*, which is the property a one-sided
 * floor actually needs. Both facts are why the floor is measured over a long
 * baseline rather than the analysis window -- see
 * [dev.molasses.sensing.Thresholds.regularityWindowMs].
 */
object Cv {

    /** Bessel-corrected (n-1). The production estimator. */
    fun sample(values: DoubleArray): Double {
        if (values.size < 2) return Double.NaN
        val mean = values.average()
        if (mean == 0.0) return Double.NaN
        val ss = values.sumOf { (it - mean) * (it - mean) }
        val sd = sqrt(ss / (values.size - 1))
        return sd / mean
    }

    /** Population (n). Retained only so `CvEstimatorTest` can show the bias. */
    fun population(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val mean = values.average()
        if (mean == 0.0) return Double.NaN
        val ss = values.sumOf { (it - mean) * (it - mean) }
        val sd = sqrt(ss / values.size)
        return sd / mean
    }

    /**
     * Ratio by which [population] under-reports [sample] for a given n.
     * `sqrt((n-1)/n)` is the exact factor between the two standard deviations.
     */
    fun populationBiasFactor(n: Int): Double =
        if (n < 2) Double.NaN else sqrt((n - 1.0) / n)
}
