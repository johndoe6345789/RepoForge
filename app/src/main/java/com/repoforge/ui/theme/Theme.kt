package com.repoforge.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Indigo seed palette, used when dynamic colour is off or unavailable (Android 11 and below).
private val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    tertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7),
    tertiaryContainer = Color(0xFF5D3C55),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
)

/** State colours that read the same across providers: open, merged, closed. */
object StateColors {
    val open = Color(0xFF2DA44E)
    val merged = Color(0xFF8250DF)
    val closed = Color(0xFFCF222E)
    val draft = Color(0xFF6E7781)
}

/** Diff tints, chosen per theme so added/removed lines stay readable in both. */
data class DiffColors(val added: Color, val removed: Color, val addedText: Color, val removedText: Color)

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun diffColors(): DiffColors = if (LocalDarkTheme.current) {
    DiffColors(Color(0x332EA043), Color(0x33F85149), Color(0xFF7EE787), Color(0xFFFFA198))
} else {
    DiffColors(Color(0xFFE6FFEC), Color(0xFFFFEBE9), Color(0xFF1A7F37), Color(0xFFCF222E))
}

@Composable
fun RepoForgeTheme(dark: Boolean, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
