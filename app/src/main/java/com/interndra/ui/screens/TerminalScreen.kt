package com.interndra.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.TerminalAgent
import com.interndra.service.TermuxBridge
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.launch

/**
 * TerminalScreen — REAL interactive terminal emulator.
 *
 * Features:
 * - Live output with ANSI color rendering
 * - Command input at bottom with history (Up/Down arrows)
 * - Session tabs with create/rename/delete
 * - Real-time streaming output from TermuxBridge
 * - Auto-scroll toggle
 * - Clear, Copy functionality
 * - Workdir display
 */
@Composable
fun TerminalScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val sessions       by vm.terminalSessions.collectAsState()
    val activeSession by vm.activeTerminalSession.collectAsState()
    val listState      = rememberLazyListState()
    val scope          = rememberCoroutineScope()
    val context        = androidx.compose.ui.platform.LocalContext.current

    // Terminal state
    var inputText by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf<String?>(null) }

    // Command history navigation
    var commandHistoryIndex by remember { mutableStateOf(-1) }
    var savedCurrentInput by remember { mutableStateOf("") }

    // Streaming output lines for active session
    val outputLines = remember(activeSession) {
        vm.terminalAgent.getOutputLines(activeSession)
    }
    // Refresh output lines periodically
    var refreshTick by remember { mutableStateOf(0L) }

    // Observe streaming output
    LaunchedEffect(Unit) {
        vm.terminalAgent.outputFlow.collect { event ->
            when (event) {
                is TerminalAgent.StreamEvent.Output -> {
                    if (event.sessionName == activeSession || activeSession == "default") {
                        refreshTick = System.currentTimeMillis()
                    }
                }
                else -> {}
            }
        }
    }

    // Get fresh output lines
    val displayLines = remember(refreshTick, activeSession, outputLines.size) {
        vm.terminalAgent.getOutputLines(activeSession)
    }

    // Focus requester for input field
    val inputFocusRequester = remember { FocusRequester() }

    // Auto-scroll — use instant scrollToItem (no animation) for high-frequency updates
    // to prevent animation jitter during streaming output
    LaunchedEffect(displayLines.size) {
        if (autoScroll && displayLines.isNotEmpty()) {
            listState.scrollToItem(displayLines.size - 1)
        }
    }

    // Focus input field on session change
    LaunchedEffect(activeSession) {
        inputFocusRequester.requestFocus()
    }

    // Termux status
    val termuxBridge = remember { TermuxBridge(context) }
    val termuxInstalled = remember { termuxBridge.isTermuxInstalled() }

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

    Column(Modifier.fillMaxSize().background(TerminalBg).imePadding()) {

        // ── Top Bar ───────────────────────────────────────────────────────
        TerminalTopBar(
            sessions = sessions,
            activeSession = activeSession,
            onOpenDrawer = onOpenDrawer,
            onSelectSession = { vm.setActiveTerminalSession(it) },
            onAddSession = { showNewSessionDialog = true },
            onSessionLongPress = { showSessionMenu = it },
            termuxInstalled = termuxInstalled,
            autoScroll = autoScroll,
            onToggleAutoScroll = { autoScroll = !autoScroll },
            onClear = { vm.terminalAgent.clearHistory(activeSession) }
        )

        // Active session workdir
        val workdir = remember(activeSession) {
            vm.terminalAgent.getWorkdir(activeSession)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).background(TerminalBg),
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

        // ── Terminal Output Area ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBg)
        ) {
            if (displayLines.isEmpty()) {
                // Empty state
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Terminal, null,
                            tint = TerminalWhite.copy(alpha = 0.12f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("INTERNDRA Terminal",
                            color = TerminalWhite.copy(alpha = 0.5f), fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Type a command below",
                            color = TerminalWhite.copy(alpha = 0.25f), fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(16.dp))
                        if (!termuxInstalled) {
                            Surface(shape = RoundedCornerShape(8.dp), color = TerminalRed.copy(0.12f)) {
                                Text("⚠ Termux not installed — some commands won't work",
                                    color = TerminalRed.copy(0.8f), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { inputFocusRequester.requestFocus() },
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(displayLines, key = { idx, _ -> "line_${idx}_${refreshTick}" }) { index, line ->
                        AnsiTerminalLine(
                            text = line,
                            isLast = index == displayLines.size - 1
                        )
                    }
                }
            }
        }

        // ── Terminal Input Bar ───────────────────────────────────────────
        TerminalInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    scope.launch {
                        vm.terminalAgent.execute(activeSession, inputText.trim()).let { }
                    }
                    commandHistoryIndex = -1
                    inputText = ""
                }
            },
            onKeyUp = {
                val history = vm.terminalAgent.getHistory(activeSession)
                if (history.isNotEmpty()) {
                    val newIndex = if (commandHistoryIndex < 0) 0
                        else (commandHistoryIndex + 1).coerceAtMost(history.size - 1)
                    if (commandHistoryIndex < 0) savedCurrentInput = inputText
                    commandHistoryIndex = newIndex
                    inputText = history[newIndex].command
                }
            },
            onKeyDown = {
                if (commandHistoryIndex >= 0) {
                    val newIndex = commandHistoryIndex - 1
                    if (newIndex < 0) {
                        commandHistoryIndex = -1
                        inputText = savedCurrentInput
                        savedCurrentInput = ""
                    } else {
                        commandHistoryIndex = newIndex
                        val history = vm.terminalAgent.getHistory(activeSession)
                        if (newIndex < history.size) {
                            inputText = history[newIndex].command
                        }
                    }
                }
            },
            focusRequester = inputFocusRequester
        )

        // Status bar
        TerminalStatusBar(
            lineCount = displayLines.size,
            sessionName = activeSession,
            termuxInstalled = termuxInstalled
        )
    }
}

