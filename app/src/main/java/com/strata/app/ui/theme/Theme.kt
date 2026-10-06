package com.strata.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

internal val StrataShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Material 3 Expressive with our own colours and type. Wallpaper-based dynamic colour is
 * deliberately off so asset-class colours always read the same.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StrataTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStrataColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            motionScheme = MotionScheme.expressive(),
            shapes = StrataShapes,
            typography = StrataTypography,
            content = content,
        )
    }
}

object StrataTheme {
    val colors: StrataColors
        @Composable get() = LocalStrataColors.current
}
