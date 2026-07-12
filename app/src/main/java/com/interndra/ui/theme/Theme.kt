package com.interndra.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.ui.platform.LocalContext

// ═══════════════════════════════════════════════════════════════════════════════
// INTERNDRA Theme — v4.0 Dynamic + Dual-Palette
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Theme mode for the app.
 * - SYSTEM: follow device dark mode setting
 * - DARK: force dark mode
 * - LIGHT: force light mode
 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Material3 color schemes — hand-crafted dark + light variants.
 * These use INTERNDRA's semantic colors while mapping to Material3 roles.
 */
private val InterndraDarkScheme = darkColorScheme(
    primary          = Accent,
    onPrimary        = Background900,
    primaryContainer = Accent.copy(alpha = 0.12f),
    onPrimaryContainer = AccentGlow,
    secondary        = TerminalGreen,
    onSecondary      = Background900,
    secondaryContainer = TerminalGreen.copy(alpha = 0.12f),
    onSecondaryContainer = TerminalGreen,
    tertiary         = TerminalYellow,
    onTertiary       = Background900,
    background       = Background800,
    onBackground     = TerminalWhite,
    surface          = SurfaceCard,
    onSurface        = TerminalWhite,
    surfaceVariant   = SurfaceLight,
    onSurfaceVariant = TerminalWhite.copy(alpha = 0.6f),
    outline          = SurfaceLight.copy(alpha = 0.4f),
    outlineVariant   = SurfaceLight.copy(alpha = 0.2f),
    error            = TerminalRed,
    onError          = Background900,
    errorContainer   = TerminalRed.copy(alpha = 0.12f),
    onErrorContainer = TerminalRed,
    inverseSurface   = TerminalWhite,
    inverseOnSurface = Background800,
    inversePrimary   = AccentDark
)

private val InterndraLightScheme = lightColorScheme(
    primary          = Color(0xFF3578E4), // Accent (light)
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF1A4B8C),
    secondary        = Color(0xFF34A853),
    onSecondary      = Color.White,
    secondaryContainer = Color(0xFFCEEAD6),
    onSecondaryContainer = Color(0xFF1B5E20),
    tertiary         = Color(0xFFF9AB00),
    onTertiary       = Color.White,
    background       = Color(0xFFF5F5F7),
    onBackground     = Color(0xFF1C1C1E),
    surface          = Color.White,
    onSurface        = Color(0xFF1C1C1E),
    surfaceVariant   = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    outline          = Color(0xFFD1D1D6),
    outlineVariant   = Color(0xFFE5E5EA),
    error            = Color(0xFFD93025),
    onError          = Color.White,
    errorContainer   = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF8B1A1A),
    inverseSurface   = Color(0xFF1C1C1E),
    inverseOnSurface = Color.White,
    inversePrimary   = Color(0xFF8AB4F8)
)

// ═══════════════════════════════════════════════════════════════════════════════
// Entry point — InterndraTheme
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * INTERNDRA's main theme composable.
 *
 * @param themeMode force a specific mode; if null, follows system.
 * @param dynamicColor use Material You dynamic colors (Android 12+).
 * @param content composable content to wrap.
 */
@Composable
fun InterndraTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val interndraColors = InterndraColors(isLight = !isDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        isDark -> InterndraDarkScheme
        else -> InterndraLightScheme
    }

    CompositionLocalProvider(LocalInterndraColors provides interndraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = InterndraTypography,
            content     = content
        )
    }
}
