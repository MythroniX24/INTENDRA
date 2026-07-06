package com.interndra.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager

import com.interndra.data.model.LogType
import com.interndra.data.model.TerminalLog
import com.interndra.service.TermuxBridge
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TerminalScreen — ENHANCED with session tabs UI.
 *
 * Features:
 * - Session tab bar with horizontal scrolling
 * - Create/rename/delete sessions
 * - Per-session workdir display
 * - Log viewer with search, filter, type colors/icons
 * - Termux status indicator
 * - Auto-scroll toggle
 */
@Composable
fun TerminalScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val logs          by vm.terminalLogs.collectAsState()
    val sessions       by vm.terminalSessions.collectAsState()
    val activeSession by vm.activeTerminalSession.collectAsState()
    val listState      = rememberLazyListState()
    val context        = androidx.compose.ui.platform.LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<LogType?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf<String?>(null) }

    // Filter logs
    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        logs.filter { log ->
            val matchesSearch = searchQuery.isBlank() ||
                log.content.contains(searchQuery, ignoreCase = true) ||
                log.logType.name.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || log.logType == selectedFilter
            matchesSearch && matchesFilter
        }
    }

    // Compute stats
    val stats = remember(logs) {
        TerminalStats(
            total = logs.size,
            ok = logs.count { it.logType == LogType.STATUS_OK },
            fail = logs.count { it.logType == LogType.STATUS_FAIL },
            commands = logs.count { it.logType in listOf(LogType.COMMAND, LogType.TERMUX_CMD) }
        )
    }

    // Termux status
    val termuxBridge = remember { TermuxBridge(context) }
    val termuxInstalled = remember { termuxBridge.isTermuxInstalled() }

    // Auto-scroll
    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // ── New Session Dialog ───────────────────────────────────────────────
    if (showNewSessionDialog) {
        NewSessionDialog(
            onDismiss = { showNewSessionDialog = false },
            onCreate = { name, workdir ->
                vm.createTerminalSession(name, workdir)
                vm.setActiveTerminalSession(name)
                showNewSessionDialog = false
            }
        )
    }

    // ── Session Context Menu ──────────────────────────────────────────────
    showSessionMenu?.let { sessionName ->
        SessionContextMenu(
            sessionName = sessionName,
            isDefault = sessionName == "default",
            onDismiss = { showSessionMenu = null },
            onRename = { newName ->
                vm.renameTerminalSession(sessionName, newName)
                showSessionMenu = null
            },
            onDelete = {
                vm.removeTerminalSession(sessionName)
                showSessionMenu = null
            },
            onClearHistory = {
                vm.terminalAgent.clearHistory(sessionName)
                showSessionMenu = null
            }
        )
    }

    val clipboardManager = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(ChatBg)) {

        // ── Top Bar ───────────────────────────────────────────────────────
        Surface(color = CardSurface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth()) {
                // Title row
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Terminal Logs", color = TerminalWhite, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold)
                        Text("${logs.size} entries · ${stats.ok} ✓ · ${stats.fail} ✗ · ${stats.commands} \$",
                            color = TerminalWhite.copy(alpha = 0.45f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    // Termux status indicator
                    TerminalStatusIndicator(installed = termuxInstalled)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(if (showSearch) Icons.Default.SearchOff else Icons.Default.Search, "Search",
                            tint = if (showSearch) Accent else TerminalWhite.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(if (autoScroll) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            "Auto-scroll",
                            tint = if (autoScroll) Accent else TerminalWhite.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { vm.clearAll() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear",
                            tint = TerminalRed.copy(alpha = 0.7f))
                    }
                }

                // ── Session Tabs ───────────────────────────────────────────
                SessionTabBar(
                    sessions = sessions,
                    activeSession = activeSession,
                    onSelectSession = { vm.setActiveTerminalSession(it) },
                    onAddSession = { showNewSessionDialog = true },
                    onSessionLongPress = { showSessionMenu = it }
                )

                // ── Active Session Workdir ─────────────────────────────────
                val workdir = remember(activeSession) {
                    vm.terminalAgent.getWorkdir(activeSession)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null,
                        tint = Accent.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        workdir,
                        color = Accent.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ── Search bar ─────────────────────────────────────────────
                AnimatedVisibility(visible = showSearch) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f).height(48.dp),
                            placeholder = { Text("Search logs...", color = TerminalWhite.copy(0.3f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = SurfaceLight.copy(0.3f),
                                focusedTextColor = TerminalWhite,
                                unfocusedTextColor = TerminalWhite,
                                cursorColor = Accent
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = TerminalWhite.copy(0.4f), modifier = Modifier.size(18.dp)) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, "Clear", tint = TerminalWhite.copy(0.4f)) } }
                            } else null
                        )
                    }
                }

                // ── Filter chips ──────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filters = listOf(null to "All") +
                        LogType.entries.map { it to it.name.take(12) }
                    filters.take(8).forEach { (type, label) ->
                        val selected = selectedFilter == type
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) Accent.copy(0.2f) else SurfaceLight.copy(0.1f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (selected) Accent.copy(0.5f) else SurfaceLight.copy(0.2f)
                            ),
                            modifier = Modifier.clickable { selectedFilter = type }
                        ) {
                            Text(
                                label,
                                color = if (selected) Accent else TerminalWhite.copy(0.6f),
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        // ── Log area ───────────────────────────────────────────────────────
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Terminal, null,
                        tint = TerminalWhite.copy(alpha = 0.12f), modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No logs yet.",
                        color = TerminalWhite.copy(alpha = 0.35f), fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("Send a command or ask the AI to do something.",
                        color = TerminalWhite.copy(alpha = 0.2f), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        } else if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FilterList, null,
                        tint = TerminalWhite.copy(alpha = 0.15f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No logs match your filter.",
                        color = TerminalWhite.copy(alpha = 0.35f), fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${filteredLogs.size} of ${logs.size} logs shown",
                    color = TerminalWhite.copy(alpha = 0.25f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                if (!autoScroll) {
                    Text(
                        "🔴 Auto-scroll off — tap ↓ to re-enable",
                        color = TerminalYellow.copy(0.6f),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        EnhancedLogLine(
                            log = log,
                            searchHighlight = searchQuery,
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(log.content))
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Session Tab Bar ────────────────────────────────────────────────────────
@Composable
private fun SessionTabBar(
    sessions: List<String>,
    activeSession: String,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onSessionLongPress: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { name ->
            val isActive = name == activeSession
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isActive) Accent.copy(0.2f) else SurfaceLight.copy(0.08f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isActive) Accent.copy(0.6f) else SurfaceLight.copy(0.2f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectSession(name) }
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isActive) Accent else SurfaceLight.copy(0.3f))
                    )
                    Text(
                        name,
                        color = if (isActive) Accent else TerminalWhite.copy(0.7f),
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    // Options icon for all sessions
                    if (sessions.isNotEmpty()) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            "Options",
                            tint = if (isActive) Accent.copy(0.6f) else TerminalWhite.copy(0.3f),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onSessionLongPress(name) }
                        )
                    }
                }
            }
        }

        // Add session button
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TerminalGreen.copy(0.1f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, TerminalGreen.copy(0.3f)
            ),
            modifier = Modifier.clickable(onClick = onAddSession)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(Icons.Default.Add, null,
                    tint = TerminalGreen, modifier = Modifier.size(14.dp))
                Text("New", color = TerminalGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── New Session Dialog ─────────────────────────────────────────────────────
@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, workdir: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var workdir by remember {
        mutableStateOf("/data/data/com.termux/files/home")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1B1E),
        titleContentColor = TerminalWhite,
        textContentColor = TerminalWhite,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddCircle, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Terminal Session", color = TerminalWhite, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "") },
                    label = { Text("Session Name", color = TerminalWhite.copy(0.5f)) },
                    placeholder = { Text("e.g. project-a", color = TerminalWhite.copy(0.3f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = SurfaceLight.copy(0.3f),
                        focusedTextColor = TerminalWhite,
                        unfocusedTextColor = TerminalWhite,
                        cursorColor = Accent,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TerminalWhite.copy(0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                )
                OutlinedTextField(
                    value = workdir,
                    onValueChange = { workdir = it },
                    label = { Text("Working Directory", color = TerminalWhite.copy(0.5f)) },
                    placeholder = { Text("e.g. /data/data/com.termux/files/home/project", color = TerminalWhite.copy(0.3f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = SurfaceLight.copy(0.3f),
                        focusedTextColor = TerminalWhite,
                        unfocusedTextColor = TerminalWhite,
                        cursorColor = Accent,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TerminalWhite.copy(0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), workdir.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Create", color = ChatBg, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = TerminalWhite)
            }
        }
    )
}

// ── Session Context Menu ───────────────────────────────────────────────────
@Composable
private fun SessionContextMenu(
    sessionName: String,
    isDefault: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onClearHistory: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(sessionName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1B1E),
        titleContentColor = TerminalWhite,
        textContentColor = TerminalWhite,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Session: $sessionName", color = TerminalWhite, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Rename
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceLight.copy(0.1f),
                    modifier = Modifier.fillMaxWidth().clickable { showRenameDialog = true }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Rename", color = TerminalWhite, fontSize = 14.sp)
                            Text("Change session name", color = TerminalWhite.copy(0.4f), fontSize = 11.sp)
                        }
                    }
                }
                // Clear history
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceLight.copy(0.1f),
                    modifier = Modifier.fillMaxWidth().clickable { onClearHistory(); onDismiss() }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteSweep, null, tint = TerminalYellow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Clear History", color = TerminalWhite, fontSize = 14.sp)
                            Text("Remove all command history for this session", color = TerminalWhite.copy(0.4f), fontSize = 11.sp)
                        }
                    }
                }
                // Delete (not for default)
                if (!isDefault) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TerminalRed.copy(0.1f),
                        modifier = Modifier.fillMaxWidth().clickable { onDelete(); onDismiss() }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, null, tint = TerminalRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Delete Session", color = TerminalRed, fontSize = 14.sp)
                                Text("Permanently remove this session", color = TerminalRed.copy(0.5f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
        }
    )

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color(0xFF1A1B1E),
            title = { Text("Rename Session", color = TerminalWhite) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = SurfaceLight.copy(0.3f),
                        focusedTextColor = TerminalWhite,
                        unfocusedTextColor = TerminalWhite,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank() && renameText != sessionName) {
                            onRename(renameText.trim())
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Rename", color = ChatBg) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = TerminalWhite) }
            }
        )
    }
}

