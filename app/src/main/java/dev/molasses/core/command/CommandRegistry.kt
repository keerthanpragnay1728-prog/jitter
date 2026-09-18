package dev.molasses.core.command

/**
 * Where a command's effect lands, and therefore what can stop it running.
 *
 * ## Why the surface and not the command
 * A `when` over every verb rots by the tenth command, and one handler per
 * command is ceremony. The right unit is the effect surface, because
 * availability is a property of the surface rather than of the verb: if the
 * accessibility service is unbound, every `SERVICE` command fails identically
 * without four separate checks, and a fifth inherits that for free.
 */
enum class Surface {
    /** Launcher state, synchronous, nothing external can stop it. */
    STATE,

    /** Needs an Intent to resolve on this device. */
    INTENT,

    /** Needs the AccessibilityService, the only privileged route this app has. */
    SERVICE,

    /** Needs a subsystem of this app that may not be built yet. */
    SUBSYSTEM,
}

/**
 * Whether a surface can run a command right now.
 *
 * [Unavailable] carries a string resource id rather than a message, so the
 * reason is copy and lives in `strings.xml` like all other copy. Held as an
 * `Int` rather than a `@StringRes Int` because this file is in the pure
 * module and cannot see `androidx.annotation`.
 */
sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reasonKey: Int) : Availability
}

/**
 * One surface's answer. Implementations live in the Android layer.
 *
 * ## Why the spec is a parameter
 * Availability is still a property of the surface: a surface answers about its
 * own dependency and the dispatcher never asks a command about itself. But a
 * surface can host several commands whose dependency is the same *kind* and
 * not the same *thing*. `INTENT` is the clear case: whether an Intent resolves
 * cannot be answered without knowing which Intent, and a clock app is not a
 * wifi panel. Passing the spec keeps one implementation per surface while
 * letting it name what is missing.
 *
 * The bulk property survives: `SERVICE` ignores the spec and returns one
 * answer for everything on it, so a new SERVICE command inherits the binding
 * check for free.
 */
interface EffectSurface {
    val surface: Surface
    fun availability(spec: CommandSpec): Availability
}

/**
 * One row per command. The single source of truth for parsing, dispatch and
 * help, so a new verb documents itself and cannot silently vanish from the
 * manual.
 *
 * `CommandRegistryTest` asserts a round trip in both directions: every
 * [Command] variant has an entry and every entry maps to a variant. Adding a
 * command without registering it fails the build.
 */
data class CommandSpec(
    val verb: String,
    /** Argument SHAPE only, never a value. See [CommandParser.USAGE]. */
    val usageKey: Int,
    val descriptionKey: Int,
    val surface: Surface,
    /**
     * True when running this suspends friction rather than adding it.
     *
     * Checked by the dispatcher, not by the handler. A relief command that
     * can be typed freely turns the command bar into an escape hatch from the
     * whole app, and putting the check at dispatch means a future relief
     * command cannot forget to make it.
     */
    val isRelief: Boolean = false,
    /** Durations above this need a second Enter. Null means never. */
    val requiresConfirmAboveMs: Long? = null,
)

/**
 * The registry.
 *
 * Resource ids arrive from the Android layer at construction rather than
 * being referenced here, because `R` is generated and this module is
 * Android-free. `CommandRegistry.Keys` is the contract the caller fills.
 */
class CommandRegistry(keys: Keys) {

    /**
     * String resource ids, supplied by the Android layer.
     *
     * One field per command so a missing entry is a compile error rather than
     * a map lookup that returns null at runtime.
     */
    data class Keys(
        val blockUsage: Int, val blockDesc: Int,
        val focusUsage: Int, val focusDesc: Int,
        val bedtimeUsage: Int, val bedtimeDesc: Int,
        val statusUsage: Int, val statusDesc: Int,
        val helpUsage: Int, val helpDesc: Int,
        val alarmUsage: Int, val alarmDesc: Int,
        val timerUsage: Int, val timerDesc: Int,
        val rebootUsage: Int, val rebootDesc: Int,
        val poweroffUsage: Int, val poweroffDesc: Int,
        val wifiUsage: Int, val wifiDesc: Int,
        val dndUsage: Int, val dndDesc: Int,
    )

