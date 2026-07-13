package com.interndra.ui.screens

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ai.JailbreakLevel
import com.interndra.data.model.*
import com.interndra.ui.components.*
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.ui.viewmodel.HybridUiState
import com.interndra.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── ENHANCED: streaming animation key used for typewriter effect ────────────
private var streamIdCounter = 0L
private fun nextStreamId() = ++streamIdCounter

@Composable
fun HybridChatScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {}
) {
    val messages by vm.messages.collectAsState()
    val uiState  by vm.uiState.collectAsState()
    val mode     by vm.privacyMode.collectAsState()
    val provider by vm.aiProvider.collectAsState()
    val jailbreakEnabled by vm.jailbreakEnabled.collectAsState()
    val jailbreakLevel by vm.jailbreakLevel.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val keyboard   = LocalSoftwareKeyboardController.current
    val context    = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic      = LocalHapticFeedback.current

    // ── Theme-aware colors ────────────────────────────────────────────
    val colors = LocalInterndraColors.current

    // ── Task system ───────────────────────────────────────────────────
    val activeTask by vm.taskManager.activeTask.collectAsState()

    // ── Group messages by consecutive role (derived for recomposition efficiency) ──
    val groupedMessages by remember {
        derivedStateOf {
            if (messages.isEmpty()) emptyList()
            else {
                val groups = mutableListOf<Pair<MessageRole, List<ChatMessage>>>()
                var currentRole = messages[0].role
                var currentGroup = mutableListOf<ChatMessage>()
                for (msg in messages) {
                    if (msg.role == currentRole && !msg.isLoading) {
                        currentGroup.add(msg)
                    } else {
                        if (currentGroup.isNotEmpty()) groups.add(currentRole to currentGroup)
                        currentRole = msg.role
                        currentGroup = mutableListOf(msg)
                    }
                }
                if (currentGroup.isNotEmpty()) groups.add(currentRole to currentGroup)
                groups
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {

        // ── Emergency lock banner ─────────────────────────────────────────
        AnimatedVisibility(visible = uiState.emergencyLockActive) {
            Surface(color = TerminalRed.copy(0.15f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = TerminalRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Emergency Privacy Lock Active — Local Only",
                        color = TerminalRed, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deactivateEmergencyLock() }) {
                        Text("Unlock", color = TerminalRed, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── Error banner ──────────────────────────────────────────────────
        AnimatedVisibility(visible = uiState.error != null) {
            uiState.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { vm.dismissError() }) { Text("✕", color = Color.White) }
                    }
                }
            }
        }

        // ── Cloud consent dialog ──────────────────────────────────────────
        uiState.pendingCloudConsent?.let { req ->
            CloudConsentBanner(req.reason, req.destinationDomain,
                onAllow = { vm.allowCloudConsent() }, onDeny = { vm.denyCloudConsent() })
        }

        // ── Safety confirmation dialog ─────────────────────────────────────
        uiState.pendingConfirmation?.let { req ->
            ConfirmationBanner(req.message, req.commandSummary,
                onAccept = { vm.confirmAction() }, onDeny = { vm.denyAction() })
        }

    // ── Messages (isolated recomposition scope for streaming) ─────────
        MessageList(
            modifier = Modifier.weight(1f),
            messages = messages,
            groupedMessages = groupedMessages,
            activeTask = activeTask,
            colors = colors,
            onSuggestionClick = { text -> inputText = text },
            onCopy = { text ->
                clipboardManager.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onDelete = { msg -> vm.deleteMessage(msg) },
            onRegenerate = { vm.sendCommand("regenerate last response") },
            onTaskPause = { vm.taskManager.pause() },
            onTaskResume = { vm.taskManager.resume() },
            onTaskRetry = { vm.taskManager.retryAll() },
            onTaskCancel = { vm.taskManager.cancel() },
            onTaskRetryStep = { idx -> vm.taskManager.retryStep(idx) }
        )

        // ── Simple Input Bar ──────────────────────────────────────────────
        SimpleInputBar(
            text         = inputText,
            isLoading    = uiState.isLoading,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    // Haptic feedback on send
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    keyboard?.hide()
                    vm.sendCommand(inputText)
                    inputText = ""
                }
            }
        )
    }
}

// ── Simple Top Bar ────────────────────────────────────────────────────────
@Composable
private fun SimpleTopBar(
    onOpenDrawer: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ── Message List (isolated recomposition scope for streaming) ──────────
@Composable
private fun MessageList(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    groupedMessages: List<Pair<MessageRole, List<ChatMessage>>>,
    activeTask: TaskPlan?,
    colors: InterndraColors,
    onSuggestionClick: (String) -> Unit = {},
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
    onTaskPause: () -> Unit = {},
    onTaskResume: () -> Unit = {},
    onTaskRetry: () -> Unit = {},
    onTaskCancel: () -> Unit = {},
    onTaskRetryStep: (Int) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // ── Streaming state (local to this scope = no recomposition of parent) ─
    val initialMessageCount = remember { messages.size }
    var streamingMsgId by remember { mutableStateOf<Long?>(null) }
    var streamedText by remember { mutableStateOf("") }
    var userScrolledUp by remember { mutableStateOf(false) }

    fun isNearBottom(): Boolean {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        return lastVisible >= totalItems - 3
    }

    // Track scroll gestures to detect when user scrolls up (stop auto-scroll)
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !isNearBottom()) {
            userScrolledUp = true
            keyboard?.hide()
        }
    }

    // Auto-scroll on new messages (only if user hasn't scrolled up)
    LaunchedEffect(messages.size, streamedText) {
        if (messages.isNotEmpty() && !userScrolledUp) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Reset userScrolledUp when a new user message arrives
    val lastMsg = messages.lastOrNull()
    LaunchedEffect(lastMsg?.id) {
        if (lastMsg?.role == MessageRole.USER) {
            userScrolledUp = false
        }
    }

    // ── Streaming effect: smooth character reveal with variable speed ──
    val animatedMessageIds = remember { mutableSetOf<Long>() }
    LaunchedEffect(messages.size) {
        val msg = messages.lastOrNull()
        if (msg != null && messages.size > initialMessageCount &&
            msg.role == MessageRole.AI && !msg.isLoading &&
            msg.content.length > 30 && msg.id !in animatedMessageIds) {
            animatedMessageIds.add(msg.id)
            streamingMsgId = msg.id
            streamedText = ""
            val text = msg.content
            var revealed = 0
            val len = text.length

            // Calculate per-character delays
            val charDelays = IntArray(len) { idx ->
                val c = text[idx]
                when {
                    c.isWhitespace() -> 8
                    c in listOf('.', ',', '!', '?', ';', ':') -> 12
                    c == '\n' -> 20
                    idx > 0 && text[idx-1] == '`' -> 4
                    c.isLetterOrDigit() -> 6
                    c == ' ' && idx > 0 && idx < len-1 &&
                        text[idx-1] in listOf('.','!','?') -> 40
                    else -> 4
                }
            }

            val frameDuration = 14L

            while (revealed < len && streamingMsgId == msg.id) {
                var accumulated = 0
                var charsThisFrame = 0
                while (revealed < len && accumulated < frameDuration && streamingMsgId == msg.id && charsThisFrame < 8) {
                    accumulated += charDelays[revealed]
                    revealed++
                    charsThisFrame++
                }
                streamedText = text.substring(0, revealed)
                delay(frameDuration)
            }
            streamedText = text
            streamingMsgId = null
            userScrolledUp = false
        }
    }

    // ── Render ───────────────────────────────────────────────────────────
        Box(modifier = modifier.fillMaxHeight()) {
        LazyColumn(
            state               = listState,
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            beyondViewportPageSize = 3
        ) {
            if (messages.isEmpty()) {
                item { PremiumWelcomeScreen(onTextChange = onSuggestionClick) }
            } else {
                itemsIndexed(groupedMessages, key = { _, group ->
                    "group_${group.second.firstOrNull()?.id ?: group.hashCode()}"
                }) { groupIdx, (role, msgs) ->
                    MessageGroup(
                        role = role,
                        messages = msgs,
                        streamingMsgId = streamingMsgId,
                        streamedText = streamedText,
                        groupIndex = groupIdx,
                        onCopy = onCopy,
                        onDelete = onDelete,
                        onRegenerate = onRegenerate
                    )
                }

                activeTask?.let { task ->
                    item(key = "task_${task.id}") {
                        Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                            TaskCard(
                                task = task,
                                onPause = onTaskPause,
                                onResume = onTaskResume,
                                onRetry = onTaskRetry,
                                onCancel = onTaskCancel,
                                onRetryStep = onTaskRetryStep
                            )
                        }
                    }
                }
            }
        }

        // ── Scroll-to-bottom FAB ─────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible = userScrolledUp && messages.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
            enter = scaleIn(animationSpec = spring(dampingRatio = 0.6f)) + fadeIn(),
            exit = scaleOut(animationSpec = tween(150)) + fadeOut(tween(150))
        ) {
            Surface(
                shape = CircleShape,
                color = colors.accent.copy(alpha = 0.9f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        userScrolledUp = false
                        keyboard?.hide()
                        scope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        "Scroll to bottom",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ── Message Group ───────────────────────────────────────────────────────────
@Composable
private fun MessageGroup(
    role: MessageRole,
    messages: List<ChatMessage>,
    streamingMsgId: Long?,
    streamedText: String,
    groupIndex: Int = 0,
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit
) {
    val colors = LocalInterndraColors.current
    val isUser = role == MessageRole.USER
    val context = LocalContext.current

    // Show timestamp for groups (first message time)
    val groupTime = remember(messages) {
        val first = messages.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val sdf = if (now - first < 86400000L) SimpleDateFormat("h:mm a", Locale.getDefault())
                  else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        sdf.format(Date(first))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isUser) 8.dp else 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Timestamp header for group
        Text(
            text = groupTime,
            color = TerminalWhite.copy(alpha = 0.3f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ENHANCED: Connected message bubbles with entry animation
        // Each message slides up with a staggered delay for a premium feel
        messages.forEachIndexed { idx, msg ->
            val isLast = idx == messages.size - 1
            val borderRadius = if (isUser) {
                if (isLast) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                else RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)
            } else {
                if (isLast) RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
                else RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)
            }

            val showAvatar = !isUser && isLast
            val isStreaming = msg.id == streamingMsgId && streamedText.isNotEmpty()

            // Animated message entry (skip animation for streaming messages to avoid flicker)
            AnimatedMessage(
                index = idx + groupIndex * 2,
                visible = true
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (!isUser && !isLast) 48.dp else 0.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    // AI avatar only on last message of group
                    if (showAvatar) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, colors.aiBubbleBorder),
                            modifier = Modifier
                                .padding(top = 8.dp, end = 10.dp)
                                .size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, "AI",
                                    tint = colors.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Message content
                    Column {
                        Box(
                            modifier = Modifier
                                .widthIn(max = if (isUser) 300.dp else 400.dp)
                                .clip(borderRadius)
                                .background(
                                    if (isUser) colors.userBubbleBg else colors.aiBubbleBg,
                                    shape = borderRadius
                                )
                                .border(
                                    if (!isUser) 0.5.dp else 0.dp,
                                    if (!isUser) colors.aiBubbleBorder else Color.Transparent,
                                    shape = borderRadius
                                )
                                .padding(
                                    horizontal = if (isUser) 16.dp else 14.dp,
                                    vertical = if (isUser) 12.dp else 6.dp
                                )
                        ) {
                            if (msg.isLoading) {
                                ThinkingIndicator()
                            } else                        if (isUser) {
                                Text(msg.content, color = colors.userBubbleText, fontSize = 15.sp, lineHeight = 22.sp)
                            } else {
                                // Streaming or full text
                                val displayText = if (isStreaming) streamedText else msg.content
                                RichMarkdownText(
                                    markdown = displayText,
                                    modifier = Modifier.fillMaxWidth(),
                                    onLinkClick = { url ->
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                    }
                                )
                            }
                        }

                        // Message actions bar (only for AI messages, last in group)
                        if (isLast && !msg.isLoading) {
                            MessageActionsBar(
                                onCopy = { onCopy(msg.content) },
                                onRegenerate = if (msg.role == MessageRole.AI) onRegenerate else null,
                                isUserMessage = isUser
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Cloud Consent Banner ───────────────────────────────────────────────────
@Composable
private fun CloudConsentBanner(
    reason: String, domain: String,
    onAllow: () -> Unit, onDeny: () -> Unit
) {
    Surface(color = Color(0xFF1A2040), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, null, tint = TerminalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cloud AI Request", color = TerminalBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(reason, color = TerminalWhite, fontSize = 13.sp)
            Text("Destination: $domain", color = TerminalWhite.copy(0.5f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalBlue),
                    modifier = Modifier.weight(1f)) {
                    Text("Allow Once", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onDeny,
                    border = BorderStroke(1.dp, TerminalWhite.copy(0.3f)),
                    modifier = Modifier.weight(1f)) {
                    Text("Stay Local", color = TerminalWhite)
                }
            }
        }
    }
}

// ── Confirmation Banner ────────────────────────────────────────────────────
@Composable
private fun ConfirmationBanner(
    message: String, summary: String,
    onAccept: () -> Unit, onDeny: () -> Unit
) {
    Surface(color = Color(0xFF3D2000), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = TerminalYellow, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Action Required", color = TerminalYellow, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(message, color = TerminalWhite, fontSize = 14.sp)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(summary, color = TerminalWhite.copy(0.7f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                    modifier = Modifier.weight(1f)) {
                    Text("Confirm", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onDeny,
                    border = BorderStroke(1.dp, TerminalRed),
                    modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = TerminalRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Simplified Input Bar ────────────────────────────────────────────
@Composable
private fun SimpleInputBar(
    text: String,
    isLoading: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val colors = LocalInterndraColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.inputBarBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(24.dp),
            color     = colors.inputFieldBg,
            border    = BorderStroke(1.dp, colors.inputFieldBorder)
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text field
                TextField(
                    value         = text,
                    onValueChange = onTextChange,
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        Text(
                            "Ask anything...",
                            fontSize = 15.sp, color = colors.inputPlaceholder
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = colors.inputTextColor,
                        unfocusedTextColor      = colors.inputTextColor,
                        cursorColor             = colors.accent
                    ),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) onSend()
                    })
                )

                // Send button
                IconButton(
                    onClick = onSend,
                    enabled  = text.isNotBlank() && !isLoading,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            "Send",
                            tint = if (text.isNotBlank()) Accent else TerminalWhite.copy(0.3f)
                        )
                    }
                }
            }
        }
    }
}

// ── Premium Welcome Screen ───────────────────────────────────────────────
@Composable
private fun PremiumWelcomeScreen(onTextChange: (String) -> Unit) {
    val suggestions = listOf(
        "📊 Check battery & storage",
        "🔍 Search the web",
        "📂 List files in Downloads",
        "🚀 Open WhatsApp",
        "📶 Show Wi-Fi info",
        "💵 USD to INR rate"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Gradient icon background
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Accent, VaultPurple),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(80f, 80f)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "INTERNDRA",
            color = TerminalWhite,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Private AI OS for Android",
            color = TerminalWhite.copy(alpha = 0.4f),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(8.dp))

        // Feature badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeatureBadge("🤖", "Local AI")
            FeatureBadge("🔒", "Privacy")
            FeatureBadge("⚡", "Shizuku")
            FeatureBadge("🌐", "Web")
        }

        Spacer(Modifier.height(36.dp))

        // Quick action cards
        suggestions.chunked(2).forEachIndexed { rowIdx, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEachIndexed { colIdx, suggestion ->
                    val emoji = suggestion.substringBefore(" ")
                    val text = suggestion.substringAfter(" ").trim()
                    val colors = quickActionColors(rowIdx * 2 + colIdx)

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onTextChange(text) },
                        colors = CardDefaults.cardColors(
                            containerColor = colors.first.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.first.copy(alpha = 0.15f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text,
                                color = TerminalWhite.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun quickActionColors(index: Int): Pair<Color, Color> = when (index) {
    0 -> TerminalGreen to GradientTermEnd
    1 -> Accent to VaultPurple
    2 -> TerminalYellow to GradientVaultEnd
    3 -> VaultPurple to VaultCyan
    4 -> VaultCyan to Accent
    5 -> TerminalBlue to GradientTermStart
    else -> Accent to VaultPurple
}

@Composable
private fun FeatureBadge(emoji: String, label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceCard
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(emoji, fontSize = 11.sp)
            Text(
                label,
                color = TerminalWhite.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Helper: extract filename from content URI ──────────────────────────────
private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "file"
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
    } else {
        name = uri.lastPathSegment ?: "file"
    }
    return name
}
