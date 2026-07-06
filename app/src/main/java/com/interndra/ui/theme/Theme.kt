package com.interndra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary          = Accent,
    secondary        = TerminalGreen,
    tertiary         = TerminalYellow,
    background       = ChatBg,
    surface          = CardSurface,
    surfaceVariant   = SurfaceLight,
    onPrimary        = ChatBg,
    onSecondary      = ChatBg,
    onBackground     = TerminalWhite,
    onSurface        = TerminalWhite,
    error            = TerminalRed,
    onError          = ChatBg
)

@Composable
fun InterndraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = InterndraTypography,
        content     = content
    )
}
