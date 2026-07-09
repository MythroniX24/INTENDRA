package com.interndra.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.PrivacyMode
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

// ── Tab definition ─────────────────────────────────────────────────────────
enum class AppTab(
    val icon: ImageVector,
    val activeIcon: ImageVector,
    val label: String,
    val badge: ((HybridAgentViewModel) -> String)? = null
) {
    CHAT(Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Filled.Chat, "Chat"),
    TERMINAL(Icons.Default.Terminal, Icons.Default.Terminal, "Terminal"),
    VAULT(Icons.Default.Book, Icons.Default.Book, "Vault", { vm ->
        val count = vm.uiState.value.knowledgeCount
        if (count > 0) count.toString() else ""
    }),
    DASHBOARDS(Icons.Default.Dashboard, Icons.Default.Dashboard, "Dashboards"),
    SETTINGS(Icons.Default.Settings, Icons.Default.Settings, "Settings")
}

// ── AppShell — Unified layout with bottom navigation ──────────────────────
@Composable
fun AppShell(
    vm: HybridAgentViewModel,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val uiState by vm.uiState.collectAsState()
    val activeWorkspaceName by remember { derivedStateOf { uiState.activeWorkspaceName } }
    val provider by vm.aiProvider.collectAsState()
    val privacyMode by vm.privacyMode.collectAsState()
    val shizukuAuth by vm.shizukuAuthorized.collectAsState()
    val shizukuAvail by vm.shizukuAvailable.collectAsState()

    Scaffold(
        containerColor = Background800,
        topBar = {
            AppTopBar(
                title = selectedTab.label,
                subTitle = activeWorkspaceName.takeIf { it != "General" },
                onOpenDrawer = onOpenDrawer,
                provider = provider,
                privacyMode = privacyMode,
                emergencyLock = uiState.emergencyLockActive,
                localModelReady = uiState.localModelReady
            )
        },
        bottomBar = {
            AppBottomNav(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                onOpenTerminal = onOpenTerminal
            )
        },
        content = content
    )
}

// ── App Top Bar ────────────────────────────────────────────────────────────
@Composable
private fun AppTopBar(
    title: String,
    subTitle: String?,
    onOpenDrawer: () -> Unit,
    provider: com.interndra.util.Constants.AiProvider,
    privacyMode: PrivacyMode,
    emergencyLock: Boolean,
    localModelReady: Boolean
) {
    Surface(
        color = Background800,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Menu button
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }

                // Title
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            color = TerminalWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (emergencyLock) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(TerminalRed)
                            )
                        }
                    }
                    if (subTitle != null) {
                        Text(
                            subTitle,
                            color = TerminalWhite.copy(alpha = 0.4f),
                            fontSize = 11.sp
                        )
                    }
                }

                // Status chips
                StatusChip(
                    text = if (emergencyLock) "🔒 Locked"
                           else "${privacyMode.emoji} ${privacyMode.label.take(6)}",
                    color = when {
                        emergencyLock -> TerminalRed
                        privacyMode == PrivacyMode.LOCAL_ONLY -> TerminalGreen
                        else -> Accent
                    }
                )
                Spacer(Modifier.width(6.dp))
                StatusChip(
                    text = when (provider) {
                        com.interndra.util.Constants.AiProvider.GEMINI -> "🧬 Gemini"
                        com.interndra.util.Constants.AiProvider.OPENROUTER -> "⚡ OR"
                    },
                    color = when (provider) {
                        com.interndra.util.Constants.AiProvider.GEMINI -> TerminalGreen
                        com.interndra.util.Constants.AiProvider.OPENROUTER -> Accent
                    }
                )
                if (localModelReady) {
                    Spacer(Modifier.width(6.dp))
                    StatusChip(text = "📱 Local", color = TerminalBlue)
                }
            }

            // Subtle bottom border
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(TerminalWhite.copy(alpha = 0.08f))
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ── Bottom Navigation ──────────────────────────────────────────────────────
@Composable
private fun AppBottomNav(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onOpenTerminal: () -> Unit
) {
    Surface(
        color = Background800,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            // Subtle top border
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(TerminalWhite.copy(alpha = 0.06f))
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (tab == AppTab.TERMINAL) onOpenTerminal()
                                onTabSelected(tab)
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .drawBehind {
                                        if (isSelected) {
                                            drawRoundRect(
                                                color = Accent.copy(alpha = 0.12f),
                                                cornerRadius = CornerRadius(6f, 6f)
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isSelected) tab.activeIcon else tab.icon,
                                    tab.label,
                                    tint = if (isSelected) AccentGlow
                                           else TerminalWhite.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tab.label,
                                color = if (isSelected) Accent
                                        else TerminalWhite.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold
                                             else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Elegant Gradient Divider ───────────────────────────────────────────────
@Composable
fun GradientDivider(alpha: Float = 0.3f) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        TerminalWhite.copy(alpha = alpha),
                        Color.Transparent
                    )
                )
            )
    )
}
