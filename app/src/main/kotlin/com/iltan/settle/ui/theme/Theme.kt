package com.iltan.settle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Tidepool on Material 3 Expressive (ADR-016): the wallpaper's dynamic colours for the M3 roles,
 * fixed warning colours, Figtree and Tidepool's corners. Dynamic colour is always available because
 * minSdk is 35; [dynamicColor] = false gives the fixed Tidepool palette for tests and previews.
 */
@Composable
fun SettleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> TidepoolDark
            else -> TidepoolLight
        }
    CompositionLocalProvider(
        LocalSettleColors provides if (darkTheme) SettleColors.Dark else SettleColors.Light
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = SettleShapes,
            typography = SettleTypography,
            content = content,
        )
    }
}
