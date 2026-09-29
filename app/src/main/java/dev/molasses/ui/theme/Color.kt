package dev.molasses.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette. Every colour in the app is one of these.
 *
 * ## One hue, four brightnesses
 * Hierarchy comes from brightness steps of a single hue, not from separate
 * hues. That is how real phosphor terminals worked, and it is what keeps the
 * aesthetic strict while staying readable: a second hue would read as a second
 * kind of information, and this app only has one.
 *
 * ## The single exception
 * [TerminalAlert] is the only colour that breaks the hue, and it is reserved
 * for the terminal tier and nothing else. That is the state where Jitter is
 * deliberately making the phone feel broken, and one hue break is the clearest
 * available signal that this is the app rather than a hardware fault. Same
 * reasoning as the shutter's visible grey tell.
 *
 * Specifically it is **not** for low battery. A battery warning in the same
 * red as the terminal tier would teach the user to read red as "something is
 * wrong with the phone", which is precisely the confusion the tell exists to
 * prevent. Low battery dims toward [PhosphorDivider] and Bit's face carries
 * it instead.
 *
 * ## Inline hex is a build failure
 * `tools/check-colors.sh` fails on a hex literal anywhere under `ui/` except
 * this file. Without that, a one-off `Color(0xFF...)` in a composable is
 * invisible in review and permanent in practice.
 */

/** Pure black. On OLED the pixels are off, which is the point. */
val JitterBackground = Color(0xFF000000)

/** Phosphor green. Body text and accents both. */
val PhosphorGreen = Color(0xFF50FA7B)

/** Metadata and secondary text. */
val PhosphorDim = Color(0xFF88C096)

/**
 * Structure and inline hints. Decorative only.
 *
 * Deliberately low contrast against [JitterBackground], so it must never
 * carry text the user has to read. Autocomplete hints are the intended use:
 * visible enough to notice, dim enough to ignore.
 */
val PhosphorDivider = Color(0xFF245C38)

/**
 * Tier 4 terminal collapse. One use in the entire app.
 *
 * See the class doc. If you are reaching for this for anything else, the
 * answer is a brightness step, not a hue.
 */
val TerminalAlert = Color(0xFFFF5555)
