package com.interndra.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.interndra.data.model.Workspace
import com.interndra.ui.components.AppShell
import com.interndra.ui.components.AppTab
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

@Composable
fun MainScreen(viewModel: HybridAgentViewModel) {
    var selectedAppTab by remember { mutableStateOf(AppTab.CHAT) }
    // Track sub-tab for DASHBOARDS tab
    var selectedDashboard by remember { mutableStateOf(0) } // 0=Memory, 1=Vault, 2=Research, 3=Timeline, 4=Security, 5=Workspace

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope       = rememberCoroutineScope()
    val uiState     by viewModel.uiState.collectAsState()
    val workspaces  by viewModel.workspaces.collectAsState()
    val memCount    by remember { derivedStateOf { uiState.memoryCount } }
    val vaultCount  by remember { derivedStateOf { uiState.knowledgeCount } }

    fun navigateToTab(tab: AppTab) {
        selectedAppTab = tab
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Background900,
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(Modifier.height(32.dp))

                // ── App Branding ──────────────────────────────────────────
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Accent, VaultPurple)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "INTERNDRA",
                            color = TerminalWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "AI OS v2.1",
                            color = TerminalWhite.copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (uiState.emergencyLockActive) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(TerminalRed)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Privacy Mode indicator ────────────────────────────────
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceCard
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        uiState.emergencyLockActive -> TerminalRed
                                        uiState.localModelReady -> TerminalGreen
                                        else -> TerminalYellow
                                    }
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                uiState.privacyMode.label,
                                color = TerminalWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (uiState.localModelReady) "Local AI ready"
                                else if (uiState.emergencyLockActive) "Locked"
                                else "Cloud only",
                                color = TerminalWhite.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── NEW CHAT button (prominent, like Claude) ──────────────
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Accent.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.4f))
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val wsId = viewModel.createWorkspace("New Chat", "💬", "#00E5FF")
                                selectedAppTab = AppTab.CHAT
                                scope.launch { drawerState.close() }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("New Chat", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Conversations (workspaces) ─────────────────────────────
                DrawerSectionLabel("CONVERSATIONS")

                workspaces.forEach { ws ->
                    var showRename by remember { mutableStateOf(false) }
                    var renameText by remember(ws.id) { mutableStateOf(ws.name) }

                    Surface(
                        color = if (uiState.activeWorkspaceId == ws.id) Accent.copy(alpha = 0.1f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 1.dp)
                            .clickable {
                                viewModel.switchWorkspace(ws.id, ws.name)
                                selectedAppTab = AppTab.CHAT
                                scope.launch { drawerState.close() }
                            }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ws.emoji, fontSize = 16.sp)
                            Spacer(Modifier.width(10.dp))

                            if (showRename) {
                                TextField(
                                    value = renameText,
                                    onValueChange = { renameText = it.take(30) },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = SurfaceLight,
                                        unfocusedContainerColor = SurfaceLight,
                                        focusedTextColor = TerminalWhite,
                                        unfocusedTextColor = TerminalWhite,
                                        focusedIndicatorColor = Accent,
                                        cursorColor = Accent
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (renameText.isNotBlank()) {
                                            viewModel.renameWorkspace(
                                                ws.copy(name = renameText.trim()),
                                                renameText.trim()
                                            )
                                        }
                                        showRename = false
                                    })
                                )
                            } else {
                                Text(
                                    ws.name,
                                    color = if (uiState.activeWorkspaceId == ws.id) TerminalWhite else TerminalWhite.copy(alpha = 0.7f),
                                    fontSize = 14.sp,
                                    fontWeight = if (uiState.activeWorkspaceId == ws.id) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Rename button (long-press to rename)
                            if (!showRename) {
                                IconButton(
                                    onClick = { showRename = true; renameText = ws.name },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "Rename",
                                        tint = TerminalWhite.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                if (workspaces.isEmpty()) {
                    Text(
                        "No conversations yet",
                        color = TerminalWhite.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // ── Create Project button ───────────────────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            viewModel.createWorkspace("New Project", "📁", "#8AB4F8")
                            selectedAppTab = AppTab.CHAT
                            scope.launch { drawerState.close() }
                        }
                        .padding(vertical = 6.dp)
                ) {
                    Text("+ New Project", color = Accent.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                // ── Quick-access navigation ───────────────────────────────
                DrawerSectionLabel("TOOLS")

                DrawerItem(
                    icon = Icons.Default.Terminal,
                    label = "Terminal",
                    selected = selectedAppTab == AppTab.TERMINAL
                ) { navigateToTab(AppTab.TERMINAL) }

                DrawerItem(
                    icon = Icons.Default.Book,
                    label = "Knowledge Vault",
                    selected = selectedAppTab == AppTab.DASHBOARDS && selectedDashboard == 1
                ) { selectedDashboard = 1; navigateToTab(AppTab.DASHBOARDS) }
                

                Spacer(Modifier.weight(1f))

                // ── Shizuku status ────────────────────────────────────────
                val shizukuAuth by viewModel.shizukuAuthorized.collectAsState()
                val shizukuAvail by viewModel.shizukuAvailable.collectAsState()
                val shizukuPriv = viewModel.shizukuPrivilegeLevel

                ShizukuStatusRow(
                    isAvailable = shizukuAvail,
                    isAuthorized = shizukuAuth,
                    privilegeLevel = shizukuPriv,
                    backendDesc = viewModel.executionBackendDescription,
                    onRequestPermission = { viewModel.requestShizukuPermission() },
                    onRefresh = { viewModel.refreshShizukuStatus() }
                )

                Spacer(Modifier.height(12.dp))

                // ── Settings footer ────────────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { navigateToTab(AppTab.SETTINGS) }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            null,
                            tint = TerminalWhite.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Settings",
                            color = TerminalWhite.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    ) {
        AppShell(
            vm = viewModel,
            selectedTab = selectedAppTab,
            onTabSelected = { tab ->
                if (tab == AppTab.DASHBOARDS) {
                    // Keep current dashboard sub-tab
                }
                selectedAppTab = tab
            },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onOpenTerminal = { selectedAppTab = AppTab.TERMINAL }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = Background800
            ) {
                AnimatedContent(
                    targetState = selectedAppTab to selectedDashboard,
                    transitionSpec = {
                        val fromTab = initialState.first.ordinal
                        val toTab = targetState.first.ordinal
                        if (toTab != fromTab) {
                            val direction = if (toTab > fromTab) 1 else -1
                            val offset = direction * 120
                            (slideInHorizontally(initialOffsetX = { offset }) + fadeIn(tween(250))) togetherWith
                                    (slideOutHorizontally(targetOffsetX = { -offset / 2 }) + fadeOut(tween(200)))
                        } else {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        }
                    },
                    label = "TabTransition"
                ) { (tab, dashIdx) ->
                    when (tab) {
                        AppTab.CHAT -> HybridChatScreen(
                            vm = viewModel,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNavigateToTerminal = { selectedAppTab = AppTab.TERMINAL }
                        )
                        AppTab.TERMINAL -> TerminalScreen(
                            vm = viewModel,
                            onOpenDrawer = { scope.launch { drawerState.open() } }
                        )
                        AppTab.VAULT -> KnowledgeVaultScreen(
                            vm = viewModel,
                            onOpenDrawer = { scope.launch { drawerState.open() } }
                        )
                        AppTab.DASHBOARDS -> when (dashIdx) {
                            0 -> MemoryDashboardScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            1 -> KnowledgeVaultScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            2 -> ResearchDashboardScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            3 -> TimelineScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            4 -> SecurityDashboardScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                            5 -> WorkspaceScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onWorkspaceSelected = { ws ->
                                    viewModel.switchWorkspace(ws.id, ws.name)
                                    selectedAppTab = AppTab.CHAT
                                }
                            )
                            else -> MemoryDashboardScreen(
                                vm = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } }
                            )
                        }
                        AppTab.SETTINGS -> SettingsScreen(
                            vm = viewModel,
                            onOpenDrawer = { scope.launch { drawerState.open() } }
                        )
                    }
                }
            }
        }
    }
}

