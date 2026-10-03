package dev.molasses.core.safety

/**
 * Every way out of a gate or a lock lands on Jitter's console. Pure: the
 * caller passes the start, the fallback and the log.
 *
 * ## Why by name and not through the home action
 * [ ARCHITECT'S SPACE ] used to send `GLOBAL_ACTION_HOME`, which goes to
 * whatever the device calls home. A device that showed its stock launcher
 * after an exit, with Jitter holding the home role, made that a promise the
 * control could not keep: the label says Jitter's space, and the home action
 * is the system's to route. Starting `LauncherActivity` by name says where.
 *
 * Home-first, the home action at the first draw, is not this and does not go
 * through here. Its job is to put the gated app in the background, and the
 * home action is the stronger tool for that: no background launch rule can
 * block it.
 *
 * ## What the fallback covers, and what it cannot
 * A start that throws (no such activity, a security refusal) falls back to
 * the home action and is logged, so an exit never strands the user on the
 * overlay's own app with the overlay gone.
 *
 * A start the system declines without throwing is not seen here. Android
 * blocks some background activity starts by returning normally and logging
 * on the system side. An accessibility service is on the documented list of
 * exemptions, and the gate's lease relaunch already depends on that, but
 * [Route.CONSOLE] means the start was sent, not that the console is showing.
 * The caller logs it as sent for that reason.
 *
 * Only [RuntimeException] is caught. Everything `startActivity` throws is
 * one; an [Error] is not a refusal and is not hidden behind a fallback.
 */
object ConsoleExit {

    enum class Route {
        /** The console start was sent without a throw. */
        CONSOLE,

        /** The start threw; the home action was sent instead. */
        HOME_FALLBACK,
    }

    fun open(
        start: () -> Unit,
        fallback: () -> Unit,
        onRefused: (RuntimeException) -> Unit,
    ): Route = try {
        start()
        Route.CONSOLE
    } catch (e: RuntimeException) {
        onRefused(e)
        fallback()
        Route.HOME_FALLBACK
    }
}
