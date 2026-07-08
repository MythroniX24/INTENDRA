package com.interndra.ui.theme

import androidx.compose.ui.graphics.Color

// ── Terminal palette ────────────────────────────────────────────────────────
val TerminalGreen  = Color(0xFF81C995)
val TerminalBlue   = Color(0xFF8AB4F8)
val TerminalYellow = Color(0xFFFDE293)
val TerminalRed    = Color(0xFFF28B82)
val TerminalWhite  = Color(0xFFE8EAED)
val TerminalBg     = Color(0xFF0D0D0D)  // Terminal background

// ── Surface palette ─────────────────────────────────────────────────────────
val ChatBg       = Color(0xFF0F0F0F)  // ultra-dark background
val CardSurface  = Color(0xFF1E1F20)  // card / drawer background
val SurfaceLight = Color(0xFF282A2C)  // elevated surface

// ── Chat bubbles ─────────────────────────────────────────────────────────────
val UserBubble = Color(0xFF282A2C)
val AiBubble   = Color.Transparent

// ── Accent ───────────────────────────────────────────────────────────────────
val Accent = Color(0xFF8AB4F8)        // primary interactive colour

// ── Knowledge Vault palette ───────────────────────────────────────────────────
val VaultGold   = Color(0xFFFAD165)   // knowledge entries
val VaultPurple = Color(0xFFCB98F8)   // graph / connections
val VaultCyan   = Color(0xFF78D9EC)   // RAG / research

// ── Status ────────────────────────────────────────────────────────────────────
val StatusOnline  = TerminalGreen
val StatusOffline = TerminalRed
val StatusIdle    = TerminalYellow

// ── Semantic colors (Phase 3 — rich markdown renderer) ──────────────────────
val Success = Color(0xFF81C995)   // green — success callouts, checklists
val Danger  = Color(0xFFF28B82)   // red — danger callouts, errors
val Warning = Color(0xFFFDE293)   // yellow — warning callouts
