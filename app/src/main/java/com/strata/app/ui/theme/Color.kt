package com.strata.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Strata: an ink-cobalt base with saturated mineral accents.

val Cobalt = Color(0xFF2E3FE6)
val CobaltDeep = Color(0xFF1C27B0)

internal val LightColors = lightColorScheme(
    primary = Cobalt,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF0B1680),
    inversePrimary = Color(0xFF9AA6FF),
    secondary = Color(0xFFD8461F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCD1),
    onSecondaryContainer = Color(0xFF561303),
    tertiary = Color(0xFF00876E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFF2E2),
    onTertiaryContainer = Color(0xFF003B30),
    error = Color(0xFFCC1F3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADC),
    onErrorContainer = Color(0xFF5E0414),
    background = Color(0xFFF3F4FB),
    onBackground = Color(0xFF121638),
    surface = Color(0xFFF3F4FB),
    onSurface = Color(0xFF121638),
    surfaceVariant = Color(0xFFE2E5F4),
    onSurfaceVariant = Color(0xFF51587F),
    surfaceTint = Cobalt,
    inverseSurface = Color(0xFF1B2147),
    inverseOnSurface = Color(0xFFEEF0FF),
    outline = Color(0xFF8189B0),
    outlineVariant = Color(0xFFD0D4EA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDADDEE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EBF7),
    surfaceContainerHighest = Color(0xFFDFE2F2),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF9DA8FF),
    onPrimary = Color(0xFF0C1470),
    primaryContainer = Color(0xFF2E3FE6),
    onPrimaryContainer = Color(0xFFE7E9FF),
    inversePrimary = Cobalt,
    secondary = Color(0xFFFF8D69),
    onSecondary = Color(0xFF4A1000),
    secondaryContainer = Color(0xFF7A2510),
    onSecondaryContainer = Color(0xFFFFDCD1),
    tertiary = Color(0xFF3FD8B0),
    onTertiary = Color(0xFF00382C),
    tertiaryContainer = Color(0xFF005A4A),
    onTertiaryContainer = Color(0xFFBFF2E2),
    error = Color(0xFFFF6B80),
    onError = Color(0xFF5E0414),
    errorContainer = Color(0xFF8A1328),
    onErrorContainer = Color(0xFFFFDADC),
    background = Color(0xFF0A0E26),
    onBackground = Color(0xFFECEEFF),
    surface = Color(0xFF0A0E26),
    onSurface = Color(0xFFECEEFF),
    surfaceVariant = Color(0xFF232C5C),
    onSurfaceVariant = Color(0xFFA6ADD8),
    surfaceTint = Color(0xFF9DA8FF),
    inverseSurface = Color(0xFFECEEFF),
    inverseOnSurface = Color(0xFF151A3C),
    outline = Color(0xFF6A72A3),
    outlineVariant = Color(0xFF2A3363),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A3466),
    surfaceDim = Color(0xFF0A0E26),
    surfaceContainerLowest = Color(0xFF070A1D),
    surfaceContainerLow = Color(0xFF10163A),
    surfaceContainer = Color(0xFF141B42),
    surfaceContainerHigh = Color(0xFF1C2450),
    surfaceContainerHighest = Color(0xFF252E5E),
)

/** Colours that Material's scheme has no slot for. */
@Immutable
data class StrataColors(
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val gain: Color,
    val loss: Color,
    val income: Color,
    val spending: Color,
    val gridLine: Color,
    val isDark: Boolean,
)

internal val LightExtras = StrataColors(
    hero = Cobalt,
    onHero = Color.White,
    onHeroMuted = Color(0xFFC9CEFF),
    gain = Color(0xFF00876E),
    loss = Color(0xFFCC1F3B),
    income = Color(0xFF00A07F),
    spending = Color(0xFFF0532D),
    gridLine = Color(0xFFDDE0EF),
    isDark = false,
)

internal val DarkExtras = StrataColors(
    hero = Color(0xFF2E3FE6),
    onHero = Color.White,
    onHeroMuted = Color(0xFFC9CEFF),
    gain = Color(0xFF3FD8B0),
    loss = Color(0xFFFF6B80),
    income = Color(0xFF0AAB80),
    spending = Color(0xFFE16949),
    gridLine = Color(0xFF222A57),
    isDark = true,
)

val LocalStrataColors = staticCompositionLocalOf { LightExtras }

/**
 * Curated series colours. Asset classes and spending categories store a key; each key has a
 * light and a dark variant tuned for its background.
 */
object SeriesPalette {
    data class Swatch(val key: String, val label: String, val light: Color, val dark: Color)

    /** Validated order: adjacent entries stay distinguishable under colour-vision deficiency. */
    val swatches = listOf(
        Swatch("cobalt", "Cobalt", Color(0xFF3346F0), Color(0xFF7986F2)),
        Swatch("saffron", "Saffron", Color(0xFFE39A00), Color(0xFFBC8D0E)),
        Swatch("magenta", "Magenta", Color(0xFFD12A7C), Color(0xFFE45799)),
        Swatch("sky", "Sky", Color(0xFF0E8FE0), Color(0xFF3A93E0)),
        Swatch("jade", "Jade", Color(0xFF00A07F), Color(0xFF0AAB80)),
        Swatch("coral", "Coral", Color(0xFFF0532D), Color(0xFFE16949)),
        Swatch("violet", "Violet", Color(0xFF7B3FF0), Color(0xFF9D77EF)),
        Swatch("lime", "Lime", Color(0xFF5E9A0C), Color(0xFF76A40A)),
        Swatch("graphite", "Graphite", Color(0xFF5A6185), Color(0xFF8B92BA)),
    )

    private val byKey = swatches.associateBy { it.key }

    fun color(key: String, dark: Boolean): Color {
        val swatch = byKey[key] ?: swatches.last()
        return if (dark) swatch.dark else swatch.light
    }
}

@Composable
fun seriesColor(key: String): Color = SeriesPalette.color(key, LocalStrataColors.current.isDark)
