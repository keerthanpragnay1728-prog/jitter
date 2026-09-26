package dev.molasses.core

/**
 * The body of the first function in [text] whose declaration contains
 * [signature], from the signature to its matching closing brace.
 *
 * Brace counting with no string or comment awareness. The wiring tests that
 * use it read service and overlay code whose braces balance, and a wrong
 * slice fails an assertion rather than passing one, which is the safe
 * direction for a test.
 */
fun functionBody(text: String, signature: String): String {
    val start = text.indexOf(signature)
    if (start < 0) throw AssertionError("could not find `$signature`")
    val open = text.indexOf('{', start)
    if (open < 0) throw AssertionError("`$signature` has no body")
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        i++
    }
    throw AssertionError("`$signature` never closes")
}
