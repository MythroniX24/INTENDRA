package com.interndra.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.AgentActivity
import com.interndra.agent.AgentState
import com.interndra.agent.TerminalAgent
import com.interndra.ai.SafetyEngine
import com.interndra.terminal.TerminalSession as PtyTerminalSession
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// INTENDRA Terminal Screen — light/white premium UI over the existing
// Linux / PTY backend (TerminalAgent). The terminal OUTPUT area uses a dark
// monospace surface for authentic readability (spec §2 exception); the rest
// of the screen stays white/light and consistent with the Chat Screen.
// ─────────────────────────────────────────────────────────────────────────────

// ── Dark terminal surface palette (independent of app theme) ─────────────
private val TermBg = Color(0xFF0D0D0D)
private val TermSurface = Color(0xFF141417)
private val TermFg = Color(0xFFE8EAED)
private val TermGreen = Color(0xFF81C995)
private val TermRed = Color(0xFFF28B82)
private val TermYellow = Color(0xFFFDE293)
private val TermBlue = Color(0xFF8AB4F8)
private val TermMagenta = Color(0xFFC678DD)
private val TermCyan = Color(0xFF56B6C2)

private const val MAX_STREAM_LINES = 2000
private val ANSI_PATTERN = Regex("\u001b\\[[0-9;]*m")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: HybridAgentViewModel) {
    val colors = LocalInterndraColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val linuxEnv by vm.linuxEnvState.collectAsState()
    val sessions by vm.terminalSessions.collectAsState()
    val activeSession by vm.activeTerminalSession.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val agentActivity by vm.agentActivity.collectAsState()

    var mode by rememberSaveable { mutableStateOf("terminal") } // "terminal" | "ai"
    var commandInput by rememberSaveable { mutableStateOf("") }
    var confirmCommand by remember { mutableStateOf<String?>(null) }
    var confirmReason by remember { mutableStateOf("") }
    var blockedReason by remember { mutableStateOf<String?>(null) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var envChecked by remember { mutableStateOf(false) }

    // ── Streaming output (from TerminalAgent.outputFlow) ────────────────
    val outputLines = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        vm.terminalAgent.outputFlow.collect { event ->
            when (event) {
                is TerminalAgent.StreamEvent.Output -> {
                    outputLines.addAll(event.text.split("\n"))
                    while (outputLines.size > MAX_STREAM_LINES) outputLines.removeAt(0)
                }
                is TerminalAgent.StreamEvent.Error -> {
                    outputLines.add("\u001b[31m⚠ ${event.message}\u001b[0m")
                    while (outputLines.size > MAX_STREAM_LINES) outputLines.removeAt(0)
                }
                else -> { /* CommandStart/End/Job events — status only */ }
            }
        }
    }

    // ── PTY screen buffer polling (~50ms, only renders while active) ────
    var ptyActive by remember { mutableStateOf(false) }
    var screenChars by remember { mutableStateOf<List<CharArray>>(emptyList()) }
    var screenStyles by remember { mutableStateOf<List<IntArray>>(emptyList()) }
    var cursorRow by remember { mutableIntStateOf(0) }
    var cursorCol by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val pty = vm.terminalAgent.getPtySession()
            if (pty != null && pty.isRunning) {
                ptyActive = true
                screenChars = pty.emulator.getScreenChars()
                screenStyles = pty.emulator.getScreenStyles()
                cursorRow = pty.emulator.cursorRow
                cursorCol = pty.emulator.cursorCol
            } else {
                ptyActive = false
            }
            delay(50)
        }
    }

    // ── Light Linux-environment check on open ───────────────────────────
    LaunchedEffect(Unit) {
        if (!vm.linuxEnvState.value.installed) {
            withContext(Dispatchers.IO) { runCatching { vm.linuxEnvironmentManager.check() } }
        }
        envChecked = true
    }

    val agentActive = agentState.isActive()
    val showPtyBuffer = ptyActive && !(mode == "ai" && agentActive)
    val linuxNotReady = envChecked && !linuxEnv.installed && !ptyActive
    val workdir = vm.terminalAgent.getWorkdir(activeSession)
    val safety = remember { SafetyEngine() }

    fun runCommand(cmd: String) {
        scope.launch {
            val pty = vm.terminalAgent.getPtySession()
            if (pty?.isRunning == true) {
                pty.writeInput("$cmd\n")
            } else {
                vm.terminalAgent.execute(activeSession, cmd)
            }
        }
    }

    fun submit() {
        val cmd = commandInput.trim()
        if (cmd.isBlank()) return
        commandInput = ""
        if (mode == "ai") {
            vm.sendCommand(cmd)
            return
        }
        val report = safety.validate(cmd)
        when (report.result) {
            SafetyEngine.ValidationResult.BLOCKED -> blockedReason = report.reason
            SafetyEngine.ValidationResult.REQUIRES_CONFIRMATION -> {
                confirmCommand = cmd
                confirmReason = report.reason
            }
            SafetyEngine.ValidationResult.SAFE -> runCommand(cmd)
        }
    }

    fun sendKeyToPty(key: String) {
        scope.launch {
            val pty = vm.terminalAgent.getPtySession()
            if (pty?.isRunning == true) {
                when (key) {
                    "Esc" -> pty.writeInput("\u001B")
                    "Tab" -> pty.writeInput("\t")
                    "↑" -> pty.writeInput("\u001B[A")
                    "↓" -> pty.writeInput("\u001B[B")
                    "←" -> pty.writeInput("\u001B[D")
                    "→" -> pty.writeInput("\u001B[C")
                    "Ctrl+C" -> pty.sendCtrlC()
                    "Ctrl+D" -> pty.sendCtrlD()
                    "Ctrl+L" -> pty.writeInput("\u000C")
                    else -> pty.writeInput(key)
                }
            } else {
                when (key) {
                    "Ctrl+C" -> vm.terminalAgent.sendControlChar(activeSession, '\u0003')
                    "Ctrl+D" -> vm.terminalAgent.sendControlChar(activeSession, '\u0004')
                    else -> outputLines.add("\u001b[33mPTY unavailable — start the Linux session first\u001b[0m")
                }
            }
        }
    }

    fun copyOutput() {
        val text = if (showPtyBuffer) {
            screenChars.joinToString("\n") { String(it).trimEnd() }
        } else {
            outputLines.joinToString("\n") { ANSI_PATTERN.replace(it, "") }
        }
        @Suppress("DEPRECATION")
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Terminal output copied", Toast.LENGTH_SHORT).show()
    }

    fun shareOutput() {
        val text = if (showPtyBuffer) {
            screenChars.joinToString("\n") { String(it).trimEnd() }
        } else {
            outputLines.joinToString("\n") { ANSI_PATTERN.replace(it, "") }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share terminal output")) }
    }

    fun startPtyIfPossible() {
        scope.launch {
            val env = vm.termuxEnvironment
            if (env.hasTermux()) {
                val info = env.info.value
                val config = PtyTerminalSession.TermuxSessionConfig(prefix = info.bootstrapPrefix)
                vm.terminalAgent.startPtySession(config)
            }
        }
    }

    // ── Header ──────────────────────────────────────────────────────────
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var headerMenuOpen by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Terminal, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (ptyActive) "🐧 Linux • Shell ready • $workdir"
                           else if (linuxEnv.installed) "Linux • ${vm.terminalAgent.getModeDescription()} • $workdir"
                           else "Android shell fallback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Session status dot
            val statusColor = when {
                ptyActive -> colors.success
                linuxEnv.installed -> colors.statusIdle
                else -> colors.danger
            }
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(statusColor)
            )
            IconButton(onClick = { headerMenuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "Terminal actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New session") },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                    onClick = { headerMenuOpen = false; showNewSessionDialog = true }
                )
                DropdownMenuItem(
                    text = { Text("Session info") },
                    leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp)) },
                    onClick = { headerMenuOpen = false; showInfoSheet = true }
                )
                DropdownMenuItem(
                    text = { Text("Interrupt (Ctrl+C)") },
                    leadingIcon = { Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)) },
                    onClick = { headerMenuOpen = false; sendKeyToPty("Ctrl+C") }
                )
                DropdownMenuItem(
                    text = { Text("Copy output") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) },
                    onClick = { headerMenuOpen = false; copyOutput() }
                )
                DropdownMenuItem(
                    text = { Text("Share output") },
                    leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                    onClick = { headerMenuOpen = false; shareOutput() }
                )
                DropdownMenuItem(
                    text = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)) },
                    onClick = {
                        headerMenuOpen = false
                        outputLines.clear()
                        scope.launch {
                            val pty = vm.terminalAgent.getPtySession()
                            if (pty?.isRunning == true) pty.writeInput("\u000C")
                        }
                    }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        // ── Mode switch + session chips ─────────────────────────────────
        ModeAndSessionRow(
            mode = mode,
            onModeChange = { mode = it },
            sessions = sessions,
            activeSession = activeSession,
            onSelectSession = { vm.setActiveTerminalSession(it) },
            onNewSession = { showNewSessionDialog = true }
        )

        // ── AI activity panel (AI mode only) ────────────────────────────
        AnimatedVisibility(visible = mode == "ai" && agentActive, enter = fadeIn(), exit = fadeOut()) {
            AiActivityPanel(agentState = agentState, activities = agentActivity)
        }

        // ── Terminal surface ─────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (linuxNotReady) {
                SetupState(
                    linuxEnv = linuxEnv,
                    onCheck = {
                        scope.launch(Dispatchers.IO) { runCatching { vm.linuxEnvironmentManager.check() } }
                    },
                    onRepair = {
                        scope.launch(Dispatchers.IO) { runCatching { vm.linuxEnvironmentManager.repair() } }
                        startPtyIfPossible()
                    },
                    onReinstall = {
                        scope.launch(Dispatchers.IO) { runCatching { vm.linuxEnvironmentManager.reinstall() } }
                        startPtyIfPossible()
                    }
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TermBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E22)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showPtyBuffer) {
                        PtyBufferView(
                            chars = screenChars,
                            styles = screenStyles,
                            cursorRow = cursorRow,
                            cursorCol = cursorCol
                        )
                    } else {
                        StreamingView(
                            lines = outputLines,
                            envChecked = envChecked,
                            linuxInstalled = linuxEnv.installed
                        )
                    }
                }
            }
        }

        // ── Safety confirmation / blocked card ──────────────────────────
        blockedReason?.let { reason ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Command blocked — $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { blockedReason = null }) { Text("Got it") }
                }
            }
        }
        confirmCommand?.let { cmd ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFF6E0),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE6B84F)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "⚠ $confirmReason",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7A5A00),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "$ $cmd",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF7A5A00),
                        modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmCommand = null }) { Text("Cancel") }
                        Button(
                            onClick = {
                                runCommand(cmd)
                                confirmCommand = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB26A00))
                        ) { Text("Run anyway") }
                    }
                }
            }
        }

        // ── Extra keys row ───────────────────────────────────────────────
        ExtraKeysRow(onKey = { sendKeyToPty(it) })

        // ── Command input ─────────────────────────────────────────────────
        TerminalInputBar(
            mode = mode,
            value = commandInput,
            onValueChange = { commandInput = it },
            onSubmit = { submit() },
            agentActive = agentActive,
            onStop = { vm.stopGeneration() }
        )
    }

    // ── New session dialog ────────────────────────────────────────────────
    if (showNewSessionDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("New terminal session") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session name") },
                    placeholder = { Text("e.g. project") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim().ifEmpty { "session-${sessions.size + 1}" }
                    vm.createTerminalSession(n)
                    vm.setActiveTerminalSession(n)
                    showNewSessionDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Session info sheet ────────────────────────────────────────────────
    if (showInfoSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showInfoSheet = false }, sheetState = sheetState) {
            SessionInfoSheet(
                linuxEnv = linuxEnv,
                backend = vm.terminalAgent.executionBackendDescription,
                modeDesc = vm.terminalAgent.getModeDescription(),
                sessions = sessions,
                activeSession = activeSession,
                workdirOf = { vm.terminalAgent.getWorkdir(it) },
                canStartPty = !ptyActive && vm.termuxEnvironment.hasTermux(),
                onStartPty = {
                    showInfoSheet = false
                    startPtyIfPossible()
                },
                onSelect = { vm.setActiveTerminalSession(it) },
                onDelete = { vm.removeTerminalSession(it) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode switch + session chips
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ModeAndSessionRow(
    mode: String,
    onModeChange: (String) -> Unit,
    sessions: List<String>,
    activeSession: String,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit
) {
    val colors = LocalInterndraColors.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Segmented control
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceElevated,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
        ) {
            Row(Modifier.padding(3.dp)) {
                ModeChip("terminal", "Terminal", mode, onModeChange, colors)
                ModeChip("ai", "AI", mode, onModeChange, colors)
            }
        }
        Spacer(Modifier.width(4.dp))
        sessions.forEach { name ->
            val selected = name == activeSession
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) colors.accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelectSession(name) }
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(if (selected) colors.accent else MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // New session chip
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onNewSession() }
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, "New session", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, text: String, current: String, onChange: (String) -> Unit, colors: com.interndra.ui.theme.InterndraColors) {
    val selected = label == current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.accent else Color.Transparent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(label) }
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AI activity panel (spec §8–§9)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AiActivityPanel(agentState: AgentState, activities: List<AgentActivity>) {
    val colors = LocalInterndraColors.current
    val infinite = rememberInfiniteTransition()
    val alpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600))
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).animateContentSize()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✦ Working", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(alpha))
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                Text(agentState.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = colors.accent
            )
            activities.takeLast(4).forEach { a ->
                ActivityLine(a)
            }
            Text(
                "Full response appears in the Chat tab.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ActivityLine(a: AgentActivity) {
    val colors = LocalInterndraColors.current
    val (prefix, text, color) = when (a) {
        is AgentActivity.Thinking -> Triple("●", a.message, MaterialTheme.colorScheme.onSurfaceVariant)
        is AgentActivity.Planning -> Triple("●", a.message, MaterialTheme.colorScheme.onSurfaceVariant)
        is AgentActivity.ToolStart -> Triple("●", if (a.description.isNotBlank()) "${a.tool} — ${a.description}" else a.tool, colors.accent)
        is AgentActivity.ToolResult -> Triple(if (a.success) "✓" else "✕", a.summary, if (a.success) colors.success else colors.danger)
        is AgentActivity.Search -> Triple("🔍", a.query, colors.accent)
        is AgentActivity.Reading -> Triple("📖", a.message, MaterialTheme.colorScheme.onSurfaceVariant)
        is AgentActivity.Verification -> Triple(if (a.success) "✓" else "✕", a.message, if (a.success) colors.success else colors.danger)
        is AgentActivity.Error -> Triple("✕", a.message, colors.danger)
        is AgentActivity.Completed -> Triple("✓", "Completed", colors.success)
    }
    Text(
        "$prefix $text",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PTY screen buffer rendering (dark terminal surface)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PtyBufferView(
    chars: List<CharArray>,
    styles: List<IntArray>,
    cursorRow: Int,
    cursorCol: Int
) {
    val hScroll = rememberScrollState()
    Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            chars.forEachIndexed { rowIdx, row ->
                val styleRow = styles.getOrNull(rowIdx)
                val displayRow = if (rowIdx == cursorRow && row.isNotEmpty()) {
                    val modified = row.copyOf()
                    val col = cursorCol.coerceIn(0, modified.size - 1)
                    modified[col] = '\u2588'
                    modified
                } else {
                    row
                }
                val displayStyles = if (rowIdx == cursorRow && styleRow != null) {
                    val mod = styleRow.copyOf()
                    val col = cursorCol.coerceIn(0, mod.size - 1)
                    mod[col] = mod[col] or 0x200000 // reverse video for cursor
                    mod
                } else {
                    styleRow
                }
                Text(
                    text = buildAnnotatedRow(displayRow, displayStyles),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    color = TermFg
                )
            }
        }
    }
}

