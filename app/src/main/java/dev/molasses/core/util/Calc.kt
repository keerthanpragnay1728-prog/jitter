package dev.molasses.core.util

/**
 * Arithmetic, and deliberately no more than arithmetic.
 *
 * ## The line, and it is a rule rather than a taste judgement
 * **Operators that are on a phone keypad, nothing that needs a function
 * name.** That admits `+ - * / ( )` and `%`. It excludes `sqrt`, `sin`, `log`
 * and `^`, not because any of them is hard but because the rule is what stops
 * the slide to a scientific calculator, and it can be defended in one
 * sentence to whoever proposes the next one.
 *
 * No variables, no `ans`, no history. Each is state the user would come back
 * to read, and this exists under a boundary rule that excludes exactly that:
 * a bounded input, one answer, nothing left behind.
 *
 * ## Percent is strict, and that surprises people on purpose
 * `%` divides its operand by one hundred. Everywhere, uniformly, whatever is
 * to its left.
 *
 * ```
 * 200 + 10%  =  200.1     not 220
 * 200 - 10%  =  199.9     not 180
 * 200 * 10%  =  20
 * 200 / 10%  =  2000
 * 10%        =  0.1
 * ```
 *
 * **`200 + 10%` returning `200.1` is correct and will be read as a bug.** If
 * you have arrived here because of that, this is the answer: every phone
 * calculator implements the other rule, where `%` after `+` or `-` means a
 * fraction of the left operand and after `*` or `/` means a hundredth. That
 * rule matches what people mean and it is not compositional: the meaning of a
 * node depends on the operator beside it, so the parse tree has one node that
 * does not mean what its children say.
 *
 * Strict was chosen over matching expectation because a grammar with one
 * non-compositional node is a grammar the next person extends wrongly. The
 * cost is a surprising answer to one input; the alternative cost is a
 * surprising answer to an input nobody has written yet.
 *
 * ## What it refuses
 * Division by zero is an error rather than infinity. A calculator printing
 * `Infinity` has stopped answering the question.
 *
 * Every rejection names what was wrong, because "invalid expression" tells
 * the user to guess. Four kinds, four messages: see [Error].
 *
 * Pure; no Android imports and no `java.*`. Error identity is an enum rather
 * than a string, so the copy lives in `strings.xml` like all other copy and
 * the mapping happens at the call site. Unit-tested in `CalcTest`.
 */
object Calc {

    /**
     * Why an expression was refused.
     *
     * Separate cases rather than one, because "unbalanced parentheses" and
     * "ends in an operator" send the user to different places in what they
     * typed, and a single message would send them to neither.
     */
    enum class Error {
        /** Nothing to evaluate. */
        EMPTY,

        /** A character the grammar has no meaning for. */
        UNKNOWN_CHARACTER,

        /** More opening than closing parentheses, or the reverse. */
        UNBALANCED,

        /** An operator with nothing to work on, including a trailing one. */
        MISSING_OPERAND,

        /** Division by zero. */
        DIVIDE_BY_ZERO,
    }

    sealed interface Result {
        data class Value(val value: Double) : Result
        data class Failed(val error: Error) : Result
    }

    /** Characters the grammar knows, for the tokenizer's rejection message. */
    private const val OPERATORS = "+-*/()%"

    fun evaluate(input: String): Result {
        val tokens = tokenize(input) ?: return Result.Failed(Error.UNKNOWN_CHARACTER)
        if (tokens.isEmpty()) return Result.Failed(Error.EMPTY)
        val parser = Parser(tokens)
        val value = parser.expression() ?: return Result.Failed(parser.error ?: Error.MISSING_OPERAND)
        if (!parser.atEnd()) return Result.Failed(parser.error ?: Error.UNBALANCED)
        if (!value.isFinite()) return Result.Failed(Error.DIVIDE_BY_ZERO)
        return Result.Value(value)
    }

    /** [evaluate], rendered. The one entry point a caller needs. */
    fun evaluateToString(input: String): String? =
        (evaluate(input) as? Result.Value)?.let { Decimal.format(it.value) }

    /**
     * One percent literal and what it became.
     *
     * Data, not copy: the caller frames it, because an equals sign between
     * two values is a format template and those live in resources like
     * everything else on screen.
     */
    data class PercentNote(val literal: String, val value: String)

