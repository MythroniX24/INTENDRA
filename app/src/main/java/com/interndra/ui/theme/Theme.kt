package com.interndra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary          = Accent,
    secondary        = TerminalGreen,
    tertiary         = TerminalYellow,
    background       = Background800,
    surface          = SurfaceCard,
    surfaceVariant   = SurfaceLight,
    onPrimary        = Background900,
    onSecondary      = Background900,
    onBackground     = TerminalWhite,
    onSurface        = TerminalWhite,
    error            = TerminalRed,
    onError          = Background900
)

@Composable
fun InterndraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = InterndraTypography,
        content     = content
    )
}
