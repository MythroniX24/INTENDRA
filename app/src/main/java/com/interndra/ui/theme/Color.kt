package com.interndra.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════════
// INTERNDRA Design System — v3.0 Premium Dark Theme
// ═══════════════════════════════════════════════════════════════════════════════

// ── Core backgrounds ──────────────────────────────────────────────────────
val Background900 = Color(0xFF0A0A0A)  // Deepest background
val Background800 = Color(0xFF0F0F0F)  // Default screen bg (was ChatBg)
val Background700 = Color(0xFF141416)  // Slightly elevated

// ── Surface palette ─────────────────────────────────────────────────────────
val SurfaceCard   = Color(0xFF1C1D20)  // Card surface (was CardSurface)
val SurfaceElevated = Color(0xFF232529) // Elevated surface
val SurfaceLight  = Color(0xFF2C2E33)  // Interactive surface

// ── Glass / Frosted surfaces ───────────────────────────────────────────────
val GlassOverlay = Color(0x1AFFFFFF)   // 10% white overlay
val GlassBorder  = Color(0x26FFFFFF)   // 15% white border
val GlassSurface = Color(0x0DFFFFFF)   // 5% white — subtle glass

// ── Terminal palette ────────────────────────────────────────────────────────
val TerminalGreen  = Color(0xFF81C995)
val TerminalBlue   = Color(0xFF8AB4F8)
val TerminalYellow = Color(0xFFFDE293)
val TerminalRed    = Color(0xFFF28B82)
val TerminalWhite  = Color(0xFFE8EAED)
val TerminalBg     = Color(0xFF0D0D0D)  // Terminal background

// ── Accent ───────────────────────────────────────────────────────────────────
val Accent        = Color(0xFF8AB4F8)   // Primary interactive
val AccentDark    = Color(0xFF5A8ADC)   // Darker accent for pressed states
val AccentGlow    = Color(0xFFB8D4FC)   // Glow/highlight accent

// ── Gradient pairs ─────────────────────────────────────────────────────────
val GradientChatStart = Color(0xFF8AB4F8)
val GradientChatEnd   = Color(0xFFA78BFA)
val GradientTermStart = Color(0xFF81C995)
val GradientTermEnd   = Color(0xFF4CAF50)
val GradientVaultStart = Color(0xFFFAD165)
val GradientVaultEnd   = Color(0xFFF59E0B)

// ── Chat bubbles ─────────────────────────────────────────────────────────────
val UserBubble = Color(0xFF2C2E33)
val AiBubble   = Color.Transparent
val UserBubbleGradientStart = Color(0xFF3B82F6)
val UserBubbleGradientEnd   = Color(0xFF8B5CF6)

// ── Knowledge Vault palette ───────────────────────────────────────────────────
val VaultGold   = Color(0xFFFAD165)
val VaultPurple = Color(0xFFCB98F8)
val VaultCyan   = Color(0xFF78D9EC)

// ── Status ────────────────────────────────────────────────────────────────────
val StatusOnline  = TerminalGreen
val StatusOffline = TerminalRed
val StatusIdle    = TerminalYellow

// ── Semantic colors ────────────────────────────────────────────────────────
val Success = Color(0xFF81C995)
val Danger  = Color(0xFFF28B82)
val Warning = Color(0xFFFDE293)

// ── Elevation shadows ───────────────────────────────────────────────────────
val ShadowSmall  = Color(0x0A000000)  // 4% black
val ShadowMedium = Color(0x14000000)  // 8% black
val ShadowLarge  = Color(0x1E000000)  // 12% black
