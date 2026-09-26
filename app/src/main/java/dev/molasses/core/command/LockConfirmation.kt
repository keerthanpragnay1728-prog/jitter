package dev.molasses.core.command

/**
 * The full-screen confirmation a long lock typed at the console gets. Pure.
 *
 * ```
 *     ( -_- )
 *     IMMUTABLE LOCK REQUEST
 *     TARGET // <app or ALL TRACKED>
 *     DURATION // <duration>
 *     [ COMMIT LOCK ]
 * ```
 *
 * ## Why a panel and not an echo in the prompt
 * The console used to put the canonical line back in the prompt and wait for
 * a second Enter. An Enter is the key the user just pressed, and a month is
 * one reflex away from it. The panel asks a question the prompt cannot: what
 * is being locked, and for how long, in words, with one button that is not
 * the key they were already pressing.
 *
 * There is no cancel button. Back aborts and writes nothing, which is the
 * safe direction, and a cancel sitting next to the commit would be a second
 * target for the same thumb.
 *
 * ## Which commands get it
 * Whatever the dispatcher says needs confirming: a lock above
 * `CommandRegistry.CONFIRM_ABOVE_MS`, judged through `LockRequest.evaluate`.
 * At or below it the command runs in one step, unchanged. `$ bedtime` never
 * gets it: its window ends at the next wake time, so it is at most a minute
 * short of a day and can never pass the threshold.
 *
 * ## It is never recorded
 * The commit is a button, not a line, so there is nothing for the command
 * history to hold. The line the user typed was recorded when they typed it.
 */
object LockConfirmation {

    sealed interface Target {
        /** One app, by its label. */
        data class App(val label: String) : Target

        /** `$ focus`: every tracked app. */
        data object AllTracked : Target
    }

    data class Panel(val command: Command, val target: Target, val durationMs: Long)

    /**
     * The panel for [command], or null when there is none to show: it is not
     * a lock, or it is a block whose app did not resolve to exactly one
     * package. The caller then dispatches it confirmed and gets the same
     * refusal the one-step path would give, with nothing armed.
     *
     * @param appLabel the label of the one package a block's app token
     *   resolved to, or null if it did not resolve to one.
     */
    fun panelFor(command: Command, appLabel: String?): Panel? = when (command) {
        is Command.Block -> appLabel?.let { Panel(command, Target.App(it), command.durationMs) }
        is Command.Focus -> Panel(command, Target.AllTracked, command.durationMs)
        else -> null
    }
}
