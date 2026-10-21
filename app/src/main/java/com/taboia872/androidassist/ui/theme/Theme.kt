package com.taboia872.androidassist.ui.theme

import android.os.Build
import com.taboia872.androidassist.settings.AppSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = AIDarkPrimary,
    onPrimary = AIDarkOnPrimary,
    primaryContainer = AIDarkPrimaryContainer,
    onPrimaryContainer = AIDarkOnPrimaryContainer,
    secondary = AIDarkSecondary,
    onSecondary = AIDarkOnSecondary,
    secondaryContainer = AIDarkSecondaryContainer,
    onSecondaryContainer = AIDarkOnSecondaryContainer,
    tertiary = AIDarkTertiary,
    onTertiary = AIDarkOnTertiary,
    tertiaryContainer = AIDarkTertiaryContainer,
    onTertiaryContainer = AIDarkOnTertiaryContainer,
    background = AIDarkBackground,
    onBackground = AIDarkOnBackground,
    surface = AIDarkSurface,
    onSurface = AIDarkOnSurface,
    surfaceVariant = AIDarkSurfaceVariant,
    onSurfaceVariant = AIDarkOnSurfaceVariant,
    outline = AIDarkOutline,
    error = AIDarkError,
    onError = AIDarkOnError,
    errorContainer = AIDarkErrorContainer,
    onErrorContainer = AIDarkOnErrorContainer
)

private val LightColorScheme = lightColorScheme(
    primary = AILightPrimary,
    onPrimary = AILightOnPrimary,
    primaryContainer = AILightPrimaryContainer,
    onPrimaryContainer = AILightOnPrimaryContainer,
    secondary = AILightSecondary,
    onSecondary = AILightOnSecondary,
    secondaryContainer = AILightSecondaryContainer,
    onSecondaryContainer = AILightOnSecondaryContainer,
    tertiary = AILightTertiary,
    onTertiary = AILightOnTertiary,
    tertiaryContainer = AILightTertiaryContainer,
    onTertiaryContainer = AILightOnTertiaryContainer,
    background = AILightBackground,
    onBackground = AILightOnBackground,
    surface = AILightSurface,
    onSurface = AILightOnSurface,
    surfaceVariant = AILightSurfaceVariant,
    onSurfaceVariant = AILightOnSurfaceVariant,
    outline = AILightOutline,
    error = AILightError,
    onError = AILightOnError,
    errorContainer = AILightErrorContainer,
    onErrorContainer = AILightOnErrorContainer
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Builds a Material3 scheme from a single ACCENT color. Tinted variants are
 * derived by blending the accent with white/black (tonal palette approx) —
 * enough for a chat app identity without a full tonal generator.
 */
private fun schemeFromAccent(accent: Color, dark: Boolean): ColorScheme {
    fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )
    val base = if (dark) Color(0xFF141414) else Color(0xFFFBFBFB)
    val onBase = if (dark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val container = if (dark) mix(accent, Color.Black, 0.72f) else mix(accent, Color.White, 0.82f)
    val onContainer = if (dark) mix(accent, Color.White, 0.1f) else mix(accent, Color.Black, 0.55f)
    val surfaceVar = if (dark) mix(base, accent, 0.10f) else mix(base, accent, 0.07f)
    val outline = if (dark) mix(onBase, Color.Black, 0.55f) else mix(onBase, Color.White, 0.55f)
    return if (dark) darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = mix(accent, Color(0xFF666666), 0.5f),
        onSecondary = Color.Black,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        background = base,
        onBackground = onBase,
        surface = base,
        onSurface = onBase,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = mix(onBase, Color.Black, 0.2f),
        outline = outline,
        error = Color(0xFFE5484D),
        onError = Color.Black,
        errorContainer = Color(0xFF4C1A1D),
        onErrorContainer = Color(0xFFFFD9D9)
    ) else lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = mix(accent, Color(0xFF888888), 0.4f),
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        background = base,
        onBackground = onBase,
        surface = base,
        onSurface = onBase,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = mix(onBase, Color.White, 0.25f),
        outline = outline,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002)
    )
}

/** Accent presets shown in the picker (order = UI order). */
val ACCENT_PRESETS = listOf(
    "" to null as Color?,            // "" = default teal identity
    "teal" to Color(0xFF00897B),
    "blue" to Color(0xFF1E88E5),
    "purple" to Color(0xFF8E24AA),
    "pink" to Color(0xFFD81B60),
    "red" to Color(0xFFE53935),
    "orange" to Color(0xFFFB8C00),
    "green" to Color(0xFF43A047)
)

fun accentColorOf(id: String): Color? =
    ACCENT_PRESETS.firstOrNull { it.first == id }?.second

@Composable
fun AndroidAssistTheme(
    content: @Composable () -> Unit
) {
    // COLLECT the flow: reading .value once froze the theme at composition —
    // changing it in Settings only applied after an app restart (user report).
    val cfg by AppSettings.config.collectAsState()
    val themePref = cfg.appTheme
    val accentPref = cfg.accentColor
    val systemDark = isSystemInDarkTheme()
    // "" = follow system with the static teal identity (no wallpaper tint);
    // "dynamic" = Material You wallpaper colors, still following light/dark.
    val darkTheme = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val dynamicColor = themePref == "dynamic" &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val accent = accentColorOf(accentPref)
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        accent != null -> schemeFromAccent(accent, darkTheme)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
        content = content
    )
}
