package dev.molasses.core.command

/**
 * What happened to a command line.
 *
 * ## NotWired is gone
 * It used to be a distinct outcome, which made "not implemented" a special
 * case sitting next to "service not bound" and "no alarm app installed" even
 * though all three are the same thing: a surface reporting it cannot run this
 * right now. One outcome type, several reasons, and a new reason costs
 * nothing.
 */
sealed interface DispatchResult {
    /** It ran. */
    data class Confirmed(val ackKey: Int, val args: List<String> = emptyList()) : DispatchResult

    /** Bad input. The user can fix this by typing something else. */
    data class Failed(val reasonKey: Int, val args: List<String> = emptyList()) : DispatchResult

    /** Cannot run here or yet. The user cannot fix this by retyping. */
    data class Unavailable(val reasonKey: Int, val args: List<String> = emptyList()) :
        DispatchResult

    /**
     * A long lock, echoed back for a second Enter.
     *
     * Not a dialog and not a new surface: the prompt holds a pending state and
     * any other input cancels it.
     */
    data class NeedsConfirmation(val command: Command, val echo: String) : DispatchResult

    /** Not a command at all. The caller falls back to filtering apps. */
    data object NotACommand : DispatchResult
}

/**
 * Whether relief may be granted right now.
 *
 * `$ allow youtube 10m` suspends checkpoints. If it can be typed freely the
 * command bar becomes an escape hatch from the entire app, so the decision is
 * a policy the dispatcher consults, not something a handler can skip.
 */
fun interface ReliefPolicy {
    fun allows(): Availability
}

/**
 * Routes a parsed [Command] to a surface, in a fixed order.
 *
 * Parsing stays total and knows nothing about availability: `$ block
 * instagram 30m` parses whether or not locks exist. That separation is what
 * keeps the parser pure and testable, and what lets the help output enumerate
 * commands that do not work yet.
 *
 * Availability is resolved here, never at parse.
 *
 * Pure; no Android imports. Unit-tested in `DispatchTest`.
 */
class CommandDispatch(
    /** Public so the manual is generated from the same rows dispatch uses. */
    val registry: CommandRegistry,
    private val surfaces: Map<Surface, EffectSurface>,
    private val reliefPolicy: ReliefPolicy,
    /**
     * Reason shown when no surface is registered for a command's kind. A
     * wiring bug rather than a user-facing condition, but it has to render as
     * something rather than crash.
     */
    private val missingSurfaceKey: Int,
    /** Runs the command once every gate above has passed. */
    private val execute: (Command) -> DispatchResult,
) {

    /**
     * Whether [spec] could run right now, without running anything.
     *
     * The same two gates `dispatch` applies, in the same order, exposed so the
     * manual can dim a row and give the reason the dispatcher would have
     * given. Two code paths would drift, and the drift would show up as a
     * manual that says a command works and a dispatcher that refuses it.
     */
    fun availabilityOf(spec: CommandSpec): Availability {
        // 1. Surface availability. The cheapest check and the one that covers
        //    the most commands at once.
        val surface = surfaces[spec.surface] ?: return Availability.Unavailable(missingSurfaceKey)
        val available = surface.availability(spec)
        if (available is Availability.Unavailable) return available

        // 2. Relief policy, before anything is armed. A dispatcher-level
        //    check so a future relief command cannot forget it.
        if (spec.isRelief) return reliefPolicy.allows()

        return Availability.Available
    }

    /**
     * @param confirmed true when this is the second Enter on a long lock.
     */
    fun dispatch(command: Command, confirmed: Boolean = false): DispatchResult {
        val spec = registry.specFor(command)

        when (val available = availabilityOf(spec)) {
            is Availability.Unavailable -> return DispatchResult.Unavailable(available.reasonKey)
            Availability.Available -> Unit
        }

        // 3. Confirmation for a long lock. LockRegistry is extend-only, so a
        //    mistyped 30d is unfixable for a month.
        val threshold = spec.requiresConfirmAboveMs
        val duration = CommandRegistry.durationOf(command)
        if (!confirmed && threshold != null && duration != null && duration > threshold) {
            return DispatchResult.NeedsConfirmation(command, CommandRender.render(command))
        }

        return execute(command)
    }
}
