package dev.molasses.ui.launcher

import androidx.annotation.StringRes
import dev.molasses.R
import dev.molasses.core.console.ConsoleIds

/**
 * A stable line id to the copy it renders.
 *
 * The persisted form cannot carry a resource id, because R is regenerated on
 * every build and a stored one would point at unrelated copy after an
 * upgrade. So the id crosses the boundary as a string and is resolved here,
 * the same way `CommandRegistry.Keys` hands resource ids to the Android-free
 * registry.
 *
 * A line whose id has no copy renders as an empty row and still spends one of
 * three an hour, so `ConsoleCopyTest` requires every id in [ConsoleIds.ALL]
 * to have a `console_<id>` string.
 */
object ConsoleCopy {

    @StringRes
    fun textRes(id: String): Int? = when (id) {
        ConsoleIds.SCROLLED -> R.string.console_scrolled
        ConsoleIds.GREETING_MORNING -> R.string.console_greeting_morning
        ConsoleIds.GREETING_AFTERNOON -> R.string.console_greeting_afternoon
        ConsoleIds.GREETING_LATE -> R.string.console_greeting_late
        // An unknown id can only come from a file written by a newer build.
        // Rendering nothing is right; spending a line on it is not, so the
        // caller drops it rather than delivering it.
        else -> null
    }
}
