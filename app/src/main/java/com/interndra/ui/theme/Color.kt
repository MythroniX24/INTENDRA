package com.interndra.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

// ═══════════════════════════════════════════════════════════════════════════════
// INTERNDRA Design System — v4.0 Dual-Palette (Dark + Light)
// ═══════════════════════════════════════════════════════════════════════════════
// Usage: Instantiate InterndraColors(isLight = ...) and access colors directly.
// Compose-level helpers (colors(), isDarkTheme()) are in Colors.kt
// ═══════════════════════════════════════════════════════════════════════════════

@Immutable
data class InterndraColors(
    val isLight: Boolean
) {
    // ── Core backgrounds ──────────────────────────────────────────────────
    val backgroundDeepest: Color get() = if (isLight) Color(0xFFF5F5F7) else Color(0xFF0A0A0A)   // Deepest bg (scrrim, modal overlay)
    val backgroundDefault: Color get() = if (isLight) Color(0xFFF5F5F7) else Color(0xFF0F0F0F)  // Default screen bg
    val backgroundElevated: Color get() = if (isLight) Color(0xFFE8E8ED) else Color(0xFF141416) // Slightly elevated

    // ── Surface palette ─────────────────────────────────────────────────────
    val surfaceCard: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF1C1D20)        // Card surface
    val surfaceElevated: Color get() = if (isLight) Color(0xFFF2F2F5) else Color(0xFF232529)    // Elevated surface
    val surfaceInteractive: Color get() = if (isLight) Color(0xFFE5E5EA) else Color(0xFF2C2E33) // Interactive surface (hover/active)

    // ── Glass / Frosted surfaces ───────────────────────────────────────────
    val glassOverlay: Color get() = if (isLight) Color(0x0A000000) else Color(0x1AFFFFFF)        // Subtle overlay
    val glassBorder: Color get() = if (isLight) Color(0x14000000) else Color(0x26FFFFFF)          // Border overlay
    val glassSurface: Color get() = if (isLight) Color(0x05000000) else Color(0x0DFFFFFF)         // Subtle glass

    // ── Terminal palette (shared across themes) ──────────────────────────────
    val terminalGreen: Color get() = Color(0xFF81C995)
    val terminalBlue: Color get() = Color(0xFF8AB4F8)
    val terminalYellow: Color get() = Color(0xFFFDE293)
    val terminalRed: Color get() = Color(0xFFF28B82)
    val terminalWhite: Color get() = if (isLight) Color(0xFF1C1C1E) else Color(0xFFE8EAED)
    val terminalBg: Color get() = if (isLight) Color(0xFFF0F0F2) else Color(0xFF0D0D0D)

    // ── Accent ───────────────────────────────────────────────────────────────
    val accent: Color get() = if (isLight) Color(0xFF3578E4) else Color(0xFF8AB4F8)              // Primary interactive
    val accentDark: Color get() = if (isLight) Color(0xFF2860C0) else Color(0xFF5A8ADC)          // Pressed state
    val accentGlow: Color get() = if (isLight) Color(0xFFD6E4FF) else Color(0xFFB8D4FC)          // Glow/highlight

    // ── Gradient pairs ─────────────────────────────────────────────────────
    val gradientChatStart: Color get() = if (isLight) Color(0xFF3578E4) else Color(0xFF8AB4F8)
    val gradientChatEnd: Color get() = if (isLight) Color(0xFF7C3AED) else Color(0xFFA78BFA)
    val gradientTermStart: Color get() = if (isLight) Color(0xFF34A853) else Color(0xFF81C995)
    val gradientTermEnd: Color get() = if (isLight) Color(0xFF1E8E3E) else Color(0xFF4CAF50)
    val gradientVaultStart: Color get() = if (isLight) Color(0xFFF9AB00) else Color(0xFFFAD165)
    val gradientVaultEnd: Color get() = if (isLight) Color(0xFFE37400) else Color(0xFFF59E0B)

    // ── Chat bubbles ─────────────────────────────────────────────────────────
    val userBubbleBg: Color get() = if (isLight) Color(0xFF3578E4) else Color(0xFF2C2E33)
    val aiBubbleBg: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF1A1B1E)
    val aiBubbleBorder: Color get() = if (isLight) Color(0xFFE5E5EA) else Color(0xFF2C2E33).copy(alpha = 0.3f)
    val userBubbleText: Color get() = if (isLight) Color.White else Color(0xFFE8EAED)
    val aiBubbleText: Color get() = if (isLight) Color(0xFF1C1C1E) else Color(0xFFE8EAED)
    val userBubbleGradientStart: Color get() = if (isLight) Color(0xFF3578E4) else Color(0xFF3B82F6)
    val userBubbleGradientEnd: Color get() = if (isLight) Color(0xFF7C3AED) else Color(0xFF8B5CF6)

    // ── Knowledge Vault palette ───────────────────────────────────────────────
    val vaultGold: Color get() = if (isLight) Color(0xFFF9AB00) else Color(0xFFFAD165)
    val vaultPurple: Color get() = if (isLight) Color(0xFF9334E6) else Color(0xFFCB98F8)
    val vaultCyan: Color get() = if (isLight) Color(0xFF0097A7) else Color(0xFF78D9EC)

    // ── Status ────────────────────────────────────────────────────────────────
    val statusOnline: Color get() = terminalGreen
    val statusOffline: Color get() = terminalRed
    val statusIdle: Color get() = terminalYellow

    // ── Semantic colors (shared) ──────────────────────────────────────────────
    val success: Color get() = Color(0xFF81C995)
    val danger: Color get() = Color(0xFFF28B82)
    val warning: Color get() = Color(0xFFFDE293)
    val info: Color get() = if (isLight) Color(0xFF3578E4) else Color(0xFF8AB4F8)

    // ── Elevation shadows ───────────────────────────────────────────────────
    val shadowSmall: Color get() = if (isLight) Color(0x0A000000) else Color(0x0A000000)   // 4% black
    val shadowMedium: Color get() = if (isLight) Color(0x14000000) else Color(0x140000000)  // 8% black
    val shadowLarge: Color get() = if (isLight) Color(0x1E000000) else Color(0x1E000000)    // 12% black

    // ── Code block ────────────────────────────────────────────────────────────
    val codeBlockBg: Color get() = if (isLight) Color(0xFFF0F0F2) else Color(0xFF1A1B1E)
    val codeBlockHeader: Color get() = if (isLight) Color(0xFFE5E5EA) else Color(0xFF2A2B30)
    val codeBlockBorder: Color get() = if (isLight) Color(0xFFD1D1D6) else Color(0xFF2C2E33)
    val inlineCodeBg: Color get() = if (isLight) Color(0xFFE5E5EA) else Color(0xFF2C2E33).copy(alpha = 0.4f)
    val inlineCodeText: Color get() = if (isLight) Color(0xFFC41D6F) else Color(0xFFF28B82)

    // ── Input bar ─────────────────────────────────────────────────────────────
    val inputBarBg: Color get() = if (isLight) Color(0xFFF0F0F2) else Color(0xFF0F0F0F)
    val inputFieldBg: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF1A1B1E)
    val inputFieldBorder: Color get() = if (isLight) Color(0xFFD1D1D6) else Color(0xFF2A2A2A)
    val inputPlaceholder: Color get() = if (isLight) Color(0xFF8E8E93) else Color(0xFF666666)
    val inputTextColor: Color get() = if (isLight) Color(0xFF1C1C1E) else Color.White

    // ── Divider / separator ───────────────────────────────────────────────────
    val divider: Color get() = if (isLight) Color(0xFFD1D1D6) else Color(0xFF2C2E33).copy(alpha = 0.3f)
}

