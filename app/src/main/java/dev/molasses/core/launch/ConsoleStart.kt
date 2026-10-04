package dev.molasses.core.launch

/**
 * One start from the console, and what a refusal does. Pure: the caller
 * passes the start and what to do about a refusal.
 *
 * Any [RuntimeException] from the start is a refusal. It is handed to
 * `onRefused` once (which logs its class and says one line on the
 * console) and the start reads as false, so the caller can try the next
 * rung or give its own answer. The platform's usual refusals are
 * `ActivityNotFoundException` and `SecurityException`, but a launcher that
 * crashes on a third kind is still a launcher that crashed, so the catch is
 * the whole class and the log names which one it was.
 *
 * The try holds the start and nothing else, so a fault in the caller's own
 * code around it is never answered as "nothing could open that". An
 * [Error] is not a refusal and is thrown on.
 */
object ConsoleStart {

    /** @return true when the start returned, false when it was refused. */
    fun attempt(
        start: () -> Unit,
        onRefused: (RuntimeException) -> Unit,
    ): Boolean {
        try {
            start()
        } catch (e: RuntimeException) {
            onRefused(e)
            return false
        }
        return true
    }
}
