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
import com.interndra.ai.provider.ProviderConfig
import com.interndra.ai.provider.ProviderRole
import com.interndra.ai.provider.ProviderState
import com.interndra.ai.tasks.TaskPlan
import com.interndra.data.model.*
import com.interndra.search.SourceMarker
import com.interndra.ui.components.*
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.ActiveCommand
import com.interndra.ui.viewmodel.CommandStatus
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
    onOpenModelSettings: () -> Unit = {}
) {
    // Note: The top bar (AppTopBar) is rendered by AppShell.kt, so we
    // don't include a ChatHeaderBar here — that would create a duplicate heading.
    val messages by vm.messages.collectAsState()
    val uiState  by vm.uiState.collectAsState()
    val mode     by vm.privacyMode.collectAsState()
    val provider by vm.aiProvider.collectAsState()
    val jailbreakEnabled by vm.jailbreakEnabled.collectAsState()
    val jailbreakLevel by vm.jailbreakLevel.collectAsState()
    val activeCommands by vm.activeCommands.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val providerState by vm.providerState.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val keyboard   = LocalSoftwareKeyboardController.current
    val context    = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic      = LocalHapticFeedback.current

    // ── Voice input (speech-to-text) ──────────────────────────────────
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) inputText = spoken
        }
    }
    val launchVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to INTENDRA")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Theme-aware colors ────────────────────────────────────────────
    val colors = LocalInterndraColors.current

    // ── Task system ───────────────────────────────────────────────────
    val activeTask by vm.taskManager.activeTask.collectAsState()

    // ── Active command tracking ────────────────────────────────────
    val hasRunningCommands = remember(activeCommands) {
        activeCommands.any { it.status == com.interndra.ui.viewmodel.CommandStatus.RUNNING }
    }

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
        // ── Active commands indicator (like Claude: "Running command...") ──
        if (activeCommands.isNotEmpty()) {
            CommandExecutionDisplay(
                commands = activeCommands,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        MessageList(
            modifier = Modifier.weight(1f),
            messages = messages,
            groupedMessages = groupedMessages,
            activeTask = activeTask,
            activeCommands = activeCommands,
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

        // ── Premium Agent Composer (Claude-style) ───────────────────────
        AgentComposer(
            text = inputText,
            onTextChange = { inputText = it },
            isLoading = uiState.isLoading,
            providerName = composerProviderName(providerState),
            modelName = selectedModel,
            defaultProviderId = providerState.defaults.chat,
            providers = providerState.providers,
            onSend = {
                if (inputText.isNotBlank()) {
                    // Haptic feedback on send
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    keyboard?.hide()
                    vm.sendCommand(inputText)
                    inputText = ""
                }
            },
            onStop = { vm.stopGeneration() },
            onVoice = launchVoice,
            onSelectProvider = { providerId -> vm.setProviderDefault(ProviderRole.CHAT, providerId) },
            onSelectProviderModel = { providerId, modelId -> vm.setActiveProviderModel(providerId, modelId) },
            onOpenModelSettings = onOpenModelSettings
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
    activeCommands: List<com.interndra.ui.viewmodel.ActiveCommand>,
    colors: InterndraColors,
    onSuggestionClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
    onTaskPause: () -> Unit,
    onTaskResume: () -> Unit,
    onTaskRetry: () -> Unit,
    onTaskCancel: () -> Unit,
    onTaskRetryStep: (Int) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
// User messages → right-aligned bubbles (like ChatGPT)
// AI messages → full-width, no bubble, direct rendering (like Claude)
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
            .padding(horizontal = if (isUser) 8.dp else 0.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Timestamp header for group
        Text(
            text = groupTime,
            color = TerminalWhite.copy(alpha = 0.3f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        messages.forEachIndexed { idx, msg ->
            val isLast = idx == messages.size - 1
            val isStreaming = msg.id == streamingMsgId && streamedText.isNotEmpty()

            AnimatedMessage(index = idx + groupIndex * 2, visible = true) {
                if (isUser) {
                    // ── USER: Bubble style (right-aligned) ────────────────
                    val borderRadius = if (isLast)
                        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                    else
                        RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(borderRadius)
                                .background(colors.userBubbleBg, shape = borderRadius)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                msg.content,
                                color = colors.userBubbleText,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                } else {
                    // ── AI: Full-width, no bubble (like Claude) ───────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        if (msg.isLoading) {
                            ThinkingIndicator()
                        } else {
                            // Web-search sources live in a hidden marker; the visible
                            // text stays clean (no links dumped in the chat). They are
                            // rendered below as a collapsible Sources box.
                            val sources = remember(msg.id, msg.content) {
                                SourceMarker.decode(msg.content)
                            }
                            val displayText = if (isStreaming) streamedText else msg.content
                            RichMarkdownText(
                                markdown = SourceMarker.strip(displayText),
                                modifier = Modifier.fillMaxWidth(),
                                onLinkClick = { url ->
                                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                }
                            )
                            if (sources.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                SourcesBox(
                                    sources = sources,
                                    colors = colors,
                                    messageId = msg.id
                                )
                            }
                        }

                        // Message actions bar
                        if (isLast && !msg.isLoading) {
                            MessageActionsBar(
                                onCopy = { onCopy(SourceMarker.strip(msg.content)) },
                                onRegenerate = onRegenerate,
                                isUserMessage = false
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Collapsible Sources Box (web search) ──────────────────────────────────
// Shown below an AI message that used web search. The chat text stays clean;
// tapping the header reveals the links (like Claude's source pills).
@Composable
private fun SourcesBox(
    sources: List<SourceMarker.SourceLink>,
    colors: InterndraColors,
    messageId: Long
) {
    // Key the expansion state by message id so deletion/reordering of older
    // messages never leaks an expanded flag onto a different message.
    var expanded by remember(messageId) { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.inputFieldBg.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, colors.inputFieldBorder.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header row — tap to expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Sources",
                    color = colors.inputTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${sources.size}",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse sources" else "Show sources",
                    tint = TerminalWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded link list
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    sources.forEachIndexed { i, s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(s.url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${i + 1}.",
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title.ifBlank { s.url },
                                    color = colors.inputTextColor,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    SourceMarker.hostname(s.url),
                                    color = TerminalWhite.copy(alpha = 0.4f),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = TerminalWhite.copy(alpha = 0.4f),
                                modifier = Modifier.size(14.dp)
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

// ── Premium Agent Composer (Claude-style) ─────────────────────────────
@Composable
private fun AgentComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isLoading: Boolean,
    providerName: String,
    modelName: String,
    defaultProviderId: String?,
    providers: List<ProviderConfig>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectProviderModel: (String, String) -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val colors = LocalInterndraColors.current
    var showModelSheet by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.inputBarBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ── Model selector chip row ──────────────────────────────────
        Row(
            Modifier.padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.25f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showModelSheet = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tune, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (modelName.isNotBlank() && modelName != "openrouter/auto")
                            modelName.substringAfterLast('/') else providerName,
                        color = colors.inputTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "INTENDRA AI",
                color = TerminalWhite.copy(alpha = 0.25f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // ── Input field ─────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = colors.inputFieldBg,
            border = BorderStroke(1.dp, colors.inputFieldBorder)
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Ask INTENDRA anything…", fontSize = 15.sp, color = colors.inputPlaceholder)
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
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() })
                )

                if (isLoading) {
                    // ── Stop button while generating ─────────────────
                    Surface(
                        shape = CircleShape,
                        color = TerminalRed.copy(alpha = 0.15f),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onStop)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Stop,
                                "Stop generation",
                                tint = TerminalRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else if (text.isBlank()) {
                    // ── Voice input ──────────────────────────────────
                    Surface(
                        shape = CircleShape,
                        color = colors.accent.copy(alpha = 0.12f),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onVoice)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Mic,
                                "Voice input",
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // ── Send button (gradient) ───────────────────────
                    Surface(
                        shape = CircleShape,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onSend)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(listOf(Accent, VaultPurple))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                "Send",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Model / provider picker ─────────────────────────────────────
    if (showModelSheet) {
        ModelPickerSheet(
            providers = providers,
            defaultProviderId = defaultProviderId,
            onDismiss = { showModelSheet = false },
            onSelectProvider = onSelectProvider,
            onSelectProviderModel = onSelectProviderModel,
            onOpenModelSettings = {
                showModelSheet = false
                onOpenModelSettings()
            }
        )
    }
}

// ── Model & Provider Picker Sheet ─────────────────────────────────────
@Composable
private fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    defaultProviderId: String?,
    onDismiss: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectProviderModel: (String, String) -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val colors = LocalInterndraColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultProvider = providers.firstOrNull { it.id == defaultProviderId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.inputBarBg
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Model & Provider", color = colors.inputTextColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Switch AI providers and models", color = TerminalWhite.copy(alpha = 0.4f), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            // ── Providers ──────────────────────────────────────────
            Text(
                "PROVIDERS",
                color = TerminalWhite.copy(alpha = 0.35f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            providers.filter { it.isReadyForChat }.forEach { p ->
                val selected = p.id == defaultProviderId
                Surface(
                    color = if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectProvider(p.id) }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (p.isLocal) "📱" else "☁️", fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            p.name,
                            color = colors.inputTextColor,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(Icons.Default.CheckCircle, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── Models of the default provider ─────────────────────
            if (defaultProvider != null && defaultProvider.models.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "MODELS — ${defaultProvider.name.uppercase(Locale.ROOT)}",
                    color = TerminalWhite.copy(alpha = 0.35f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                defaultProvider.models.forEach { m ->
                    val active = m.id == defaultProvider.activeModelId
                    Surface(
                        color = if (active) colors.accent.copy(alpha = 0.1f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectProviderModel(defaultProvider.id, m.id) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                m.displayName.ifBlank { m.id },
                                color = colors.inputTextColor,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (active) {
                                Icon(Icons.Default.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "No models configured yet — add an API key and fetch models in provider settings.",
                    color = TerminalWhite.copy(alpha = 0.45f),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenModelSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Manage providers & API keys", color = colors.accent, fontSize = 13.sp)
            }
        }
    }
}

// ── Current provider name for the composer chip ────────────────────────
private fun composerProviderName(state: ProviderState): String =
    state.providers.firstOrNull { it.id == state.defaults.chat && it.isReadyForChat }?.name
        ?: state.providers.firstOrNull()?.name
        ?: "No provider"

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
            "INTENDRA",
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

// ── Command Execution Display (like Claude's command indicator) ────────────
@Composable
private fun CommandExecutionDisplay(
    commands: List<ActiveCommand>,
    modifier: Modifier = Modifier
) {
    val runningCount = commands.count { it.status == CommandStatus.RUNNING }
    val colors = LocalInterndraColors.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.codeBlockBg.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, colors.codeBlockBorder.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp)) {
            // ── Header: Status indicator ─────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (runningCount > 0) {
                    val infiniteTransition = rememberInfiniteTransition(label = "cmd_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        0.3f, 1.0f,
                        infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "cmd_pulse"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(TerminalGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Running ${runningCount} command${if (runningCount > 1) "s" else ""}…",
                        color = TerminalGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    val allSuccess = commands.all { it.status == CommandStatus.SUCCESS }
                    val allFailed = commands.all { it.status == CommandStatus.FAILED }
                    val icon = if (allSuccess) "✅" else if (allFailed) "❌" else "⚠️"
                    val label = if (allSuccess) "All commands completed"
                                else if (allFailed) "Commands failed"
                                else "${commands.count { it.status == CommandStatus.SUCCESS }}/${commands.size} completed"
                    Text("$icon $label", color = TerminalWhite.copy(0.7f), fontSize = 13.sp)
                }

                Spacer(Modifier.weight(1f))

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = TerminalWhite.copy(0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Expanded command list ────────────────────────────────────
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    commands.forEachIndexed { idx, cmd ->
                        CommandExecutionRow(cmd)
                        if (idx < commands.size - 1) {
                            HorizontalDivider(
                                color = colors.codeBlockBorder.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandExecutionRow(command: ActiveCommand) {
    val statusColor = when (command.status) {
        CommandStatus.RUNNING -> TerminalGreen
        CommandStatus.SUCCESS -> TerminalGreen.copy(alpha = 0.7f)
        CommandStatus.FAILED -> TerminalRed
    }
    val statusIcon = when (command.status) {
        CommandStatus.RUNNING -> "⚡"
        CommandStatus.SUCCESS -> "✅"
        CommandStatus.FAILED -> "❌"
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                statusIcon,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    command.description,
                    color = TerminalWhite.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    command.command,
                    color = TerminalWhite.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(4.dp))

            // Show output/error if available
            if (command.output.isNotBlank() || command.error.isNotBlank()) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }

        // Show output snippet when failed
        if (command.status == CommandStatus.FAILED && command.error.isNotBlank()) {
            Text(
                command.error.take(120),
                color = TerminalRed.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
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
