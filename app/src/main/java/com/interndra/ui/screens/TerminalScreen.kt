package com.interndra.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.TerminalAgent
import com.interndra.service.ExecutionBackend
import com.interndra.service.TermuxEnvironment
import com.interndra.terminal.TerminalEmulator
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TerminalScreen — REAL PTY terminal UI (not fake).
 *
 * Renders the TerminalEmulator's screen buffer character-by-character with
 * proper ANSI colors, accepts keyboard input that writes directly to the PTY
 * master fd, and streams AI-executed command output in real-time.
 *
 * ## Architecture
 * ```
 * PTY bash process          TerminalEmulator        Compose UI
 * (forkpty/execvp)  ──►    (ANSI parser)    ──►    (character grid)
 *       │                                                │
 *       │ PTY master fd                            User input
 *       ▼                                                ▼
 *   writeInput(text) ◄───────────────────────  Keyboard/command input
 * ```
 */
@Composable
fun TerminalScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uiState by vm.uiState.collectAsState()
    val activeSessionName by vm.activeTerminalSession.collectAsState()

    // ── Terminal output lines (from TerminalAgent's outputFlow) ────
    val outputLines = remember { mutableStateListOf<String>() }
    val isPtyActive = remember { mutableStateOf(false) }

    // Collect streaming output from TerminalAgent
    LaunchedEffect(Unit) {
        vm.terminalAgent.outputFlow.collect { event ->
            when (event) {
                is TerminalAgent.StreamEvent.Output -> {
                    val lines = event.text.split("\n")
                    outputLines.addAll(lines)
                    // Keep max 2000 lines
                    while (outputLines.size > 2000) {
                        outputLines.removeAt(0)
                    }
                }
                is TerminalAgent.StreamEvent.CommandStart -> {
                    // Mark that a command is executing
                }
                is TerminalAgent.StreamEvent.CommandEnd -> {
                    // Command finished
                }
                is TerminalAgent.StreamEvent.Error -> {
                    outputLines.add("\u001b[31m${event.message}\u001b[0m")
                }
                else -> {}
            }
        }
    }

    // Check PTY status periodically
    LaunchedEffect(Unit) {
        while (true) {
            val pty = vm.terminalAgent.getPtySession()
            isPtyActive.value = pty?.isRunning == true
            kotlinx.coroutines.delay(2000)
        }
    }

    // ── Emulator screen buffer (polled fresh each frame) ──────────
    val screenChars = remember { mutableStateOf<List<CharArray>>(emptyList()) }
    val cursorPosition = remember { mutableIntStateOf(0) }

    // Poll emulator screen buffer every ~50ms for rendering
    LaunchedEffect(Unit) {
        while (true) {
            val session = vm.terminalAgent.getPtySession()
            if (session?.isRunning == true) {
                screenChars.value = session.emulator.getScreenChars()
                cursorPosition.intValue = session.emulator.cursorRow * session.emulator.columns + session.emulator.cursorCol
            }
            kotlinx.coroutines.delay(50)
        }
    }

    // ── Command input state ──────────────────────────────────────
    var commandInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val outputScrollState = remember { ScrollState(0) }

    // Auto-scroll to bottom when new output arrives
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    // ── Auto-focus input on launch ───────────────────────────────
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
    ) {
        // ── Status bar ───────────────────────────────────────────
        TerminalStatusBar(
            isPtyActive = isPtyActive.value,
            mode = vm.terminalAgent.currentMode,
            sessionName = activeSessionName,
            onOpenDrawer = onOpenDrawer,
            onClear = { outputLines.clear() }
        )

        // ── Terminal output area ────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D))
                .verticalScroll(outputScrollState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ── MUTUALLY EXCLUSIVE rendering: PTY buffer XOR streaming output ──
                // Fix: When PTY is active, ONLY show the PTY screen buffer.
                // When PTY is NOT active, show streaming output lines.
                // Previously both were shown simultaneously causing duplicate content.
                if (isPtyActive.value && screenChars.value.isNotEmpty()) {
                    // ── PTY Screen buffer rendering ────────────────
                    // Use derivedStateOf so styles re-compute when emulator state changes
                    // (not just when screenChars identity changes — fixes stale color bug).
                    val screenStyles by remember {
                        derivedStateOf {
                            vm.terminalAgent.getPtySession()?.emulator?.getScreenStyles() ?: emptyList()
                        }
                    }
                    val cursorRow = vm.terminalAgent.getPtySession()?.emulator?.cursorRow ?: 0
                    val cursorCol = vm.terminalAgent.getPtySession()?.emulator?.cursorCol ?: 0

                    screenChars.value.forEachIndexed { rowIdx, row ->
                        val styleRow = screenStyles.getOrNull(rowIdx)
                        // Overlay cursor ON the character row (not as a separate line)
                        val displayRow = if (rowIdx == cursorRow && row.isNotEmpty()) {
                            // Replace character at cursor column with cursor block
                            val modified = row.copyOf()
                            val col = cursorCol.coerceIn(0, modified.size - 1)
                            modified[col] = '\u2588' // Full block = cursor
                            modified
                        } else {
                            row
                        }
                        // Apply cursor highlight style at cursor position
                        val displayStyles = if (rowIdx == cursorRow && styleRow != null) {
                            val mod = styleRow.copyOf()
                            val col = cursorCol.coerceIn(0, mod.size - 1)
                            mod[col] = mod[col] or 0x200000 // reverse video for cursor
                            mod
                        } else {
                            styleRow
                        }
                        val annotatedLine = buildAnnotatedStringForRow(displayRow, displayStyles)
                        Text(
                            text = annotatedLine,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                } else {
                    // ── Streaming output lines (only when PTY not active) ──
                    outputLines.forEach { line ->
                        Text(
                            text = renderAnsiLine(line),
                            color = TerminalGreen,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false
                        )
                    }

                    // ── Empty state ────────────────────────────
                    if (outputLines.isEmpty()) {
                        TerminalWelcomeMessage(
                            mode = vm.terminalAgent.currentMode,
                            onInstallTermux = { vm.installEmbeddedTermux(
                                onProgress = { progress ->
                                    outputLines.add("📦 $progress")
                                },
                                onComplete = { success, msg ->
                                    outputLines.add(if (success) "\u001b[32m$msg\u001b[0m" else "\u001b[31m$msg\u001b[0m")
                                }
                            )}
                        )
                    }
                }
            }
        }

        // ── Extra Keys Row ────────────────────────────────────
        ExtraKeysRow(
            onKey = { key ->
                scope.launch {
                    val pty = vm.terminalAgent.getPtySession()
                    val usePty = pty?.isRunning == true
                    when (key) {
                        // ── Navigation keys ──
                        "Tab" -> if (usePty) pty?.writeInput("\t") else vm.terminalAgent.execute(activeSessionName, "\t")
                        "Esc" -> if (usePty) pty?.writeInput("\u001B") else vm.terminalAgent.execute(activeSessionName, "\u001B")
                        "Up", "↑" -> if (usePty) pty?.writeInput("\u001B[A") else vm.terminalAgent.execute(activeSessionName, "\u001B[A")
                        "Down", "↓" -> if (usePty) pty?.writeInput("\u001B[B") else vm.terminalAgent.execute(activeSessionName, "\u001B[B")
                        "Left", "←" -> if (usePty) pty?.writeInput("\u001B[D") else vm.terminalAgent.execute(activeSessionName, "\u001B[D")
                        "Right", "→" -> if (usePty) pty?.writeInput("\u001B[C") else vm.terminalAgent.execute(activeSessionName, "\u001B[C")
                        // ── Ctrl combos ──
                        "Ctrl+A" -> if (usePty) pty?.writeInput("\u0001") else vm.terminalAgent.sendControlChar(activeSessionName, '\u0001')
                        "Ctrl+E" -> if (usePty) pty?.writeInput("\u0005") else vm.terminalAgent.sendControlChar(activeSessionName, '\u0005')
                        "Ctrl+W" -> if (usePty) pty?.writeInput("\u0017") else vm.terminalAgent.sendControlChar(activeSessionName, '\u0017')
                        "Ctrl+U" -> if (usePty) pty?.writeInput("\u0015") else vm.terminalAgent.sendControlChar(activeSessionName, '\u0015')
                        "Ctrl+R" -> if (usePty) pty?.writeInput("\u0012") else vm.terminalAgent.sendControlChar(activeSessionName, '\u0012')
                        // ── Alt+arrows ──
                        "Alt+←" -> if (usePty) pty?.writeInput("\u001B\u0062") else vm.terminalAgent.execute(activeSessionName, "\u001Bb")
                        "Alt+→" -> if (usePty) pty?.writeInput("\u001B\u0066") else vm.terminalAgent.execute(activeSessionName, "\u001Bf")
                        "Alt+↑" -> if (usePty) pty?.writeInput("\u001B\u001B[A") else vm.terminalAgent.execute(activeSessionName, "\u001B\u001B[A")
                        "Alt+↓" -> if (usePty) pty?.writeInput("\u001B\u001B[B") else vm.terminalAgent.execute(activeSessionName, "\u001B\u001B[B")
                        // ── Literal characters ──
                        "/" -> if (usePty) pty?.writeInput("/") else vm.terminalAgent.execute(activeSessionName, "/")
                        "-" -> if (usePty) pty?.writeInput("-") else vm.terminalAgent.execute(activeSessionName, "-")
                    }
                }
            }
        )

        // ── Bottom input bar ───────────────────────────────────
        TerminalInputBar(
            commandInput = commandInput,
            onCommandChange = { commandInput = it },
            onExecute = { cmd ->
                if (cmd.isNotBlank()) {
                    outputLines.add("\u001b[32m$\u001b[0m $cmd")
                    scope.launch {
                        vm.terminalAgent.execute(activeSessionName, cmd)
                    }
                    commandInput = ""
                }
            },
            onCtrlC = {
                scope.launch {
                    vm.terminalAgent.sendControlChar(activeSessionName, '\u0003')
                }
            },
            onCtrlD = {
                scope.launch {
                    vm.terminalAgent.sendControlChar(activeSessionName, '\u0004')
                }
            },
            onCtrlL = {
                outputLines.clear()
            },
            focusRequester = focusRequester,
            isPtyActive = isPtyActive.value
        )

        // ── Bottom execution mode indicator ────────────────────
        ExecutionModeBar(vm = vm)
    }
}

