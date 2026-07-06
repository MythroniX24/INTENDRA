package com.interndra.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.interndra.data.model.Workspace
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

// Tab indices
private const val TAB_CHAT      = 0
private const val TAB_TERMINAL  = 1
private const val TAB_MEMORY    = 2
private const val TAB_SECURITY  = 3
private const val TAB_WORKSPACE = 4
private const val TAB_KNOWLEDGE = 5
private const val TAB_RESEARCH  = 6
private const val TAB_TIMELINE  = 7
private const val TAB_SETTINGS  = 8

@Composable
fun MainScreen(viewModel: HybridAgentViewModel) {
    var selectedTab = remember { mutableStateOf(TAB_CHAT) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope       = rememberCoroutineScope()
    val uiState     by viewModel.uiState.collectAsState()
    val workspaces  by viewModel.workspaces.collectAsState()
    val memCount    by remember { derivedStateOf { uiState.memoryCount } }
    val vaultCount  by remember { derivedStateOf { uiState.knowledgeCount } }

    fun navigateTo(tab: Int) {
        selectedTab.value = tab
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CardSurface,
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(Modifier.height(24.dp))

                // ── App title + emergency lock badge ──────────────────────
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "INTERNDRA",
                        color      = TerminalWhite,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier   = Modifier.weight(1f)
                    )
                    if (uiState.emergencyLockActive) {
                        Surface(shape = RoundedCornerShape(50), color = TerminalRed.copy(alpha = 0.2f)) {
                            Text("🔒 LOCKED", color = TerminalRed, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }

                // AI OS mode indicator
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    shape    = RoundedCornerShape(8.dp),
                    color    = SurfaceLight
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.privacyMode.emoji, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(uiState.privacyMode.label, color = TerminalWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (uiState.localModelReady) "Local AI ready" else "Cloud only",
                                color = if (uiState.localModelReady) TerminalGreen else TerminalYellow,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // ── Workspace section ─────────────────────────────────────
                if (workspaces.isNotEmpty()) {
                    HorizontalDivider(color = SurfaceLight, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    SectionHeader("WORKSPACES")
                    workspaces.take(5).forEach { ws ->
                        DrawerWorkspaceItem(ws, uiState.activeWorkspaceId == ws.id) {
                            viewModel.switchWorkspace(ws.id, ws.name)
                            selectedTab.value = TAB_CHAT
                            scope.launch { drawerState.close() }
                        }
                    }
                }

                Button(
                    onClick = { navigateTo(TAB_WORKSPACE) },
                    colors  = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manage Workspaces", color = TerminalWhite, fontSize = 13.sp)
                }

                // ── Core navigation ───────────────────────────────────────
                HorizontalDivider(color = SurfaceLight, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SectionHeader("CORE")

                DrawerItem(icon = Icons.AutoMirrored.Filled.Chat, label = "Chat", selected = selectedTab.value == TAB_CHAT)
                    { navigateTo(TAB_CHAT) }
                DrawerItem(icon = Icons.Default.Terminal, label = "Terminal Logs", selected = selectedTab.value == TAB_TERMINAL)
                    { navigateTo(TAB_TERMINAL) }
                DrawerItem(icon = Icons.Default.Psychology, label = "Memory ($memCount)", selected = selectedTab.value == TAB_MEMORY)
                    { navigateTo(TAB_MEMORY) }

                // ── Knowledge OS section ──────────────────────────────────
                HorizontalDivider(color = SurfaceLight, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SectionHeader("KNOWLEDGE OS")

                DrawerItem(icon = Icons.Default.Book, label = "Knowledge Vault ($vaultCount)", selected = selectedTab.value == TAB_KNOWLEDGE)
                    { navigateTo(TAB_KNOWLEDGE) }
                DrawerItem(icon = Icons.Default.Science, label = "Research Dashboard", selected = selectedTab.value == TAB_RESEARCH)
                    { navigateTo(TAB_RESEARCH) }
                DrawerItem(icon = Icons.Default.Timeline, label = "Timeline", selected = selectedTab.value == TAB_TIMELINE)
                    { navigateTo(TAB_TIMELINE) }

                // ── System section ────────────────────────────────────────
                HorizontalDivider(color = SurfaceLight, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SectionHeader("SYSTEM")

                DrawerItem(icon = Icons.Default.Security, label = "Security & Privacy", selected = selectedTab.value == TAB_SECURITY)
                    { navigateTo(TAB_SECURITY) }

                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = SurfaceLight, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                DrawerItem(icon = Icons.Default.Settings, label = "Settings", selected = selectedTab.value == TAB_SETTINGS)
                    { navigateTo(TAB_SETTINGS) }

                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = ChatBg) {
            Crossfade(targetState = selectedTab.value, label = "Tab") { tab ->
                when (tab) {
                    TAB_CHAT      -> HybridChatScreen(
                        vm                   = viewModel,
                        onOpenDrawer         = { scope.launch { drawerState.open() } },
                        onNavigateToTerminal = { selectedTab.value = TAB_TERMINAL }
                    )
                    TAB_TERMINAL  -> TerminalScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_MEMORY    -> MemoryDashboardScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_SECURITY  -> SecurityDashboardScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_WORKSPACE -> WorkspaceScreen(
                        vm = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onWorkspaceSelected = { ws ->
                            viewModel.switchWorkspace(ws.id, ws.name)
                            selectedTab.value = TAB_CHAT
                        }
                    )
                    TAB_KNOWLEDGE -> KnowledgeVaultScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_RESEARCH  -> ResearchDashboardScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_TIMELINE  -> TimelineScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                    TAB_SETTINGS  -> SettingsScreen(vm = viewModel, onOpenDrawer = { scope.launch { drawerState.open() } })
                }
            }
        }
    }
}

// ── Shared drawer composables ──────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color      = TerminalWhite.copy(alpha = 0.4f),
        fontSize   = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
    )
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon     = { Icon(icon, contentDescription = null) },
        label    = { Text(label) },
        selected = selected,
        onClick  = onClick,
        colors   = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor   = SurfaceLight,
            selectedTextColor        = TerminalWhite,
            unselectedTextColor      = TerminalWhite,
            selectedIconColor        = Accent,
            unselectedIconColor      = TerminalWhite.copy(alpha = 0.6f)
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape    = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun DrawerWorkspaceItem(
    workspace: Workspace,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon     = { Text(workspace.emoji, fontSize = 16.sp) },
        label    = { Text(workspace.name, maxLines = 1) },
        selected = selected,
        onClick  = onClick,
        colors   = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor   = SurfaceLight,
            selectedTextColor        = TerminalWhite,
            unselectedTextColor      = TerminalWhite.copy(alpha = 0.7f),
            selectedIconColor        = Accent,
            unselectedIconColor      = TerminalWhite.copy(alpha = 0.6f)
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape    = RoundedCornerShape(12.dp)
    )
}
