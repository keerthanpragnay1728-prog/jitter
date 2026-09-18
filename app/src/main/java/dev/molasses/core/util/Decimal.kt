package dev.molasses.core.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * How a computed number is written down.
 *
 * ## Why this is not `toString()`
 * Two reasons, and the second is the one that surprised me.
 *
 * `0.1 + 0.2` is `0.30000000000000004` in every language with IEEE doubles. A
 * calculator that prints it has told the truth about its arithmetic and lied
 * about the question: the binary representation is a detail of the machine,
 * not an answer to "what is 0.1 plus 0.2".
 *
 * And `Double.toString` switches to scientific notation at 1e7, so plain
 * `123456789.0` renders as `1.23456789E8`. For a calculator that is worse
 * than the rounding problem, because it happens to ordinary numbers a person
 * actually types.
 *
 * So results are rounded to [SIGNIFICANT] significant digits and then
 * rendered digit by digit through [plain], and the app does not claim more
 * precision than that anywhere.
 *
 * ## Why not `BigDecimal`
 * It is the correct answer to a question nobody asks on a phone. Doubles hold
 * integers exactly to about nine quadrillion, which is past anything typed
 * into a launcher, and ten significant digits is more than a two line answer
 * can usefully show. `BigDecimal` would drag `java.math` into the pure module
 * for precision unreachable through this interface.
 *
 * ## Why not `String.format`
 * `"%.10g"` is the obvious tool and it is locale dependent. Without an
 * explicit `Locale` it renders `0,1` on a device set to German or Turkish,
 * which is a number this app would then fail to parse back. An explicit
 * `Locale` means importing `java.util`, and rendering the digits directly is
 * locale independent by construction.
 *
 * ## What it does not promise
 * Ties go away from zero, which is what a person means by rounding, rather
 * than to even, which is what `kotlin.math.round` does and what would show
 * `2.5` as `2`.
 *
 * But a tie at the precision boundary follows the binary value rather than
 * the decimal one that was typed. A literal ending in 5 is usually not
 * exactly representable, and scaling it by a power of ten rounds again, so
 * which side it lands on is a property of two doubles rather than of the
 * digits. `1.005` rounds to `1.00` at three digits where the decimal answer
 * is `1.01`, and `1.2345` rounds up to `1.235` where the same reasoning
 * would have predicted down. It goes both ways about equally, which is why
 * this is a caveat and not a bias.
 *
 * That is IEEE 754 rather than a rule this object could choose differently
 * without arbitrary precision arithmetic, and it is written down here
 * rather than discovered.
 *
 * Pure; no Android imports and no `java.*`. Unit-tested in `DecimalTest`.
 */
object Decimal {

    /** Digits kept. Past this, a phone answer is noise. */
    const val SIGNIFICANT = 10

    /**
     * Largest magnitude rendered without an exponent.
     *
     * Past this the plain form is more digits than anyone counts, and an
     * exponent is honest about the scale. Below the matching negative bound a
     * plain form is a row of leading zeros.
     */
    private const val MAX_PLAIN_MAGNITUDE = 15
    private const val MIN_PLAIN_MAGNITUDE = -6

    /**
     * Round [value] to [digits] significant digits.
     *
     * Significant rather than decimal places, because a fixed number of
     * decimals is wrong at both ends: it throws away everything in `1/3000`
     * and pads `12345678901` with zeros it does not have.
     */
    fun round(value: Double, digits: Int = SIGNIFICANT): Double {
        if (value == 0.0 || !value.isFinite()) return value
        val magnitude = floor(log10(abs(value)))
        val factor = 10.0.pow(digits - 1 - magnitude)
        // A factor that has overflowed or collapsed cannot round anything.
        if (!factor.isFinite() || factor == 0.0) return value
        val scaled = value * factor
        if (!scaled.isFinite()) return value
        val result = halfUp(scaled) / factor
        // Dividing by a very small factor overflows even when everything
        // before it was finite, which is how Double.MAX_VALUE came back as
        // infinity. A rounded value that is not finite is not a rounding.
        return if (result.isFinite()) result else value
    }

    /**
     * [value] as a string, rounded and without trailing zeros.
     *
     * `4 / 2` reads `2` rather than `2.0000000000`. A trailing `.0` on a whole
     * number is the single most common way a calculator looks like a debugger.
     */
    fun format(value: Double, digits: Int = SIGNIFICANT): String {
        if (!value.isFinite()) return value.toString()
        val rounded = round(value, digits)
        // Negative zero is arithmetically equal to zero and reads as a bug.
        if (rounded == 0.0) return "0"
        return plain(rounded, digits) ?: rounded.toString()
    }

    /**
     * Nearest integer, with ties going away from zero.
     *
     * Not `kotlin.math.round`, which rounds ties to even: it answers 2 for
     * 2.5 and 1.2 for 1.25, which is the statistically unbiased choice and
     * reads as a broken calculator. Half away from zero is what a person
     * means by rounding and what every calculator they have used does.
     *
     * Written out rather than called, which also sidesteps a trap: a bare
     * `round` inside this object resolves to [round] rather than to an
     * import, because a member wins over an imported top level function, and
     * the result is infinite recursion that compiles without a warning.
     */
    private fun halfUp(value: Double): Double {
        val magnitude = floor(abs(value) + 0.5)
        return if (value < 0) -magnitude else magnitude
    }

    /**
     * The digits, laid out by hand, or null when an exponent is the better
     * answer.
     *
     * Renders through a `Long` of scaled units rather than through any
     * formatter: a rounded value has at most [digits] significant digits, so
     * scaling it to an integer is exact for every magnitude this returns for.
     */
    private fun plain(value: Double, digits: Int): String? {
        val magnitude = floor(log10(abs(value))).toInt()
        if (magnitude >= MAX_PLAIN_MAGNITUDE || magnitude < MIN_PLAIN_MAGNITUDE) return null

        val decimals = (digits - 1 - magnitude).coerceAtLeast(0)
        val scaled = value * 10.0.pow(decimals)
        // Past this a Long cannot hold the units and the digits would wrap.
        if (!scaled.isFinite() || abs(scaled) > 9.0e18) return null

        var units = halfUp(scaled).toLong()
        val negative = units < 0
        if (negative) units = -units

        var text = units.toString()
        if (decimals > 0) {
            if (text.length <= decimals) {
                text = "0".repeat(decimals - text.length + 1) + text
            }
            text = text.dropLast(decimals) + "." + text.takeLast(decimals)
            text = text.trimEnd('0').trimEnd('.')
        }
        if (text.isEmpty()) text = "0"
        return if (negative && text != "0") "-$text" else text
    }
}