// ── Terminal Status Bar ──────────────────────────────────────────────────
@Composable
private fun TerminalStatusBar(
    isPtyActive: Boolean,
    mode: TermuxEnvironment.ExecMode,
    sessionName: String,
    onOpenDrawer: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = Color(0xFF0A0A0A),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }

            // Indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (isPtyActive) TerminalGreen else TerminalYellow,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            Spacer(Modifier.width(6.dp))

            Text(
                if (isPtyActive) "PTY" else "SH",
                color = if (isPtyActive) TerminalGreen else TerminalYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${mode.emoji} ${mode.label}",
                color = TerminalWhite.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.weight(1f))

            Text(
                "session: $sessionName",
                color = TerminalWhite.copy(alpha = 0.3f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.width(8.dp))

            // Clear button
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.DeleteSweep, "Clear", tint = TerminalWhite.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── Terminal Input Bar ──────────────────────────────────────────────────
@Composable
private fun TerminalInputBar(
    commandInput: String,
    onCommandChange: (String) -> Unit,
    onExecute: (String) -> Unit,
    onCtrlC: () -> Unit,
    onCtrlD: () -> Unit,
    onCtrlL: () -> Unit,
    focusRequester: FocusRequester,
    isPtyActive: Boolean
) {
    Surface(
        color = Color(0xFF0A0A0A),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prompt character
            Text(
                text = if (isPtyActive) "❯" else "$",
                color = if (isPtyActive) TerminalGreen else TerminalYellow,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp)
            )

            // Text input field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = commandInput,
                    onValueChange = { onCommandChange(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TerminalWhite,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(TerminalGreen),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (commandInput.isNotBlank()) {
                                onExecute(commandInput.trim())
                            }
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (commandInput.isEmpty()) {
                            Text(
                                text = if (isPtyActive) "Type a command or ask AI..." else "Shell ready — type 'help'",
                                color = TerminalWhite.copy(alpha = 0.25f),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(Modifier.width(4.dp))

            // Ctrl+C button
            SmallIconButton(
                icon = Icons.Default.Cancel,
                label = "Ctrl+C",
                tint = TerminalRed.copy(alpha = 0.7f),
                onClick = onCtrlC
            )

            // Ctrl+D button
            SmallIconButton(
                icon = Icons.Default.Stop,
                label = "Ctrl+D",
                tint = TerminalYellow.copy(alpha = 0.7f),
                onClick = onCtrlD
            )

            // Enter button
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (commandInput.isNotBlank()) TerminalGreen.copy(alpha = 0.15f) else Color.Transparent,
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = commandInput.isNotBlank()) {
                        onExecute(commandInput.trim())
                    }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Send,
                        "Run",
                        tint = if (commandInput.isNotBlank()) TerminalGreen else TerminalWhite.copy(alpha = 0.2f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

// ── Execution Mode Bar ──────────────────────────────────────────────────
@Composable
private fun ExecutionModeBar(vm: HybridAgentViewModel) {
    val agent = vm.terminalAgent
    val mode = agent.currentMode
    val backend = agent.executionBackendDescription

    // Poll PTY session periodically instead of stale `remember`
    var pid by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            pid = agent.getPtySession()?.childPid ?: -1
            kotlinx.coroutines.delay(2000)
        }
    }

    Surface(
        color = Color(0xFF080808),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (mode) {
                    TermuxEnvironment.ExecMode.TERMUX -> "🐧 Termux"
                    TermuxEnvironment.ExecMode.SHIZUKU -> "🔑 Shizuku ADB"
                    TermuxEnvironment.ExecMode.ROOT -> "🛡️ Root Shell"
                    TermuxEnvironment.ExecMode.FALLBACK -> "⚙️ Fallback"
                },
                color = TerminalGreen,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = backend,
                color = TerminalWhite.copy(alpha = 0.3f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.weight(1f))

            if (pid > 0) {
                Text(
                    text = "PID $pid",
                    color = TerminalWhite.copy(alpha = 0.25f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Terminal Welcome Message ────────────────────────────────────────────
@Composable
private fun TerminalWelcomeMessage(
    mode: TermuxEnvironment.ExecMode,
    onInstallTermux: () -> Unit = {}
) {
    Column(
        modifier = Modifier.padding(8.dp)
    ) {
        // ── ASCII Art Header ────────────────────────────────────
        Text(
            text = """
╔══════════════════════════════════════╗
║       INTENDRA TERMINAL v2.1         ║
║   Real PTY terminal — Type a command ║
╚══════════════════════════════════════╝
            """.trimIndent(),
            color = TerminalGreen.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(4.dp))

        // ── Mode-specific message ──────────────────────────────
        when (mode) {
            TermuxEnvironment.ExecMode.FALLBACK -> {
                Text(
                    text = "⚠️ Fallback Mode — No Termux environment detected",
                    color = TerminalYellow,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Install Embedded Termux to unlock:",
                    color = TerminalWhite.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "  • Full Linux bash environment",
                    color = TerminalWhite.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "  • apt, pkg package manager",
                    color = TerminalWhite.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "  • python3, git, node, npm, pip",
                    color = TerminalWhite.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))

                // Install button
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TerminalGreen.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, TerminalGreen.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .clickable { onInstallTermux() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            text = "📥",
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Install Embedded Termux (~25MB)",
                            color = TerminalGreen,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Or ask AI: \"install embedded termux bootstrap\"",
                    color = TerminalWhite.copy(alpha = 0.3f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            TermuxEnvironment.ExecMode.TERMUX -> {
                Text(
                    text = "🐧 Termux mode active — bash ready",
                    color = TerminalGreen,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "  • PRoot-distro: install Ubuntu/Debian/Arch via \"proot-distro install ubuntu\"",
                    color = TerminalWhite.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "  • Full Linux distro with apt, pacman, dnf package managers",
                    color = TerminalWhite.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            TermuxEnvironment.ExecMode.SHIZUKU -> {
                Text(
                    text = "🔑 Shizuku ADB — elevated shell ready",
                    color = TerminalBlue,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            TermuxEnvironment.ExecMode.ROOT -> {
                Text(
                    text = "🛡️ Root shell — full system access",
                    color = TerminalRed.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Commands: Ctrl+C=Interrupt  Ctrl+D=EOF  Ctrl+L=Clear  Type 'help'",
            color = TerminalWhite.copy(alpha = 0.25f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "❯ Waiting for shell...",
            color = TerminalYellow,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── ANSI rendering helper ──────────────────────────────────────────────
/**
 * Extra Keys Row — like Termux's extra keys row.
 * Provides quick access to Tab, Esc, arrow keys, and common Ctrl combos.
 */
@Composable
private fun ExtraKeysRow(
    onKey: (String) -> Unit
) {
    Surface(
        color = Color(0xFF0D0D0D),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Extra key buttons
            ExtraKey("Esc", onKey)
            ExtraKey("Tab", onKey)
            ExtraKey("/", onKey)
            ExtraKey("-", onKey)
            DividerLine()
            ExtraKey("←", onKey)
            ExtraKey("→", onKey)
            ExtraKey("↑", onKey)
            ExtraKey("↓", onKey)
            DividerLine()
            ExtraKey("Ctrl+A", onKey)
            ExtraKey("Ctrl+E", onKey)
            ExtraKey("Ctrl+W", onKey)
            ExtraKey("Ctrl+U", onKey)
            ExtraKey("Ctrl+R", onKey)
            DividerLine()
            ExtraKey("Alt+←", onKey)
            ExtraKey("Alt+→", onKey)
            ExtraKey("Alt+↑", onKey)
            ExtraKey("Alt+↓", onKey)
        }
    }
}

@Composable
private fun ExtraKey(label: String, onKey: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1A1A1A),
        border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
        modifier = Modifier
            .clickable { onKey(label) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = TerminalWhite.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(TerminalWhite.copy(alpha = 0.1f))
    )
}

/**
 * Strips ANSI escape codes from a line and returns the plain text.
 * For the real terminal, we render the TerminalEmulator's screen buffer
 * which has already parsed all ANSI codes. For streaming output lines,
 * we strip codes here.
 */
private fun renderAnsiLine(line: String): String {
    // Strip ANSI escape sequences for display
    return line.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
        .replace(Regex("\u001b\\][0-9;]*[a-zA-Z]"), "")
        .replace("\u001b", "")
        .replace("\u0007", "")
}

/**
 * Build an AnnotatedString from a row of characters and optional styles.
 *
 * This batches an entire terminal row (up to 120 chars) into a SINGLE
 * AnnotatedString, so only ONE Text() composable is needed per row
 * instead of one per character. Fixes Bug #2 (rendering jank).
 *
 * @param row     CharArray of characters in the row
 * @param styles  Optional IntArray of TextStyle-encoded styles per column
 */
private fun buildAnnotatedStringForRow(row: CharArray, styles: IntArray?): AnnotatedString {
    if (row.isEmpty()) return AnnotatedString("")

    return buildAnnotatedString {
        var currentSpanStart = 0
        var currentStyleCode = styles?.getOrNull(0) ?: -1

        for (col in row.indices) {
            val styleCode = styles?.getOrNull(col) ?: -1
            if (styleCode != currentStyleCode) {
                // Style changed — emit the previous segment
                if (col > currentSpanStart) {
                    val segment = String(row, currentSpanStart, col - currentSpanStart)
                    if (currentStyleCode >= 0) {
                        val spanStyle = styleCodeToSpanStyle(currentStyleCode)
                        if (spanStyle != null) {
                            pushStyle(spanStyle)
                            append(segment)
                            pop()
                        } else {
                            append(segment)
                        }
                    } else {
                        append(segment)
                    }
                }
                currentSpanStart = col
                currentStyleCode = styleCode
            }
        }
        // Emit the final segment
        if (currentSpanStart < row.size) {
            val segment = String(row, currentSpanStart, row.size - currentSpanStart)
            if (currentStyleCode >= 0) {
                val spanStyle = styleCodeToSpanStyle(currentStyleCode)
                if (spanStyle != null) {
                    pushStyle(spanStyle)
                    append(segment)
                    pop()
                } else {
                    append(segment)
                }
            } else {
                append(segment)
            }
        }
    }
}

/**
 * Convert a TextStyle-encoded int to a Compose SpanStyle.
 * Returns null for default style (no special formatting needed).
 *
 * TextStyle encoding (from TextStyle.kt):
 *   - Bits 0-7   → foreground color index (SHIFT_FG = 0)
 *   - Bits 8-15  → background color index (SHIFT_BG = 8)
 *   - Bits 16-23 → style flags (SHIFT_FLAGS = 16)
 *     - bit 16 (0x10000) → bold
 *     - bit 17 (0x20000) → dim
 *     - bit 18 (0x40000) → italic
 *     - bit 19 (0x80000) → underline
 *     - bit 20 (0x100000) → blink
 *     - bit 21 (0x200000) → reverse
 *     - bit 22 (0x400000) → strikethrough
 *     - bit 23 (0x800000) → hidden
 */
private fun styleCodeToSpanStyle(styleCode: Int): SpanStyle? {
    val foreground = styleCode and 0xFF
    val hasBold = (styleCode and 0x10000) != 0
    val hasUnderline = (styleCode and 0x80000) != 0
    val isItalic = (styleCode and 0x40000) != 0

    // Map ANSI color index → Compose Color
    val color = when (foreground) {
        0 -> null                          // black / default bg
        1 -> TerminalRed                   // red
        2 -> TerminalGreen                 // green
        3 -> TerminalYellow                // yellow
        4 -> TerminalBlue                  // blue
        5 -> TerminalMagenta               // magenta
        6 -> TerminalCyan                  // cyan
        7 -> null                          // white/default fg → use default
        in 8..15 -> when (foreground - 8) { // bright colors
            0 -> TerminalWhite.copy(alpha = 0.6f)  // bright black (grey)
            1 -> TerminalRed                         // bright red
            2 -> TerminalGreen                       // bright green
            3 -> TerminalYellow                      // bright yellow
            4 -> TerminalBlue                         // bright blue
            5 -> TerminalMagenta                      // bright magenta
            6 -> TerminalCyan                         // bright cyan
            7 -> TerminalWhite                        // bright white
            else -> null
        }
        else -> null                       // 256-color / true-color → default for now
    }

    if (color == null && !hasBold && !hasUnderline && !isItalic) return null
    return SpanStyle(
        color = color ?: TerminalGreen,
        fontWeight = if (hasBold) FontWeight.Bold else null,
        fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = if (hasUnderline) androidx.compose.ui.text.style.TextDecoration.Underline else null
    )
}
