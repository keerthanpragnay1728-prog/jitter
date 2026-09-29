package dev.molasses.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimalTest {

    @Test
    fun `the embarrassment this exists for`() {
        // Every calculator that prints this has told the truth about its
        // arithmetic and lied about the question.
        assertEquals("0.3", Decimal.format(0.1 + 0.2))
    }

    @Test
    fun `a whole number has no decimal point`() {
        // A trailing .0 is the single most common way a calculator looks like
        // a debugger.
        assertEquals("2", Decimal.format(4.0 / 2.0))
        assertEquals("0", Decimal.format(0.0))
        assertEquals("-5", Decimal.format(-5.0))
    }

    @Test
    fun `negative zero reads as zero`() {
        // Arithmetically equal, and visibly a bug.
        assertEquals("0", Decimal.format(-0.0))
        assertEquals("0", Decimal.format(0.0 * -1.0))
    }

    @Test
    fun `ten significant digits, not ten decimal places`() {
        // Decimal places are wrong at both ends: they throw away everything in
        // a small number and pad a large one with zeros it does not have.
        assertEquals("0.3333333333", Decimal.format(1.0 / 3.0))
        assertEquals("33333.33333", Decimal.format(100000.0 / 3.0))
    }

    @Test
    fun `trailing zeros inside the precision are trimmed`() {
        assertEquals("0.5", Decimal.format(0.5))
        assertEquals("1.25", Decimal.format(1.25))
    }

    @Test
    fun `integers stay exact well past anything typed on a phone`() {
        assertEquals("123456789", Decimal.format(123456789.0))
        assertEquals("1000000", Decimal.format(1000000.0))
    }

    @Test
    fun `ordinary magnitudes never show an exponent`() {
        // Double.toString switches to scientific at 1e7, so plain 123456789
        // renders as 1.23456789E8. For a calculator that is worse than the
        // rounding problem, because it happens to numbers people type.
        for (v in listOf(1.0e7, 123456789.0, 9999999.0, 1.0e14, 0.0001)) {
            val out = Decimal.format(v)
            assertTrue("$v rendered as $out", 'E' !in out && 'e' !in out)
        }
    }

    @Test
    fun `past the plain range an exponent is the honest form`() {
        // Fifteen digits nobody counts, or a row of leading zeros.
        assertTrue(Decimal.format(1.0e20).let { 'E' in it || 'e' in it })
        assertTrue(Decimal.format(1.0e-12).let { 'E' in it || 'e' in it })
    }

    @Test
    fun `extreme magnitudes round trip rather than becoming infinite`() {
        // The rounding factor overflows well before the value does, and
        // multiplying by an infinite factor would turn a finite answer into
        // an infinite one.
        for (v in listOf(1.0e300, -1.0e300, 1.0e-300, Double.MIN_VALUE, Double.MAX_VALUE)) {
            val r = Decimal.round(v)
            assertTrue("$v became $r", r.isFinite())
        }
    }

    @Test
    fun `a non-finite input is returned rather than mangled`() {
        // Callers reject these before they get here. This says what happens
        // if one ever does not, rather than leaving it to be discovered.
        assertTrue(Decimal.round(Double.NaN).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Decimal.round(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun `rounding is to the nearest, and ties go away from zero`() {
        // Values chosen to be exactly representable, so this tests the rule
        // rather than the representation. See the test below for why that
        // distinction is not pedantry.
        assertEquals(1.3, Decimal.round(1.25, 2), 1e-12)
        assertEquals(-1.3, Decimal.round(-1.25, 2), 1e-12)
        assertEquals(3.0, Decimal.round(2.5, 1), 1e-12)
        assertEquals(-3.0, Decimal.round(-2.5, 1), 1e-12)
    }

    @Test
    fun `a tie that is not representable follows the binary value`() {
        // The limitation, with values measured rather than assumed. A decimal
        // literal ending in 5 is usually not exactly representable, and
        // scaling it by a power of ten rounds again, so which side of the tie
        // it lands on is a property of the two doubles rather than of the
        // digits that were typed.
        //
        // 1.005 is the textbook case: its nearest double is below the
        // midpoint, so it rounds down where the decimal answer is 1.01.
        assertEquals(1.0, Decimal.round(1.005, 3), 1e-12)
        assertEquals(4.47, Decimal.round(4.475, 3), 1e-12)

        // And it goes the other way just as often, which is why this is a
        // caveat rather than a bias. Both of these land above their midpoint.
        assertEquals(1.235, Decimal.round(1.2345, 4), 1e-12)
        assertEquals(8.84, Decimal.round(8.835, 3), 1e-12)
    }
}
