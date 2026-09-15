package dev.molasses.core.command

import dev.molasses.core.time.TimeParser

/**
 * A [Command] back to its canonical text form.
 *
 * Exists so the parser can be round-tripped: `parse(render(c)) == c` for every
 * command the parser can produce. That property is what makes the grammar
 * trustworthy. Without it the parser and any code that echoes a command back
 * (a confirmation line, a persisted lock reason, a history list) can disagree
 * about what the user asked for, and the disagreement shows up as a lock with
 * the wrong duration rather than as an error.
 *
 * Canonical means one spelling per command. `sleep` renders as `bedtime`, and
 * durations render in the largest units that divide exactly, so `90m` comes
 * back as `1h30m`. The round trip is on the parsed *value*, not on the
 * original text.
 *
 * Pure; no Android imports. Unit-tested in `CommandRenderTest`.
 */
object CommandRender {

    fun render(command: Command): String = when (command) {
        is Command.Block -> "block ${command.appToken} ${duration(command.durationMs)}"
        is Command.Allow -> "allow ${command.appToken} ${duration(command.durationMs)}"
        is Command.Focus -> "focus ${duration(command.durationMs)}"
        is Command.Timer -> "timer ${duration(command.durationMs)}"
        Command.Bedtime -> "bedtime"
        Command.Status -> "status"
        Command.Help -> "help"
        Command.Reboot -> "reboot"
        Command.PowerOff -> "poweroff"
        is Command.Log -> if (command.appToken == null) "log" else "log ${command.appToken}"
        is Command.Alarm -> "alarm ${time(command.minuteOfDay)}"
        is Command.Remind -> "rem ${duration(command.durationMs)} ${command.text}"
        is Command.Wifi -> "wifi${toggle(command.enable)}"
        is Command.Dnd -> "dnd${toggle(command.enable)}"
    }

    /**
     * Largest units first, omitting zero components.
     *
     * Always emits at least one component: a zero duration cannot reach here
     * from the parser, which rejects it, but rendering "" would produce a line
     * that reparses as a missing argument rather than failing loudly.
     */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0s"
        var rest = ms
        val out = StringBuilder()
        for ((unit, suffix) in UNITS) {
            val n = rest / unit
            if (n > 0) {
                out.append(n).append(suffix)
                rest -= n * unit
            }
        }
        // Sub-second remainders are dropped: the grammar has no unit smaller
        // than a second, so a duration that is not a whole number of seconds
        // cannot be rendered faithfully. Nothing in the parser can produce
        // one, and a caller that constructs a Command by hand gets the floor
        // rather than a string that will not reparse.
        return if (out.isEmpty()) "0s" else out.toString()
    }

    /** Always `HH:mm`, the one 24 hour spelling, so noon is never ambiguous. */
    fun time(minuteOfDay: Int): String {
        val m = ((minuteOfDay % TimeParser.MINUTES_PER_DAY) + TimeParser.MINUTES_PER_DAY) %
            TimeParser.MINUTES_PER_DAY
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    private fun toggle(enable: Boolean?): String = when (enable) {
        null -> ""
        true -> " on"
        false -> " off"
    }

    private val UNITS: List<Pair<Long, String>> = listOf(
        24 * 60 * 60 * 1000L to "d",
        60 * 60 * 1000L to "h",
        60 * 1000L to "m",
        1000L to "s",
    )
}
