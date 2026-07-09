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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.TerminalAgent
import com.interndra.service.TerminalBuffer
import com.interndra.service.TermuxBridge
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TerminalScreen — REAL interactive terminal emulator.
 *
 * ## A+++ UPGRADE: TerminalBuffer-powered ANSI rendering
 * Raw shell output is now piped through [TerminalBuffer] which handles:
 * - Full SGR color sequences (30-37, 40-47, 90-97, 100-107)
 * - Bold, dim, underline text styling
 * - Cursor movement + screen clear sequences
 * - Per-character color spans rendered as [AnnotatedString]
 *
 * Features:
 * - Live output with proper ANSI color rendering (no more raw escape codes)
 * - Command input at bottom with history (Up/Down arrows)
 * - Session tabs with create/rename/delete
 * - Real-time streaming output from TerminalAgent
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
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf<String?>(null) }

    // Command history navigation
    var commandHistoryIndex by remember { mutableStateOf(-1) }
    var savedCurrentInput by remember { mutableStateOf("") }
    var showHistoryPanel by remember { mutableStateOf(false) }

    // ── TerminalBuffer: per-session ANSI parser ──
    val terminalBuffer = remember { TerminalBuffer() }

    // Parsed terminal lines from TerminalBuffer (structured with color spans)
    var terminalLines by remember { mutableStateOf<List<TerminalBuffer.TerminalLine>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0L) }

    // Single unified LaunchedEffect: handles session switch (reset + re-parse)
    // AND real-time streaming output collection. Cancels/restarts on session change.
    LaunchedEffect(activeSession) {
        // Reset and re-parse all output for the newly active session
        terminalBuffer.reset()
        val allLines = vm.terminalAgent.getOutputLines(activeSession)
        for (line in allLines) {
            terminalBuffer.processOutput(line)
        }
        terminalLines = terminalBuffer.flush()

        // Start collecting streaming output for this session
        vm.terminalAgent.outputFlow.collect { event ->
            if (event is TerminalAgent.StreamEvent.Output &&
                (event.sessionName == activeSession || activeSession == "default")) {
                terminalBuffer.processOutput(event.text)
                // Flush with minimal debounce — 16ms (one frame) for smooth streaming
                val now = System.currentTimeMillis()
                if (now - refreshTick > 16) {
                    refreshTick = now
                    terminalLines = terminalBuffer.flush()
                }
            }
        }
    }

    // Focus requester for input field
    val inputFocusRequester = remember { FocusRequester() }

    // Auto-scroll — use instant scrollToItem for high-frequency streaming
    LaunchedEffect(terminalLines.size) {
        if (autoScroll && terminalLines.isNotEmpty()) {
            listState.scrollToItem(terminalLines.size - 1)
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
        Column(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
            // ── Background Jobs Panel ──────────────────────────────────
            val bgJobs = remember(refreshTick) {
                vm.terminalAgent.listJobsForSession(activeSession)
                    .filter { it.isActive }
            }
            AnimatedVisibility(visible = bgJobs.isNotEmpty()) {
                Surface(
                    color = TerminalYellow.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, TerminalYellow.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 2.dp)) {
                            Icon(Icons.Default.PlayArrow, null,
                                tint = TerminalGreen, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Background Jobs (${bgJobs.size})",
                                color = TerminalYellow, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { vm.terminalAgent.cancelAllJobs(activeSession) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(20.dp)
                            ) {
                                Text("Cancel All", color = TerminalRed, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        bgJobs.forEach { job ->
                            AnimatedJobRow(
                                job = job,
                                onCancel = { vm.terminalAgent.cancelJob(job.id) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            // ── Terminal Output ────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
            if (terminalLines.isEmpty()) {
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
                    itemsIndexed(
                        terminalLines,
                        key = { idx, _ -> "tline_${idx}_${refreshTick}" }
                    ) { index, line ->
                        AnsiTerminalLine(
                            terminalLine = line,
                            isLast = index == terminalLines.size - 1
                        )
                    }
                }
            } // end if/else
            } // end Box (terminal output)
        } // end Column (output area + jobs panel)

        // ── Animated Background Job Row ──────────────────────────────────────────
@Composable
private fun AnimatedJobRow(
    job: TerminalAgent.SessionJob,
    onCancel: () -> Unit
) {
    // ── Live duration counter ──
    val durationTick = remember { mutableStateOf(0L) }
    LaunchedEffect(job.id, job.status) {
        if (job.status == TerminalAgent.BackgroundJobStatus.RUNNING) {
            while (true) {
                durationTick.value = System.currentTimeMillis() - job.startedAt
                delay(1000)
            }
        } else {
            durationTick.value = System.currentTimeMillis() - job.startedAt
        }
    }

    // ── Animated progress bar for running jobs ──
    val progressAnim = rememberInfiniteTransition(label = "job_progress")
    val progressShift by progressAnim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = LinearEasing),
            RepeatMode.Restart
        ), label = "progress_shift"
    )

    val durationFormatted = remember(durationTick.value) {
        val secs = durationTick.value / 1000
        when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m ${secs % 60}s"
            else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
        }
    }

    val statusColor = when (job.status) {
        TerminalAgent.BackgroundJobStatus.RUNNING -> TerminalGreen
        TerminalAgent.BackgroundJobStatus.COMPLETED -> Accent
        TerminalAgent.BackgroundJobStatus.CANCELLED -> TerminalYellow
        TerminalAgent.BackgroundJobStatus.FAILED -> TerminalRed
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon (animated pulse for running)
            if (job.status == TerminalAgent.BackgroundJobStatus.RUNNING) {
                val pulseAnim = rememberInfiniteTransition(label = "pulse_job")
                val pulseAlpha by pulseAnim.animateFloat(0.4f, 1.0f,
                    infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
                Box(
                    Modifier.size(6.dp).alpha(pulseAlpha).clip(CircleShape).background(statusColor)
                )
            } else {
                val icon = when (job.status) {
                    TerminalAgent.BackgroundJobStatus.COMPLETED -> "✓"
                    TerminalAgent.BackgroundJobStatus.CANCELLED -> "⏹"
                    TerminalAgent.BackgroundJobStatus.FAILED -> "✗"
                    else -> "●"
                }
                Text(icon, fontSize = 8.sp, color = statusColor)
            }
            Spacer(Modifier.width(6.dp))

            // Command + duration
            Column(modifier = Modifier.weight(1f)) {
                Text("#${job.id}: ${job.command.take(40)}",
                    color = TerminalWhite.copy(alpha = 0.7f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⏱ $durationFormatted",
                        color = statusColor.copy(alpha = 0.6f), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("${job.outputLines.size} lines",
                        color = TerminalWhite.copy(alpha = 0.3f), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // Cancel button
            IconButton(onClick = onCancel, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, "Cancel",
                    tint = if (job.status == TerminalAgent.BackgroundJobStatus.RUNNING)
                        TerminalRed.copy(alpha = 0.7f) else TerminalWhite.copy(alpha = 0.3f),
                    modifier = Modifier.size(12.dp))
            }
        }

        // ── Animated progress bar (running only) ──
        if (job.status == TerminalAgent.BackgroundJobStatus.RUNNING) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(SurfaceLight.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.4f)
                        .offset(x = (progressShift * 60).dp) // Sweep across
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    TerminalGreen.copy(alpha = 0.3f),
                                    TerminalGreen,
                                    TerminalGreen.copy(alpha = 0.3f)
                                )
                            )
                        )
                )
            }
        }

        // ── Exit code display for completed/failed jobs ──
        if (job.status != TerminalAgent.BackgroundJobStatus.RUNNING && job.exitCode != null) {
            Text(
                "Exit ${job.exitCode}",
                color = if (job.exitCode == 0) TerminalGreen.copy(0.5f) else TerminalRed.copy(0.5f),
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 12.dp, top = 1.dp)
            )
        }
    }
}
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
            lineCount = terminalLines.size,
            sessionName = activeSession,
            termuxInstalled = termuxInstalled
        )
    }

    // ── Command History Panel (slide-up) ────────────────────────────────
    AnimatedVisibility(
        visible = showHistoryPanel,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        HistoryPanel(
            history = vm.terminalAgent.getHistory(activeSession),
            onSelect = { entry ->
                inputText = entry.command
                showHistoryPanel = false
            },
            onClose = { showHistoryPanel = false }
        )
    }
}