// ═══════════════════════════════════════════════════════════════════════════════
// Legacy direct color references (backward-compat for existing code)
// ═══════════════════════════════════════════════════════════════════════════════

val Background900     get() = Color(0xFF0A0A0A)
val Background800     get() = Color(0xFF0F0F0F)
val Background700     get() = Color(0xFF141416)
val SurfaceCard       get() = Color(0xFF1C1D20)
val SurfaceElevated   get() = Color(0xFF232529)
val SurfaceLight      get() = Color(0xFF2C2E33)
val GlassOverlay      get() = Color(0x1AFFFFFF)
val GlassBorder       get() = Color(0x26FFFFFF)
val GlassSurface      get() = Color(0x0DFFFFFF)
val TerminalGreen     get() = Color(0xFF81C995)
val TerminalBlue      get() = Color(0xFF8AB4F8)
val TerminalYellow    get() = Color(0xFFFDE293)
val TerminalRed       get() = Color(0xFFF28B82)
val TerminalWhite     get() = Color(0xFFE8EAED)
val TerminalBg        get() = Color(0xFF0D0D0D)
val Accent             get() = Color(0xFF8AB4F8)
val AccentDark         get() = Color(0xFF5A8ADC)
val AccentGlow         get() = Color(0xFFB8D4FC)
val GradientChatStart  get() = Color(0xFF8AB4F8)
val GradientChatEnd    get() = Color(0xFFA78BFA)
val GradientTermStart  get() = Color(0xFF81C995)
val GradientTermEnd    get() = Color(0xFF4CAF50)
val GradientVaultStart get() = Color(0xFFFAD165)
val GradientVaultEnd   get() = Color(0xFFF59E0B)
val UserBubble         get() = Color(0xFF2C2E33)
val AiBubble           get() = Color.Transparent
val UserBubbleGradientStart get() = Color(0xFF3B82F6)
val UserBubbleGradientEnd   get() = Color(0xFF8B5CF6)
val VaultGold          get() = Color(0xFFFAD165)
val VaultPurple        get() = Color(0xFFCB98F8)
val VaultCyan          get() = Color(0xFF78D9EC)
val StatusOnline       get() = TerminalGreen
val StatusOffline      get() = TerminalRed
val StatusIdle         get() = TerminalYellow
val Success            get() = Color(0xFF81C995)
val Danger             get() = Color(0xFFF28B82)
val Warning            get() = Color(0xFFFDE293)
val ShadowSmall        get() = Color(0x0A000000)
val ShadowMedium       get() = Color(0x14000000)
val ShadowLarge        get() = Color(0x1E000000)

// ── Helper: evaluate InterndraColors based on current theme  ────────────
// Injected via CompositionLocal by Theme.kt. Defaults to dark mode.
val LocalInterndraColors = androidx.compose.runtime.compositionLocalOf {
    InterndraColors(isLight = false)
}
