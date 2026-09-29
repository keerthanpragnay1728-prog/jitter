package dev.molasses.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * One theme, dark only.
 *
 * There is no light scheme. A light phosphor terminal is not a thing, and a
 * launcher that is pure black on OLED is a battery decision as much as an
 * aesthetic one. `isSystemInDarkTheme()` is deliberately not consulted.
 *
 * Every colour comes from `Color.kt`. Every text style is monospace: the
 * layout is a character grid, and a proportional face breaks the alignment
 * that the bars and the telemetry row depend on.
 */
private val PhosphorColors = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = JitterBackground,
    secondary = PhosphorDim,
    onSecondary = JitterBackground,
    background = JitterBackground,
    onBackground = PhosphorGreen,
    surface = JitterBackground,
    onSurface = PhosphorGreen,
    surfaceVariant = JitterBackground,
    onSurfaceVariant = PhosphorDim,
    outline = PhosphorDivider,
    outlineVariant = PhosphorDivider,
    error = TerminalAlert,
    onError = JitterBackground,
)

/**
 * Monospace at every size.
 *
 * Sizes are in `sp`, so the system font scale applies. `FontScale` multiplies
 * on top of it rather than replacing it: a user who has already enlarged
 * system text must not have Jitter quietly override them.
 */
private fun phosphorTypography(scale: Float): Typography {
    fun style(sizeSp: Float) = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = (sizeSp * scale).sp,
    )
    return Typography(
        displayLarge = style(30f),
        displayMedium = style(26f),
        displaySmall = style(22f),
        headlineLarge = style(20f),
        headlineMedium = style(18f),
        headlineSmall = style(16f),
        titleLarge = style(15f),
        titleMedium = style(13f),
        titleSmall = style(12f),
        bodyLarge = style(13f),
        bodyMedium = style(12f),
        bodySmall = style(11f),
        labelLarge = style(12f),
        labelMedium = style(10f),
        labelSmall = style(9f),
    )
}

/** The visible tell drawn over a stalled app. Themeable, not removable. */
val TellColor = PhosphorDivider

@Composable
fun MolassesTheme(
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PhosphorColors,
        typography = phosphorTypography(fontScale),
        content = content,
    )
}