    /**
     * Every distinct percent literal in [input], with its value.
     *
     * ## Why this exists and why it is only this
     * Strict percent puts the whole surprise at one operator: `200 + 10%` is
     * `200.1` because `10%` is `0.1`, and a reader who is told that stops
     * being surprised. So the explanation is attached to that operator and to
     * nothing else. An echo of the normalised expression on every input would
     * be a line on screen that most inputs have not earned.
     *
     * Empty when there is no `%`, which is the common case, so the caller can
     * use emptiness as the condition rather than scanning the text itself.
     *
     * Order is the order typed, and a literal repeated in one expression is
     * listed once: `5% + 5%` has one thing to explain, not two.
     */
    fun percentNotes(input: String): List<PercentNote> {
        val tokens = tokenize(input) ?: return emptyList()
        val notes = LinkedHashMap<String, PercentNote>()
        tokens.forEachIndexed { i, token ->
            if (token !is Token.Num) return@forEachIndexed
            var signs = 0
            var j = i + 1
            while (j < tokens.size && (tokens[j] as? Token.Sym)?.char == '%') {
                signs++
                j++
            }
            if (signs == 0) return@forEachIndexed
            val literal = token.text + "%".repeat(signs)
            var value = token.value
            repeat(signs) { value /= 100.0 }
            notes.getOrPut(literal) { PercentNote(literal, Decimal.format(value)) }
        }
        return notes.values.toList()
    }

    // ------------------------------------------------------------- tokenizer

    private sealed interface Token {
        /**
         * @param text the literal as typed, kept so [percentNotes] can quote
         *   it back. The parser ignores it. One number syntax, one scanner:
         *   a second scanner for the note would be a second thing to keep in
         *   step with the grammar.
         */
        data class Num(val value: Double, val text: String) : Token
        data class Sym(val char: Char) : Token
    }

    /** Null when a character has no meaning here. */
    private fun tokenize(input: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var seenDot = false
                    while (i < input.length && (input[i].isDigit() || (input[i] == '.' && !seenDot))) {
                        if (input[i] == '.') seenDot = true
                        i++
                    }
                    val text = input.substring(start, i)
                    // A bare "." is not a number. toDoubleOrNull catches it
                    // along with anything else the scan let through.
                    val value = text.toDoubleOrNull() ?: return null
                    tokens += Token.Num(value, text)
                }
                c in OPERATORS -> {
                    tokens += Token.Sym(c)
                    i++
                }
                else -> return null
            }
        }
        return tokens
    }

    // ---------------------------------------------------------------- parser

    /**
     * Recursive descent, one function per precedence level.
     *
     * ```
     * expression := term (('+' | '-') term)*
     * term       := unary (('*' | '/') unary)*
     * unary      := '-' unary | postfix
     * postfix    := primary '%'*
     * primary    := NUMBER | '(' expression ')'
     * ```
     *
     * `%` binds tighter than unary minus, so `-10%` is `-(10%)` and not
     * `(-10)%`. Both give the same number here; the ordering is written down
     * because it is the sort of thing that stops being equivalent the moment
     * anyone adds an operator.
     */
    private class Parser(private val tokens: List<Token>) {
        private var position = 0
        var error: Error? = null
            private set

        fun atEnd(): Boolean = position >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(position)

        private fun matchSymbol(vararg chars: Char): Char? {
            val token = peek()
            if (token is Token.Sym && token.char in chars) {
                position++
                return token.char
            }
            return null
        }

        private fun fail(reason: Error): Double? {
            if (error == null) error = reason
            return null
        }

        fun expression(): Double? {
            var left = term() ?: return null
            while (true) {
                val op = matchSymbol('+', '-') ?: return left
                val right = term() ?: return null
                left = if (op == '+') left + right else left - right
            }
        }

        private fun term(): Double? {
            var left = unary() ?: return null
            while (true) {
                val op = matchSymbol('*', '/') ?: return left
                val right = unary() ?: return null
                if (op == '/' && right == 0.0) return fail(Error.DIVIDE_BY_ZERO)
                left = if (op == '*') left * right else left / right
            }
        }

        private fun unary(): Double? {
            if (matchSymbol('-') != null) {
                val operand = unary() ?: return null
                return -operand
            }
            return postfix()
        }

        private fun postfix(): Double? {
            var value = primary() ?: return null
            // Strict, and the whole of the percent argument is in the class
            // doc. A hundredth of its operand, whatever is to the left.
            while (matchSymbol('%') != null) value /= 100.0
            return value
        }

        private fun primary(): Double? {
            val token = peek()
            return when {
                token is Token.Num -> {
                    position++
                    token.value
                }
                token is Token.Sym && token.char == '(' -> {
                    position++
                    val inner = expression() ?: return null
                    if (matchSymbol(')') == null) return fail(Error.UNBALANCED)
                    inner
                }
                // A closing parenthesis with nothing open, or nothing at all
                // where a number belongs. The first is unbalanced, the second
                // is an operator with no operand, and they are different
                // sentences to whoever typed them.
                token is Token.Sym && token.char == ')' -> fail(Error.UNBALANCED)
                else -> fail(Error.MISSING_OPERAND)
            }
        }
    }
}
