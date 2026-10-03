package dev.molasses.core.launch

/**
 * One start from the console, and what a refusal does. Pure: the caller
 * passes the start, which exceptions count as a refusal, and what to do
 * about one.
 *
 * A refusal is caught, handed to [attempt]'s `onRefused` (which logs it
 * and says one line on the console), and reported as false, so the caller
 * can try the next rung or give its own answer. Anything else is thrown
 * on: it is not "nothing could open that", and answering it as though it
 * were would hide a bug behind a line the user cannot act on.
 *
 * The Android side passes `ActivityNotFoundException` and
 * `SecurityException`, the two ways the platform refuses a start. See
 * `startFromConsole`.
 */
object ConsoleStart {

    /** @return true when the start returned, false when it was refused. */
    fun attempt(
        start: () -> Unit,
        isRefusal: (RuntimeException) -> Boolean,
        onRefused: (RuntimeException) -> Unit,
    ): Boolean = try {
        start()
        true
    } catch (e: RuntimeException) {
        if (!isRefusal(e)) throw e
        onRefused(e)
        false
    }
}