/**
 * Streaming output view — newest line at index 0 (reversed) so that
 * "follow latest" is simply staying at the top (deterministic auto-scroll).
 */
@Composable
private fun StreamingView(
    lines: List<String>,
    envChecked: Boolean,
    linuxInstalled: Boolean
) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            follow = idx == 0
        }
    }

    val reversed = lines.asReversed()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            item(key = "banner") {
                if (!envChecked) {
                    Text("Checking Linux environment…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermYellow)
                } else if (linuxInstalled) {
                    Text("Shell ready — type a command below.", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermGreen)
                }
            }
            itemsIndexed(reversed) { index, line ->
                Text(
                    text = ansiToAnnotated(line),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.alpha(if (line.isBlank()) 0.3f else 1f)
                )
            }
        }

        // Jump to latest
        AnimatedVisibility(
            visible = !follow && lines.size > 30,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable {
                    follow = true
                    keyboard?.hide()
                }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ArrowDownward, "Jump to latest", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Jump to latest", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup / not-ready state (spec §16)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SetupState(
    linuxEnv: com.interndra.service.LinuxEnvironmentManager.EnvironmentState,
    onCheck: () -> Unit,
    onRepair: () -> Unit,
    onReinstall: () -> Unit
) {
    val colors = LocalInterndraColors.current
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(colors.surfaceCard), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Terminal, null, tint = colors.accent, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("Linux environment isn't ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                linuxEnv.error ?: "The embedded Linux shell isn't installed yet. Install or repair it to get a full terminal with bash, git, Python and more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                linuxEnv.progress.takeIf { it.isNotBlank() } ?: "Basic Android shell commands still work below.",
                style = MaterialTheme.typography.labelSmall,
                color = if (linuxEnv.phase == com.interndra.service.LinuxEnvironmentManager.Phase.ERROR) colors.danger else colors.statusIdle
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheck) { Text("Check") }
                Button(onClick = onRepair) { Text("Install / Repair") }
                OutlinedButton(onClick = onReinstall) { Text("Reinstall") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Arch: ${linuxEnv.archLabel.ifEmpty { "detecting…" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extra keys row (spec §14)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ExtraKeysRow(onKey: (String) -> Unit) {
    val colors = LocalInterndraColors.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("Esc", "Tab", "Ctrl+C", "Ctrl+D", "Ctrl+L", "↑", "↓", "←", "→", "/", "-", ".").forEach { key ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onKey(key) }
            ) {
                Text(
                    key,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Command input bar (spec §7, §13)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TerminalInputBar(
    mode: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    agentActive: Boolean,
    onStop: () -> Unit
) {
    val colors = LocalInterndraColors.current
    val focusRequester = remember { FocusRequester() }
    val isAi = mode == "ai"
    val showSend = if (isAi) !agentActive else value.isNotBlank()
    val promptColor = if (isAi) colors.accent else colors.success

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().imePadding()
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.inputFieldBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputFieldBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (isAi) "✦" else "❯",
                        color = promptColor,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = colors.inputTextColor,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        minLines = 1,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSubmit() }),
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(
                                    if (isAi) "Ask INTENDRA… (e.g. install Python)" else "Type a command…",
                                    color = colors.inputPlaceholder,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isAi && agentActive) {
                        // Stop generation
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onStop() }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showSend) colors.accent else colors.surfaceInteractive,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = showSend) { onSubmit() }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    if (isAi) "Send to INTENDRA" else "Run",
                                    tint = if (showSend) Color.White else colors.inputPlaceholder,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (isAi) "Ask" else "Run",
                                    color = if (showSend) Color.White else colors.inputPlaceholder,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            if (isAi) {
                Text(
                    "AI requests run through the agent — live terminal output appears above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session info sheet (spec §6, §15)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SessionInfoSheet(
    linuxEnv: com.interndra.service.LinuxEnvironmentManager.EnvironmentState,
    backend: String,
    modeDesc: String,
    sessions: List<String>,
    activeSession: String,
    workdirOf: (String) -> String,
    canStartPty: Boolean,
    onStartPty: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val colors = LocalInterndraColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
        Text("Session info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Linux environment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        InfoRow("Status", if (linuxEnv.installed) "✓ Installed" else "Not installed", if (linuxEnv.installed) colors.success else colors.danger)
        if (linuxEnv.archLabel.isNotBlank()) InfoRow("Architecture", linuxEnv.archLabel, null)
        if (linuxEnv.storageUsedBytes > 0) InfoRow("Storage", linuxEnv.storageLabel, null)
        InfoRow("Backend", backend, null)
        InfoRow("Mode", modeDesc, null)
        if (canStartPty) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStartPty) { Text("Start terminal session") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        sessions.forEach { name ->
            val active = name == activeSession
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (active) colors.accent.copy(alpha = 0.08f) else colors.surfaceElevated,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(name) }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (active) Icons.Filled.Check else Icons.Filled.Terminal,
                        null,
                        tint = if (active) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            workdirOf(name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (sessions.size > 1) {
                        IconButton(onClick = { onDelete(name) }) {
                            Icon(Icons.Filled.Delete, "Delete session", tint = colors.danger, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANSI / terminal rendering helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun ansiFg(idx: Int): Color = when (idx % 8) {
    0 -> Color(0xFF6A6A6A)
    1 -> TermRed
    2 -> TermGreen
    3 -> TermYellow
    4 -> TermBlue
    5 -> TermMagenta
    6 -> TermCyan
    else -> TermFg
}

/** Convert an ANSI-colored line into an AnnotatedString with SpanStyles. */
private fun ansiToAnnotated(line: String): AnnotatedString = buildAnnotatedString {
    var styleColor: Color = TermFg
    var bold = false
    var underline = false
    var pos = 0
    for (m in ANSI_PATTERN.findAll(line)) {
        if (m.range.first > pos) {
            appendStyled(line.substring(pos, m.range.first), styleColor, bold, underline)
        }
        val codes = m.value.drop(2).dropLast(1).split(';').mapNotNull { it.toIntOrNull() }
        for (code in codes) {
            when {
                code == 0 -> { styleColor = TermFg; bold = false; underline = false }
                code == 1 -> bold = true
                code == 4 -> underline = true
                code in 30..37 -> styleColor = ansiFg(code - 30)
                code in 90..97 -> styleColor = ansiFg(code - 90 + 8)
            }
        }
        pos = m.range.last + 1
    }
    if (pos < line.length) {
        appendStyled(line.substring(pos), styleColor, bold, underline)
    }
}

private fun AnnotatedString.Builder.appendStyled(text: String, color: Color, bold: Boolean, underline: Boolean) {
    if (text.isEmpty()) return
    pushStyle(
        SpanStyle(
            color = color,
            fontWeight = if (bold) FontWeight.Bold else null,
            textDecoration = if (underline) TextDecoration.Underline else null
        )
    )
    append(text)
    pop()
}

/** Build one AnnotatedString for a PTY row, batching style runs. */
private fun buildAnnotatedRow(row: CharArray, styles: IntArray?): AnnotatedString {
    if (row.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var spanStart = 0
        var currentCode = styles?.getOrNull(0) ?: -1
        for (col in row.indices) {
            val code = styles?.getOrNull(col) ?: -1
            if (code != currentCode) {
                if (col > spanStart) {
                    appendStyledSegment(String(row, spanStart, col - spanStart), currentCode)
                }
                spanStart = col
                currentCode = code
            }
        }
        if (spanStart < row.size) {
            appendStyledSegment(String(row, spanStart, row.size - spanStart), currentCode)
        }
    }
}

private fun AnnotatedString.Builder.appendStyledSegment(text: String, styleCode: Int) {
    if (text.isEmpty()) return
    val style = styleCodeToSpan(styleCode)
    if (style != null) {
        pushStyle(style)
        append(text)
        pop()
    } else {
        append(text)
    }
}

/**
 * TextStyle encoding (from terminal/TextStyle.kt):
 *  - bits 0-7   → foreground color index
 *  - bits 8-15  → background color index
 *  - bit 16 (0x10000) bold · bit 17 (0x20000) dim · bit 18 (0x40000) italic
 *  - bit 19 (0x80000) underline · bit 20 (0x100000) blink · bit 21 (0x200000) reverse
 */
private fun styleCodeToSpan(styleCode: Int): SpanStyle? {
    val fg = styleCode and 0xFF
    val bold = (styleCode and 0x10000) != 0
    val italic = (styleCode and 0x40000) != 0
    val underline = (styleCode and 0x80000) != 0
    val reverse = (styleCode and 0x200000) != 0

    val color: Color? = when (fg) {
        0 -> TermFg
        1 -> TermRed
        2 -> TermGreen
        3 -> TermYellow
        4 -> TermBlue
        5 -> TermMagenta
        6 -> TermCyan
        7 -> TermFg
        in 8..15 -> ansiFg(fg - 8)
        else -> null
    }
    if (color == null && !bold && !italic && !underline && !reverse) return null
    return SpanStyle(
        color = if (reverse) TermBg else (color ?: TermFg),
        background = if (reverse) TermFg else Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null
    )
}
