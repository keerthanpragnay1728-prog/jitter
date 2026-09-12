package dev.molasses.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately muted and slightly drab. The gate is an interruption, not a
 * reward screen; anything bright would read as a notification worth engaging
 * with.
 */
private val Molasses = Color(0xFF3A2E22)
private val MolassesLight = Color(0xFFF2E4C9)
private val Amber = Color(0xFFB07A32)
private val Slate = Color(0xFF8C8C96)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1408),
    secondary = Slate,
    background = Color(0xFF14100B),
    onBackground = MolassesLight,
    surface = Molasses,
    onSurface = MolassesLight,
)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    secondary = Slate,
    background = Color(0xFFFBF7EF),
    onBackground = Molasses,
    surface = Color(0xFFF3ECDE),
    onSurface = Molasses,
)

/** The visible tell drawn over a stalled app. Themeable, not removable. */
val TellColor = Slate

@Composable
fun MolassesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