// ── Terminal Stats ─────────────────────────────────────────────────────────
private data class TerminalStats(
    val total: Int,
    val ok: Int,
    val fail: Int,
    val commands: Int
)

// ── Termux Status Indicator ────────────────────────────────────────────────
@Composable
private fun TerminalStatusIndicator(installed: Boolean) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by inf.animateFloat(0.4f, 1.0f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (installed) TerminalGreen.copy(0.12f) else TerminalRed.copy(0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(if (installed) TerminalGreen else TerminalRed)
        )
        Text(
            if (installed) "Termux ✓" else "No Termux",
            color = if (installed) TerminalGreen else TerminalRed.copy(0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── Enhanced Log Line ──────────────────────────────────────────────────────
@Composable
private fun EnhancedLogLine(
    log: TerminalLog,
    searchHighlight: String,
    onClick: () -> Unit
) {
    val logColors = getLogColors(log.logType)
    val prefix = getLogPrefix(log.logType)
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val animatedAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300),
        label = "logAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(animatedAlpha)
            .clip(RoundedCornerShape(6.dp))
            .background(logColors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, end = 8.dp)
                .size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(logColors.icon, null, tint = logColors.color, modifier = Modifier.size(14.dp))
        }
        Text(
            text = sdf.format(Date(log.timestamp)),
            color = TerminalWhite.copy(alpha = 0.2f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp).padding(top = 1.dp)
        )
        val displayText = "$prefix${log.content}"
        Text(
            text = displayText,
            color = logColors.color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            lineHeight = 17.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )
        Surface(shape = RoundedCornerShape(4.dp), color = logColors.color.copy(alpha = 0.1f)) {
            Text(
                log.logType.name.take(6),
                color = logColors.color.copy(alpha = 0.4f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

// ── Color & Icon mapping ───────────────────────────────────────────────────
private data class LogColors(
    val color: Color,
    val bg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private fun getLogColors(type: LogType): LogColors = when (type) {
    LogType.STATUS_OK -> LogColors(TerminalGreen, TerminalGreen.copy(alpha = 0.06f), Icons.Default.CheckCircle)
    LogType.STATUS_FAIL -> LogColors(TerminalRed, TerminalRed.copy(alpha = 0.08f), Icons.Default.Cancel)
    LogType.COMMAND, LogType.TERMUX_CMD -> LogColors(Color(0xFF00E5FF), Color(0xFF00E5FF).copy(alpha = 0.06f), Icons.Default.Terminal)
    LogType.AI_INPUT -> LogColors(Color(0xFFB388FF), Color(0xFFB388FF).copy(alpha = 0.06f), Icons.Default.Person)
    LogType.AI_INTENT -> LogColors(Color(0xFF82B1FF), Color(0xFF82B1FF).copy(alpha = 0.06f), Icons.Default.Psychology)
    LogType.EXECUTION_PLAN -> LogColors(TerminalYellow, TerminalYellow.copy(alpha = 0.06f), Icons.Default.AccountTree)
    LogType.INFO -> LogColors(TerminalWhite.copy(alpha = 0.6f), Color.Transparent, Icons.Default.Info)
}

private fun getLogPrefix(type: LogType): String = when (type) {
    LogType.STATUS_OK -> "✓ "
    LogType.STATUS_FAIL -> "✗ "
    LogType.COMMAND, LogType.TERMUX_CMD -> "$ "
    LogType.AI_INPUT -> "→ "
    LogType.AI_INTENT -> "🧠 "
    LogType.EXECUTION_PLAN -> "📋 "
    LogType.INFO -> ""
}