    val specs: List<CommandSpec> = listOf(
        // SUBSYSTEM. An unbuilt part of this app rather than a missing
        // permission, so the surface answers per command and names which one.
        //
        // "log" and "rem" used to sit here, permanently unavailable, saying
        // "no system log page yet" and "reminder scheduling is not wired
        // yet". They are gone. A terminal that lists a command it can never
        // run is teaching the user to distrust the list, and the manual is
        // now the whole of discovery.
        //
        // "allow" went the same way. It was the last of that shape, and the
        // argument for keeping it was a fact about our notes rather than
        // about the device: relief is designed in CLAUDE.md, but from the
        // prompt it was indistinguishable from the other two. It comes back
        // with the feature, and the design is not lost because it was never
        // in here.
        CommandSpec("block", keys.blockUsage, keys.blockDesc, Surface.SUBSYSTEM,
            requiresConfirmAboveMs = CONFIRM_ABOVE_MS),
        CommandSpec("focus", keys.focusUsage, keys.focusDesc, Surface.SUBSYSTEM,
            requiresConfirmAboveMs = CONFIRM_ABOVE_MS),
        CommandSpec("bedtime", keys.bedtimeUsage, keys.bedtimeDesc, Surface.SUBSYSTEM),

        // STATE. Scroll the pager, open the manual. Nothing can stop either.
        CommandSpec("status", keys.statusUsage, keys.statusDesc, Surface.STATE),
        CommandSpec("help", keys.helpUsage, keys.helpDesc, Surface.STATE),

        // INTENT. Each needs a different Intent to resolve.
        CommandSpec("alarm", keys.alarmUsage, keys.alarmDesc, Surface.INTENT),
        CommandSpec("timer", keys.timerUsage, keys.timerDesc, Surface.INTENT),
        CommandSpec("wifi", keys.wifiUsage, keys.wifiDesc, Surface.INTENT),
        CommandSpec("dnd", keys.dndUsage, keys.dndDesc, Surface.INTENT),

        // SERVICE. An app cannot reboot or power off a device; an
        // AccessibilityService is the only component that could ever get near
        // it, so that is where they sit and where the refusal comes from.
        CommandSpec("reboot", keys.rebootUsage, keys.rebootDesc, Surface.SERVICE),
        CommandSpec("poweroff", keys.poweroffUsage, keys.poweroffDesc, Surface.SERVICE),
    )

    private val byVerb = specs.associateBy { it.verb }

    fun specFor(command: Command): CommandSpec = byVerb.getValue(verbOf(command))

    fun specForVerb(verb: String): CommandSpec? = byVerb[verb]

    companion object {
        /**
         * LockRegistry is extend-only, so a mistyped long lock is unfixable
         * for its whole duration. Anything past a day echoes back first.
         */
        const val CONFIRM_ABOVE_MS = 24L * 60 * 60 * 1000

        /**
         * The canonical verb for a command.
         *
         * `sleep` maps to `bedtime`, matching `CommandRender`: the registry
         * keys on canonical verbs so an alias cannot acquire its own row and
         * drift from the one it aliases.
         */
        fun verbOf(command: Command): String = when (command) {
            is Command.Block -> "block"
            is Command.Focus -> "focus"
            Command.Bedtime -> "bedtime"
            Command.Status -> "status"
            Command.Help -> "help"
            is Command.Alarm -> "alarm"
            is Command.Timer -> "timer"
            Command.Reboot -> "reboot"
            Command.PowerOff -> "poweroff"
            is Command.Wifi -> "wifi"
            is Command.Dnd -> "dnd"
        }

        /** The duration a command would arm, or null when it arms nothing. */
        fun durationOf(command: Command): Long? = when (command) {
            is Command.Block -> command.durationMs
            is Command.Focus -> command.durationMs
            is Command.Timer -> command.durationMs
            else -> null
        }
    }
}
