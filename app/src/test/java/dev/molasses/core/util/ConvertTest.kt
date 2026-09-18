package dev.molasses.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertTest {

    private fun value(amount: Double, from: String, to: String): Double =
        (Convert.convert(amount, from, to) as Convert.Result.Value).value

    private fun error(amount: Double, from: String, to: String): Convert.Error =
        (Convert.convert(amount, from, to) as Convert.Result.Failed).error

    // ----------------------------------------------------------- the numbers

    @Test
    fun `length`() {
        assertEquals(1.609344, value(1.0, "mi", "km"), 1e-9)
        assertEquals(1.0, value(1.609344, "km", "mi"), 1e-9)
        assertEquals(2.54, value(1.0, "in", "cm"), 1e-9)
        assertEquals(3.280839895, value(1.0, "m", "ft"), 1e-6)
    }

    @Test
    fun `mass`() {
        assertEquals(2.204622622, value(1.0, "kg", "lb"), 1e-6)
        assertEquals(28.349523125, value(1.0, "oz", "g"), 1e-9)
        assertEquals(14.0, value(1.0, "st", "lb"), 1e-6)
    }

    @Test
    fun `speed`() {
        assertEquals(1.609344, value(1.0, "mph", "kph"), 1e-9)
        assertEquals(62.13711922, value(100.0, "kph", "mph"), 1e-6)
    }

    @Test
    fun `temperature is affine, which is why the table is`() {
        // The three fixed points everyone knows. A multiplier table cannot
        // produce any of them.
        assertEquals(32.0, value(0.0, "c", "f"), 1e-9)
        assertEquals(212.0, value(100.0, "c", "f"), 1e-9)
        assertEquals(-40.0, value(-40.0, "c", "f"), 1e-9)
        assertEquals(0.0, value(32.0, "f", "c"), 1e-9)
        assertEquals(37.0, value(98.6, "f", "c"), 1e-6)
    }

    // ------------------------------------------------------------ properties

    @Test
    fun `every pair converts in both directions`() {
        // There is no direction in the table: each unit says how to reach its
        // base. This says so for every admitted unit rather than for the ones
        // someone thought to write a test for.
        for (a in Convert.UNITS) {
            for (b in Convert.UNITS) {
                if (a.dimension != b.dimension) continue
                val there = (Convert.convert(7.0, a, b) as Convert.Result.Value).value
                val back = (Convert.convert(there, b, a) as Convert.Result.Value).value
                assertEquals("${a.token} to ${b.token} and back", 7.0, back, 1e-6)
            }
        }
    }

    @Test
    fun `a unit converted to itself is unchanged`() {
        for (u in Convert.UNITS) {
            assertEquals(u.token, 12.5, value(12.5, u.token, u.token), 1e-9)
        }
    }

    @Test
    fun `no two units share a token`() {
        // A duplicate would silently shadow, and the one that lost would be
        // unreachable with nothing to say so.
        assertEquals(Convert.UNITS.size, Convert.UNITS.map { it.token }.distinct().size)
    }

    @Test
    fun `every dimension has at least one pair`() {
        // A dimension with one unit is a unit that can only convert to
        // itself, which is a row in a table doing nothing.
        for (d in Convert.Dimension.entries) {
            val count = Convert.UNITS.count { it.dimension == d }
            assertTrue("$d has $count units", count >= 2)
        }
    }

    @Test
    fun `only temperature carries an offset`() {
        // The affine shape exists for one dimension. If a second one grows an
        // offset, the table has stopped being a unit table and this should be
        // read again.
        for (u in Convert.UNITS) {
            if (u.dimension == Convert.Dimension.TEMPERATURE) continue
            assertEquals("${u.token} has an offset", 0.0, u.offset, 0.0)
        }
    }

    // ---------------------------------------------------------------- refusal

    @Test
    fun `volume is not in the table, and that is the rule working`() {
        // A US gallon is 3.785 l and an Imperial gallon is 4.546. Read the
        // class doc before adding either.
        for (token in listOf("l", "ml", "gal", "floz", "pt", "qt", "usgal", "impgal")) {
            assertNull("$token should not be admitted", Convert.unitFor(token))
        }
    }

    @Test
    fun `converting across dimensions is refused`() {
        assertEquals(Convert.Error.DIMENSION_MISMATCH, error(5.0, "km", "lb"))
        assertEquals(Convert.Error.DIMENSION_MISMATCH, error(5.0, "c", "kg"))
    }

    @Test
    fun `an unknown token is a different error from a mismatch`() {
        // "I do not know that unit" and "those two do not compare" send the
        // user to different places in what they typed.
        assertEquals(Convert.Error.UNKNOWN_UNIT, error(5.0, "km", "furlong"))
        assertEquals(Convert.Error.UNKNOWN_UNIT, error(5.0, "parsec", "km"))
    }

    @Test
    fun `tokens are case insensitive and tolerate padding`() {
        assertNotNull(Convert.unitFor("KM"))
        assertNotNull(Convert.unitFor(" Kg "))
        assertEquals(1.609344, value(1.0, "MI", "Km"), 1e-9)
    }

    // ------------------------------------------------------------- the parse

    @Test
    fun `three tokens in order`() {
        val r = Convert.parse("5 km mi") as Convert.Result.Value
        assertEquals(3.106855961, r.value, 1e-6)
        assertEquals("mi", r.unit.token)
    }

    @Test
    fun `both directions of the same pair parse`() {
        assertTrue(Convert.parse("5 km mi") is Convert.Result.Value)
        assertTrue(Convert.parse("5 mi km") is Convert.Result.Value)
    }

    @Test
    fun `the amount is a number and not an expression`() {
        // A converter that evaluated 2+3 would be a calculator wearing a
        // second verb, and the two are separate commands on purpose.
        assertEquals(Convert.Error.NOT_A_NUMBER, (Convert.parse("2+3 km mi") as Convert.Result.Failed).error)
    }

    @Test
    fun `the wrong number of tokens is refused`() {
        for (input in listOf("", "5", "5 km", "5 km mi extra")) {
            assertTrue("$input", Convert.parse(input) is Convert.Result.Failed)
        }
    }

    @Test
    fun `extra whitespace does not change the answer`() {
        val tight = (Convert.parse("5 km mi") as Convert.Result.Value).value
        val loose = (Convert.parse("   5    km    mi   ") as Convert.Result.Value).value
        assertEquals(tight, loose, 0.0)
    }

    @Test
    fun `nothing in here keeps state between calls`() {
        val first = Convert.parse("5 km mi")
        Convert.parse("100 c f")
        val again = Convert.parse("5 km mi")
        assertEquals(
            (first as Convert.Result.Value).value,
            (again as Convert.Result.Value).value,
            0.0,
        )
    }
}
