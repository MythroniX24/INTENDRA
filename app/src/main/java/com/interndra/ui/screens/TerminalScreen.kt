package com.interndra.ui.screens

import android.util.Log
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.TerminalAgent
import com.interndra.terminal.TerminalEmulator
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TerminalScreen — REAL PTY-based terminal emulator.
 *
 * ## Architecture
 *
 * This terminal screen ALWAYS renders from the real PTY session's emulator
 * screen buffer. There is NO fake/fallback display. If the real PTY is not
 * available, a clear status message is shown instead.
 *
 * ```\n * User input → PTY stdin (writeInput) → bash/process → PTY stdout
 *              → ByteQueue → TerminalEmulator (ANSI parser) → Grid rendering
 * ```\n * Key features:
 * - Real bash shell via forkpty() JNI (same as Termux)
 * - Full ANSI/VT100 color rendering via TerminalEmulator character grid
 * - Ctrl+C, Ctrl+D, Ctrl+Z, Ctrl+L signal handling
 * - Terminal resize (sends TIOCSWINSZ ioctl)
 * - Command history (Up/Down arrows)
 * - Tab-completion style extra keys (ESC, TAB, /, -)
 * - AI commands also pipe through this terminal (see executeInPty)
 */
@Composable
fun TerminalScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val sessions       by vm.terminalSessions.collectAsState()
    val activeSession by vm.activeTerminalSession.collectAsState()
    val scope          = rememberCoroutineScope()

    // Terminal UI state
    var inputText by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf<String?>(null) }
    var commandHistoryIndex by remember { mutableStateOf(-1) }
    var savedCurrentInput by remember { mutableStateOf("") }

    // ── PTY state ────────────────────────────────────────────────────────
    val ptySession = remember { vm.terminalAgent.getPtySession() }

    // Screen text from the real emulator (grid of characters, updated ~20fps)
    var screenLines by remember { mutableStateOf<List<CharArray>>(emptyList()) }
    var cursorRow by remember { mutableIntStateOf(0) }
    var cursorCol by remember { mutableIntStateOf(0) }
    var gridRows by remember { mutableIntStateOf(TerminalEmulator.DEFAULT_ROWS) }
    var gridCols by remember { mutableIntStateOf(TerminalEmulator.DEFAULT_COLUMNS) }
    var ptyRunning by remember { mutableStateOf(vm.terminalAgent.isPtyMode) }
    var emulator by remember { mutableStateOf<TerminalEmulator?>(null) }

    // ── PTY polling: read emulator screen buffer ~20 fps ─────────────────
    LaunchedEffect(Unit) {
        while (isActive) {
            val session = vm.terminalAgent.getPtySession()
            if (session?.isRunning == true) {
                val emu = session.emulator
                emulator = emu
                // Process pending bytes from the PTY → emulator queue
                session.ptyToEmulatorQueue.let { q ->
                    val buf = ByteArray(4096)
                    val n = q.tryRead(buf, 0, buf.size)
                    if (n > 0) {
                        emu.processBytes(buf, 0, n)
                    }
                }
                // Read current screen state
                screenLines = emu.getScreenChars()
                cursorRow = emu.cursorRow
                cursorCol = emu.cursorCol
                gridRows = emu.rows
                gridCols = emu.columns
                ptyRunning = true
            } else {
                ptyRunning = vm.terminalAgent.isPtyMode
                if (!ptyRunning) {
                    // Clear screen when PTY stops
                    screenLines = emptyList()
                }
            }
            delay(50) // ~20fps
        }
    }

    // ── Resize terminal on size changes ──────────────────────────────────
    // In a real app, this would observe the container size.
    LaunchedEffect(Unit) {
        delay(500) // Wait for layout
        val session = vm.terminalAgent.getPtySession()
        session?.resize(TerminalEmulator.DEFAULT_ROWS, TerminalEmulator.DEFAULT_COLUMNS)
    }

    // Focus requester for input field
    val inputFocusRequester = remember { FocusRequester() }

    // Focus input field on session change
    LaunchedEffect(activeSession) {
        inputFocusRequester.requestFocus()
    }

    // Shizuku status for shell backend display
    val isElevated = remember { vm.isShizukuElevated }

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

    // ── Session Context Menu ─────────────────────────────────────────────
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
            isElevated = isElevated,
            isPtyActive = ptyRunning,
            onClear = {
                val session = vm.terminalAgent.getPtySession()
                session?.let { s ->
                    val emu = s.emulator
                    emu.clearScreen()
                    screenLines = emu.getScreenChars()
                }
            }
        )

        // ── REAL PTY Terminal Output ──────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBg)
                .clickable { inputFocusRequester.requestFocus() }
        ) {
            if (ptyRunning && screenLines.isNotEmpty()) {
                // ── REAL PTY grid rendering ─────────────────────────────
                val scrollState = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    screenLines.forEachIndexed { row, chars ->
                        val lineText = chars.concatToString()
                        val isCursorLine = row == cursorRow
                        val displayText = if (isCursorLine && cursorCol < lineText.length) {
                            // Show cursor position (reverse video)
                            lineText
                        } else {
                            lineText.trimEnd()
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Line number (optional)
                            Text(
                                "%3d ".format(row + 1),
                                color = TerminalWhite.copy(alpha = 0.12f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false
                            )

                            // Cell-by-cell rendering with ANSI attributes
                            val currentEmu = emulator
                            var col = 0
                            while (col < chars.size) {
                                val cell = currentEmu?.getCell(row, col)
                                val ch = chars[col]
                                val fgColor = cell?.foreground?.let { fg -> Color(TerminalEmulator.colorInt(fg)) } ?: TerminalWhite
                                val bgColor = cell?.background?.let { bg -> Color(TerminalEmulator.colorInt(bg)) }
                                val isBold = cell?.bold == true

                                // Find run of same-attribute cells
                                val start = col
                                while (col < chars.size) {
                                    val nextCell = currentEmu?.getCell(row, col)
                                    if (nextCell?.foreground != cell?.foreground || nextCell?.background != cell?.background ||
                                        nextCell?.bold != cell?.bold) break
                                    col++
                                }
                                val segment = chars.concatToString(start, col)
                                Text(
                                    text = segment,
                                    color = fgColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .then(if (bgColor != null) Modifier.background(bgColor) else Modifier)
                                )
                            }
                        }
                    }

                    // Cursor blink (underscore cursor at cursor position)
                    if (cursorRow >= 0 && cursorRow < screenLines.size) {
                        val cursorChar = if (cursorCol < screenLines[cursorRow].size)
                            screenLines[cursorRow][cursorCol].toString()
                        else " "
                        val blinkAlpha = rememberInfiniteTransition(label = "cursor_blink")
                            .animateFloat(0f, 1f,
                                infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "cursor")
                        Text(
                            cursorChar,
                            color = TerminalBg,
                            background = TerminalGreen.copy(alpha = blinkAlpha.value),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }


            } else if (!ptyRunning) {
                // ── PTY unavailable — show real status, not fake terminal ─
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Terminal, null,
                            tint = TerminalWhite.copy(alpha = 0.12f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("REAL PTY Terminal",
                            color = TerminalWhite.copy(alpha = 0.5f), fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))

                        Text("⚡ Real bash via forkpty()",
                            color = TerminalGreen.copy(alpha = 0.6f), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = TerminalYellow.copy(0.12f)
                        ) {
                            Text("⏳ PTY initializing — starting real shell...",
                                color = TerminalYellow.copy(0.7f), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }

                        if (!isElevated) {
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = TerminalYellow.copy(0.12f)) {
                                Text("💡 Shizuku not active — running as app shell",
                                    color = TerminalYellow.copy(0.7f), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── Terminal Extra Keys Bar (Termux-style) ────────────────────────
        TerminalExtraKeysBar(
            onInsert = { inputText += it },
            onControl = { ctrlChar ->
                val session = vm.terminalAgent.getPtySession()
                session?.sendControlChar(ctrlChar.code)
            },
            onToggleHistory = { showHistoryPanel = !showHistoryPanel }
        )

        // ── Terminal Input Bar ────────────────────────────────────────────
        TerminalInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    val cmd = inputText.trim()
                    commandHistoryIndex = -1
                    inputText = ""

                    // Send to REAL PTY session
                    val session = vm.terminalAgent.getPtySession()
                    if (session?.isRunning == true) {
                        // Send command + newline directly to PTY stdin
                        session.writeInput("$cmd\n")
                    } else {
                        // Fallback: use terminal agent execute
                        scope.launch {
                            vm.terminalAgent.execute(activeSession, cmd).let { }
                        }
                    }
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

        // ── Terminal Status Bar ──────────────────────────────────────────
        TerminalStatusBar(
            ptyRunning = ptyRunning,
            sessionName = activeSession,
            isElevated = isElevated,
            gridSize = if (ptyRunning) "$gridRows×$gridCols" else ""
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

// ── Terminal Top Bar ───────────────────────────────────────────────────
@Composable
private fun TerminalTopBar(
    sessions: List<String>,
    activeSession: String,
    onOpenDrawer: () -> Unit,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onSessionLongPress: (String) -> Unit,
    isElevated: Boolean,
    isPtyActive: Boolean,
    onClear: () -> Unit
) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by inf.animateFloat(0.4f, 1.0f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Surface(color = TerminalBg.copy(alpha = 0.95f)) {
        Column(Modifier.fillMaxWidth()) {
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
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${sessions.size} sessions", color = TerminalWhite.copy(0.4f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }

                // PTY status with pulse
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPtyActive) TerminalGreen.copy(0.12f) else TerminalYellow.copy(0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(if (isPtyActive) TerminalGreen else TerminalYellow)
                    )
                    Text(
                        if (isPtyActive) "🔵 PTY" else "⚙️ Init",
                        color = if (isPtyActive) TerminalGreen else TerminalYellow.copy(0.7f),
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(4.dp))

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

            // Session tabs
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
                    Text(emoji, fontSize = 12.sp)
                    Text(
                        name,
                        color = if (isActive) TerminalWhite else TerminalWhite.copy(0.65f),
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isActive) Accent else SurfaceLight.copy(0.3f))
                    )
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
                Icon(Icons.Default.Add, null, tint = TerminalGreen, modifier = Modifier.size(14.dp))
                Text("New", color = TerminalGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Terminal Extra Keys Bar (Termux-style) ─────────────────────────────────
@Composable
private fun TerminalExtraKeysBar(
    onInsert: (String) -> Unit,
    onControl: (Char) -> Unit,
    onToggleHistory: () -> Unit = {}
) {
    val extraKeys = listOf(
        "ESC" to { onInsert("\u001B") },
        "TAB" to { onInsert("\t") },
        "/" to { onInsert("/") },
        "-" to { onInsert("-") },
        "Ctrl+C" to { onControl(3.toChar()) },
        "Ctrl+D" to { onControl(4.toChar()) },
        "Ctrl+Z" to { onControl(26.toChar()) },
        "Ctrl+L" to { onControl(12.toChar()) },
        "📋" to { onToggleHistory() }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        extraKeys.forEach { (label, action) ->
            OutlinedButton(
                onClick = action,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, SurfaceLight.copy(0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceCard.copy(0.5f),
                    contentColor = TerminalWhite.copy(0.8f)
                )
            ) {
                Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
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
    ptyRunning: Boolean,
    sessionName: String,
    isElevated: Boolean,
    gridSize: String = ""
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
                "🔵 PTY ${if (ptyRunning) "● live" else "○ init"} · $sessionName${if (gridSize.isNotBlank()) " · $gridSize" else ""}",
                color = TerminalWhite.copy(alpha = 0.25f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ptyRunning) {
                    Text("← real bash",
                        color = TerminalGreen.copy(alpha = 0.4f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                } else if (!isElevated) {
                    Text("app shell",
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
                            Box(Modifier.width(20.dp).padding(end = 4.dp)) {
                                Text(
                                    if (isSuccess) "✓" else "✗",
                                    color = if (isSuccess) TerminalGreen else TerminalRed,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                entry.command.take(60),
                                color = TerminalWhite,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                                Text(timeStr, color = TerminalWhite.copy(0.3f), fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace)
                                if (entry.durationMs > 0) {
                                    Text("${entry.durationMs}ms", color = TerminalWhite.copy(0.2f), fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (entry.backend != "UNKNOWN") {
                                Spacer(Modifier.width(4.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = Accent.copy(0.1f)) {
                                    Text(entry.backend.take(8), color = Accent.copy(0.6f), fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
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
    var workdir by remember { mutableStateOf("/") }

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
                    placeholder = { Text("e.g. /sdcard/project", color = TerminalWhite.copy(0.3f)) },
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
            OutlinedButton(onClick = onDismiss) { Text("Cancel", color = TerminalWhite) }
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
                MenuItem(Icons.Default.Edit, "Rename", "Change session name", Accent) { showRenameDialog = true }
                MenuItem(Icons.Default.DeleteSweep, "Clear Output", "Remove all terminal output", TerminalYellow) {
                    onClearHistory(); onDismiss()
                }
                if (!isDefault) {
                    MenuItem(Icons.Default.Delete, "Delete Session", "Permanently remove this session", TerminalRed) {
                        onDelete(); onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } }
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
                        if (renameText.isNotBlank() && renameText != sessionName) onRename(renameText.trim())
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Rename", color = TerminalBg) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = TerminalWhite) } }
        )
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, description: String, iconColor: Color, onClick: () -> Unit) {
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
