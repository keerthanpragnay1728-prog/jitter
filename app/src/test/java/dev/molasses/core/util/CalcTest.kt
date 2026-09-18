package dev.molasses.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {

    private fun value(input: String): Double =
        (Calc.evaluate(input) as Calc.Result.Value).value

    private fun error(input: String): Calc.Error =
        (Calc.evaluate(input) as Calc.Result.Failed).error

    // ------------------------------------------------------------ arithmetic

    @Test
    fun `the four operators`() {
        assertEquals(7.0, value("3 + 4"), 0.0)
        assertEquals(-1.0, value("3 - 4"), 0.0)
        assertEquals(12.0, value("3 * 4"), 0.0)
        assertEquals(0.75, value("3 / 4"), 0.0)
    }

    @Test
    fun `precedence without parentheses`() {
        assertEquals(14.0, value("2 + 3 * 4"), 0.0)
        assertEquals(10.0, value("2 * 3 + 4"), 0.0)
        assertEquals(2.5, value("1 + 6 / 4"), 0.0)
    }

    @Test
    fun `parentheses override it`() {
        assertEquals(20.0, value("(2 + 3) * 4"), 0.0)
        assertEquals(14.0, value("2 + (3 * 4)"), 0.0)
        assertEquals(1.0, value("((((1))))"), 0.0)
    }

    @Test
    fun `subtraction and division are left associative`() {
        // 10 - 3 - 2 is 5, not 9. A right-associative parser gets this wrong
        // and passes every commutative test.
        assertEquals(5.0, value("10 - 3 - 2"), 0.0)
        assertEquals(2.0, value("16 / 4 / 2"), 0.0)
    }

    @Test
    fun `unary minus`() {
        assertEquals(-5.0, value("-5"), 0.0)
        assertEquals(5.0, value("--5"), 0.0)
        assertEquals(-1.0, value("2 * -0.5"), 0.0)
        assertEquals(-7.0, value("-(3 + 4)"), 0.0)
    }

    @Test
    fun `decimals and whitespace`() {
        assertEquals(3.5, value("1.25+2.25"), 0.0)
        assertEquals(3.5, value("  1.25   +   2.25  "), 0.0)
        assertEquals(0.5, value(".5"), 0.0)
    }

    // --------------------------------------------------------------- percent

    @Test
    fun `percent is strict, everywhere, whatever is to the left`() {
        // The table from the spec, one assertion per row.
        assertEquals(200.1, value("200 + 10%"), 1e-9)
        assertEquals(199.9, value("200 - 10%"), 1e-9)
        assertEquals(20.0, value("200 * 10%"), 1e-9)
        assertEquals(2000.0, value("200 / 10%"), 1e-9)
        assertEquals(0.1, value("10%"), 1e-9)
    }

    @Test
    fun `the case that looks wrong and is not`() {
        // 200 + 10% is 200.1 and every phone calculator says 220. This is the
        // deliberate choice: strict percent keeps the grammar compositional,
        // and the contextual rule puts one node in the tree whose meaning
        // depends on its neighbour. If this test is ever "fixed", read the
        // class doc first.
        assertEquals(200.1, value("200 + 10%"), 1e-9)
    }

    @Test
    fun `percent is compositional, which is the whole argument for it`() {
        // Each of these is the strict reading applied to a subexpression, and
        // each falls out of the grammar rather than needing a rule.
        assertEquals(0.05, value("(2 + 3)%"), 1e-9)
        assertEquals(0.001, value("10%%"), 1e-9)
        assertEquals(-0.1, value("-10%"), 1e-9)
        assertEquals(0.02, value("10% * 20%"), 1e-9)
    }

    @Test
    fun `percent binds tighter than unary minus`() {
        // Same number either way today. Asserted because the ordering stops
        // being equivalent the moment anyone adds an operator.
        assertEquals(value("-(10%)"), value("-10%"), 0.0)
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `an empty expression is its own error`() {
        assertEquals(Calc.Error.EMPTY, error(""))
        assertEquals(Calc.Error.EMPTY, error("   "))
    }

    @Test
    fun `an unknown character is its own error`() {
        // Not "invalid expression". The user needs to know it was the letter.
        assertEquals(Calc.Error.UNKNOWN_CHARACTER, error("2 + x"))
        assertEquals(Calc.Error.UNKNOWN_CHARACTER, error("sqrt(4)"))
        assertEquals(Calc.Error.UNKNOWN_CHARACTER, error("2 ^ 8"))
    }

    @Test
    fun `unbalanced parentheses are their own error`() {
        assertEquals(Calc.Error.UNBALANCED, error("(2 + 3"))
        assertEquals(Calc.Error.UNBALANCED, error("2 + 3)"))
        assertEquals(Calc.Error.UNBALANCED, error(")"))
    }

    @Test
    fun `an operator with nothing to work on is its own error`() {
        assertEquals(Calc.Error.MISSING_OPERAND, error("2 +"))
        assertEquals(Calc.Error.MISSING_OPERAND, error("* 2"))
        assertEquals(Calc.Error.MISSING_OPERAND, error("2 + * 3"))
        assertEquals(Calc.Error.MISSING_OPERAND, error("-"))
    }

    @Test
    fun `division by zero is refused rather than answered with infinity`() {
        // A calculator printing Infinity has stopped answering the question.
        assertEquals(Calc.Error.DIVIDE_BY_ZERO, error("1 / 0"))
        assertEquals(Calc.Error.DIVIDE_BY_ZERO, error("1 / (2 - 2)"))
        assertEquals(Calc.Error.DIVIDE_BY_ZERO, error("0 / 0"))
    }

    @Test
    fun `no input produces a non-finite answer`() {
        // The guard behind the guard. Whatever gets past the explicit zero
        // check must still not reach the renderer as infinity or NaN.
        val inputs = listOf(
            "1 / 0", "0 / 0", "-1 / 0", "1 / 0%",
            "999999999 * 999999999 * 999999999 * 999999999",
        )
        for (input in inputs) {
            when (val r = Calc.evaluate(input)) {
                is Calc.Result.Value -> assertTrue("$input gave ${r.value}", r.value.isFinite())
                is Calc.Result.Failed -> Unit
            }
        }
    }

    // --------------------------------------------------------------- output

    @Test
    fun `the rendered form is the one a person would write`() {
        assertEquals("2", Calc.evaluateToString("4 / 2"))
        assertEquals("0.3", Calc.evaluateToString("0.1 + 0.2"))
        assertEquals("200.1", Calc.evaluateToString("200 + 10%"))
        assertEquals("0.1", Calc.evaluateToString("10%"))
    }

    @Test
    fun `a refusal renders as nothing rather than as a number`() {
        assertNull(Calc.evaluateToString("2 +"))
        assertNull(Calc.evaluateToString("1 / 0"))
    }

    @Test
    fun `nothing in here keeps state between calls`() {
        // The boundary rule as a test. Two identical calls with a different
        // call in between must give the same answer, which they cannot if an
        // ans or a history has appeared.
        val first = Calc.evaluateToString("2 + 2")
        Calc.evaluateToString("99 * 99")
        assertEquals(first, Calc.evaluateToString("2 + 2"))
    }
}