// ── Shared drawer composables ──────────────────────────────────────────────

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        color = TerminalWhite.copy(alpha = 0.3f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(
            start = 24.dp,
            top = 12.dp,
            bottom = 4.dp
        )
    )
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = if (selected) AccentGlow else TerminalWhite.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = if (selected) TerminalWhite else TerminalWhite.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

// ── Shizuku Status Row ───────────────────────────────────────────────────
@Composable
private fun ShizukuStatusRow(
    isAvailable: Boolean,
    isAuthorized: Boolean,
    privilegeLevel: String,
    backendDesc: String,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    val statusColor = when {
        !isAvailable -> TerminalWhite.copy(0.3f)
        isAuthorized -> TerminalGreen
        else -> TerminalYellow
    }
    val statusText = when {
        !isAvailable -> "Shizuku not running"
        isAuthorized -> "Shizuku Active"
        else -> "Shizuku — Tap to authorize"
    }
    val isClickable = isAvailable && !isAuthorized

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .then(if (isClickable) Modifier.clickable { onRequestPermission() } else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = if (isAuthorized) TerminalGreen.copy(0.08f) else Color.Transparent
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    statusText,
                    color = TerminalWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (isAuthorized) {
                    Text(privilegeLevel, color = TerminalGreen, fontSize = 10.sp)
                }
                Text(
                    "Backend: $backendDesc",
                    color = TerminalWhite.copy(0.4f),
                    fontSize = 9.sp
                )
            }

            if (isClickable) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    "Authorize",
                    tint = TerminalYellow,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isAuthorized) {
                Icon(
                    Icons.Default.CheckCircle,
                    "Connected",
                    tint = TerminalGreen,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerWorkspaceItem(
    workspace: Workspace,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Accent.copy(alpha = 0.1f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(workspace.emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                workspace.name,
                color = if (selected) TerminalWhite else TerminalWhite.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}