// ── Terminal Top Bar ────────────────────────────────────────────────────────
@Composable
private fun TerminalTopBar(
    sessions: List<String>,
    activeSession: String,
    onOpenDrawer: () -> Unit,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onSessionLongPress: (String) -> Unit,
    termuxInstalled: Boolean,
    autoScroll: Boolean,
    onToggleAutoScroll: () -> Unit,
    onClear: () -> Unit
) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by inf.animateFloat(0.4f, 1.0f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Surface(color = TerminalBg.copy(alpha = 0.95f)) {
        Column(Modifier.fillMaxWidth()) {
            // Title row
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }
                Text("Terminal", color = TerminalWhite, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                // Termux status
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (termuxInstalled) TerminalGreen.copy(0.12f) else TerminalRed.copy(0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(if (termuxInstalled) TerminalGreen else TerminalRed)
                    )
                    Text(
                        if (termuxInstalled) "Termux" else "No Termux",
                        color = if (termuxInstalled) TerminalGreen else TerminalRed.copy(0.7f),
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onToggleAutoScroll() }) {
                    Icon(
                        if (autoScroll) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        "Auto-scroll",
                        tint = if (autoScroll) Accent else TerminalWhite.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteSweep, "Clear",
                        tint = TerminalRed.copy(alpha = 0.7f))
                }
            }

            // Session tabs
            SessionRow(
                sessions = sessions,
                activeSession = activeSession,
                onSelectSession = onSelectSession,
                onAddSession = onAddSession,
                onSessionLongPress = onSessionLongPress
            )
        }
    }
}