// ── Command History Panel ───────────────────────────────────────────────────
@Composable
private fun HistoryPanel(
    history: List<TerminalAgent.HistoryEntry>,
    onSelect: (TerminalAgent.HistoryEntry) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = TerminalBg,
        border = BorderStroke(1.dp, SurfaceLight.copy(0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, null,
                    tint = Accent.copy(0.7f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Command History (${history.size})",
                    color = TerminalWhite.copy(0.7f), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("Close", color = Accent, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(history, key = { idx, _ -> "hist_${idx}" }) { idx, entry ->
                    val isSuccess = entry.exitCode == 0
                    val timeStr = remember(entry.timestamp) {
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(entry.timestamp))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSuccess) TerminalGreen.copy(0.05f) else TerminalRed.copy(0.05f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Exit code indicator
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .padding(end = 4.dp)
                            ) {
                                Text(
                                    if (isSuccess) "✓" else "✗",
                                    color = if (isSuccess) TerminalGreen else TerminalRed,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Command
                            Text(
                                entry.command.take(60),
                                color = TerminalWhite,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Timestamp + duration
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    timeStr,
                                    color = TerminalWhite.copy(0.3f),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (entry.durationMs > 0) {
                                    Text(
                                        "${entry.durationMs}ms",
                                        color = TerminalWhite.copy(0.2f),
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Backend tag
                            if (entry.backend != "UNKNOWN") {
                                Spacer(Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Accent.copy(0.1f)
                                ) {
                                    Text(
                                        entry.backend.take(8),
                                        color = Accent.copy(0.6f),
                                        fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Terminal Top Bar (Enhanced) ───────────────────────────────────────────
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
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }

                Column(Modifier.weight(1f)) {
                    Text("Terminal", color = TerminalWhite, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${sessions.size} sessions", color = TerminalWhite.copy(0.4f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }

                // Termux status with pulse
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
                        if (termuxInstalled) "Termux" else "Shell",
                        color = if (termuxInstalled) TerminalGreen else TerminalRed.copy(0.7f),
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(4.dp))

                // Auto-scroll toggle
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (autoScroll) Accent.copy(0.15f) else Color.Transparent,
                    modifier = Modifier.clickable { onToggleAutoScroll() }
                ) {
                    Box(Modifier.padding(6.dp)) {
                        Icon(
                            if (autoScroll) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            "Auto-scroll",
                            tint = if (autoScroll) Accent else TerminalWhite.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Clear
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.clickable { onClear() }
                ) {
                    Box(Modifier.padding(6.dp)) {
                        Icon(Icons.Default.DeleteSweep, "Clear",
                            tint = TerminalRed.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Session tabs (Enhanced)
            EnhancedSessionRow(
                sessions = sessions,
                activeSession = activeSession,
                onSelectSession = onSelectSession,
                onAddSession = onAddSession,
                onSessionLongPress = onSessionLongPress
            )
        }
    }
}

// ── Enhanced Session Row ─────────────────────────────────────────────────────
@Composable
private fun EnhancedSessionRow(
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
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { name ->
            val isActive = name == activeSession
            val isDefault = name == "default"
            val emoji = if (isDefault) "💻" else "📂"

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isActive) Accent.copy(0.18f) else SurfaceCard.copy(0.5f),
                border = BorderStroke(
                    1.dp,
                    if (isActive) Accent.copy(0.5f) else SurfaceLight.copy(0.15f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectSession(name) }
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Icon
                    Text(emoji, fontSize = 12.sp)

                    // Session name
                    Text(
                        name,
                        color = if (isActive) TerminalWhite else TerminalWhite.copy(0.65f),
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )

                    // Status dot (pulsing for active, static for others)
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isActive) Accent else SurfaceLight.copy(0.3f))
                    )

                    // Dropdown arrow
                    Icon(
                        Icons.Default.ArrowDropDown,
                        "Options",
                        tint = if (isActive) TerminalWhite.copy(0.5f) else TerminalWhite.copy(0.25f),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onSessionLongPress(name) }
                    )
                }
            }
        }

        // Add session button
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TerminalGreen.copy(0.08f),
            border = BorderStroke(1.dp, TerminalGreen.copy(0.25f)),
            modifier = Modifier.clickable(onClick = onAddSession)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
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

// ── ANSI Terminal Line (TerminalBuffer-powered) ────────────────────────────
/**
 * Renders a single [TerminalBuffer.TerminalLine] with full ANSI color support.
 * Uses [buildAnnotatedString] + [SpanStyle] to apply per-character foreground
 * colors, background colors, bold, dim, and underline from parsed color spans.
 */
@Composable
private fun AnsiTerminalLine(
    terminalLine: TerminalBuffer.TerminalLine,
    isLast: Boolean
) {
    val annotated = remember(terminalLine) {
        buildAnnotatedString {
            val text = terminalLine.text
            val spans = terminalLine.spans

            if (spans.isEmpty()) {
                // No color spans — append plain text with default terminal color
                withStyle(SpanStyle(color = TerminalWhite)) {
                    append(text)
                }
            } else {
                var lastEnd = 0
                for (span in spans.sortedBy { it.start }) {
                    // Append any plain text between spans
                    if (span.start > lastEnd) {
                        withStyle(SpanStyle(color = TerminalWhite)) {
                            append(text.substring(lastEnd, span.start))
                        }
                    }

                    // Build the span style from ANSI attributes
                    val fg = span.fgColor?.let { Color(it) } ?: TerminalWhite
                    val bg = span.bgColor?.let { Color(it) }
                    val fontWeight = when {
                        span.bold -> FontWeight.Bold
                        span.dim -> FontWeight.Light
                        else -> FontWeight.Normal
                    }
                    val fontStyle = if (span.dim) FontStyle.Italic else FontStyle.Normal

                    withStyle(SpanStyle(
                        color = fg,
                        background = bg ?: Color.Transparent,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        textDecoration = if (span.underline)
                            androidx.compose.ui.text.style.TextDecoration.Underline
                        else androidx.compose.ui.text.style.TextDecoration.None
                    )) {
                        val end = span.end.coerceAtMost(text.length)
                        val start = span.start.coerceAtLeast(0)
                        if (end > start) {
                            append(text.substring(start, end))
                        }
                    }

                    lastEnd = span.end.coerceAtMost(text.length)
                }

                // Append any remaining plain text after the last span
                if (lastEnd < text.length) {
                    withStyle(SpanStyle(color = TerminalWhite)) {
                        append(text.substring(lastEnd))
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        softWrap = true,
        modifier = Modifier.padding(vertical = 1.dp)
    )
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
            Text(
                "$ ",
                color = TerminalGreen,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )

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
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TerminalWhite
                ),
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                singleLine = true
            )

            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!termuxInstalled) {
                    Text("Shell mode",
                        color = TerminalYellow.copy(alpha = 0.4f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Text("↑↓ history",
                    color = TerminalWhite.copy(alpha = 0.2f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                Text("⏎ run",
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
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TerminalWhite)
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
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TerminalWhite)
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
