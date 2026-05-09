package com.personalai.craig.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = CraigBlue,
    onPrimary        = Color.White,
    primaryContainer = CraigBlueDark,
    secondary        = CraigAccent,
    onSecondary      = Color.Black,
    background       = CraigBackground,
    surface          = CraigSurface,
    onSurface        = CraigOnSurface,
    error            = CraigError,
    onBackground     = CraigOnSurface
)

@Composable
fun CraigTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content
    )
}