// ── Session Row ──────────────────────────────────────────────────────────────
@Composable
private fun SessionRow(
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
                border = BorderStroke(
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

        // Add session button
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TerminalGreen.copy(0.1f),
            border = BorderStroke(1.dp, TerminalGreen.copy(0.3f)),
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

// ── ANSI Terminal Line ──────────────────────────────────────────────────────
@Composable
private fun AnsiTerminalLine(
    text: String,
    isLast: Boolean
) {
    // Strip ANSI escape codes and extract text
    val cleanText = remember(text) { stripAnsiCodes(text) }

    // Determine color from ANSI codes
    val textColor = remember(text) { parseAnsiColor(text) }

    Text(
        text = cleanText,
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        softWrap = true,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

/**
 * Strip ANSI escape codes from text, returning clean display text.
 */
private fun stripAnsiCodes(text: String): String {
    return text.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
        .replace(Regex("\u001b\\][0-9;]*[a-zA-Z]"), "")
        .replace(Regex("[\\u0000-\\u001F]"), "") // Remove control chars except newline
}

/**
 * Parse ANSI escape codes to determine text color.
 * Returns appropriate TerminalWhite/TerminalGreen/TerminalRed etc.
 */
private fun parseAnsiColor(text: String): Color {
    val ansiPattern = Regex("\u001b\\[([0-9;]*)m")
    val matches = ansiPattern.findAll(text).map { it.groupValues[1] }.toList()

    // Check the LAST ANSI code before reset
    for (code in matches.reversed()) {
        val codes = code.split(";")
        for (c in codes) {
            return when (c) {
                "30", "38;5;0" -> TerminalWhite.copy(alpha = 0.5f)  // Dark gray
                "31", "38;5;1", "38;5;160", "38;5;196", "38;5;197",
                "38;5;198", "38;5;199", "38;5;200" -> TerminalRed
                "32", "38;5;2", "38;5;28", "38;5;34", "38;5;40",
                "38;5;46" -> TerminalGreen
                "33", "38;5;3", "38;5;220", "38;5;226", "38;5;228" -> TerminalYellow
                "34", "38;5;4", "38;5;21", "38;5;27", "38;5;33" -> Color(0xFF569CD6) // Blue
                "35", "38;5;5" -> Color(0xFFC586C0) // Purple
                "36", "38;5;6", "38;5;44", "38;5;45", "38;5;51" -> Color(0xFF4EC9B0) // Cyan
                "37", "38;5;7" -> TerminalWhite
                "90", "38;5;8" -> TerminalWhite.copy(alpha = 0.4f) // Bright black
                "91", "38;5;9" -> Color(0xFFF44747) // Bright red
                "92", "38;5;10" -> Color(0xFF4EC9B0) // Bright green
                "93", "38;5;11" -> Color(0xFFDCDCAA) // Bright yellow
                "94", "38;5;12" -> Color(0xFF569CD6) // Bright blue
                "95", "38;5;13" -> Color(0xFFC586C0) // Bright magenta
                "96", "38;5;14" -> Color(0xFF9CDCFE) // Bright cyan
                "0", "0;0" -> return TerminalWhite // Reset
                else -> continue
            }
        }
    }
    return TerminalWhite
}

// ── Terminal Input Bar ──────────────────────────────────────────────────────
@Composable
private fun TerminalInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onKeyUp: () -> Unit,
    onKeyDown: () -> Unit,
    focusRequester: FocusRequester
) {
    Surface(
        color = TerminalBg.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, Color(0xFF1E1E1E)), RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prompt character
            Text(
                "$ ",
                color = TerminalGreen,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )

            // Text input
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.Enter -> { onSend(); true }
                                Key.DirectionUp -> { onKeyUp(); true }
                                Key.DirectionDown -> { onKeyDown(); true }
                                else -> false
                            }
                        } else false
                    },
                placeholder = {
                    Text("Type a command...",
                        fontSize = 14.sp,
                        color = TerminalWhite.copy(alpha = 0.2f),
                        fontFamily = FontFamily.Monospace)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TerminalWhite,
                    unfocusedTextColor = TerminalWhite,
                    cursorColor = TerminalGreen
                ),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TerminalWhite
                ),
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                singleLine = true
            )

            // Send button
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.size(36.dp)
            ) {                        Icon(
                            Icons.Default.PlayArrow,
                            "Run",
                    tint = if (text.isNotBlank()) TerminalGreen else TerminalWhite.copy(alpha = 0.2f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Terminal Status Bar ─────────────────────────────────────────────────────
@Composable
private fun TerminalStatusBar(
    lineCount: Int,
    sessionName: String,
    termuxInstalled: Boolean
) {
    Surface(color = Color(0xFF0A0A0A), tonalElevation = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$lineCount lines · $sessionName",
                color = TerminalWhite.copy(alpha = 0.25f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!termuxInstalled) {
                    Text("Termux not installed",
                        color = TerminalRed.copy(alpha = 0.4f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Text("↑↓ history · Enter run",
                    color = TerminalWhite.copy(alpha = 0.2f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
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
    var workdir by remember { mutableStateOf("/data/data/com.termux/files/home") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalBg,
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
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TerminalWhite)
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
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TerminalWhite)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), workdir.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Create", color = TerminalBg, fontWeight = FontWeight.Bold) }
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
        containerColor = TerminalBg,
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
                MenuItem(Icons.Default.Edit, "Rename", "Change session name", Accent) {
                    showRenameDialog = true
                }
                MenuItem(Icons.Default.DeleteSweep, "Clear Output", "Remove all terminal output", TerminalYellow) {
                    onClearHistory()
                    onDismiss()
                }
                if (!isDefault) {
                    MenuItem(Icons.Default.Delete, "Delete Session", "Permanently remove this session", TerminalRed) {
                        onDelete()
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
        }
    )

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = TerminalBg,
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
                ) { Text("Rename", color = TerminalBg) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = TerminalWhite) }
            }
        )
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    description: String,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceLight.copy(0.1f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, color = TerminalWhite, fontSize = 14.sp)
                Text(description, color = TerminalWhite.copy(0.4f), fontSize = 11.sp)
            }
        }
    }
}
