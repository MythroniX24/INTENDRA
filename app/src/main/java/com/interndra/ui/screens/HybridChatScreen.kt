package com.interndra.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.interndra.ui.components.RichMarkdownText
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
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
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    val keyboard   = LocalSoftwareKeyboardController.current
    val context    = LocalContext.current

    // ── Streaming state: which message is currently streaming and its revealed text ──
    var streamingMsgId by remember { mutableStateOf<Long?>(null) }
    var streamedText by remember { mutableStateOf("") }
    var streamCharsPerFrame by remember { mutableStateOf(3) }

    // Auto-scroll on new messages
    LaunchedEffect(messages.size, streamedText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── Streaming effect: when a new AI message arrives, animate it in ─────
    LaunchedEffect(messages) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == MessageRole.AI && !lastMsg.isLoading && lastMsg.content.length > 30) {
            val sid = nextStreamId()
            streamingMsgId = lastMsg.id
            streamedText = ""
            val text = lastMsg.content
            val charCount = text.length
            val speed = if (charCount > 500) 8 else if (charCount > 200) 5 else 3
            streamCharsPerFrame = speed
            var revealed = 0
            while (revealed < charCount && streamingMsgId == lastMsg.id) {
                revealed = (revealed + speed).coerceAtMost(charCount)
                streamedText = text.substring(0, revealed)
                delay(16) // ~60fps
            }
            streamedText = text
            streamingMsgId = null
        }
    }

    // ── Group messages by consecutive role ──────────────────────────────────
    val groupedMessages = remember(messages) {
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

    Column(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {

        // ── Top bar ───────────────────────────────────────────────────────
        AnimatedTopBar(onOpenDrawer, onNavigateToTerminal, uiState.activeWorkspaceName)

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

        // ── Status row ────────────────────────────────────────────────────
        StatusRow(mode, provider, uiState, jailbreakEnabled, jailbreakLevel)

        // ── Messages ──────────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier.weight(1f),
            contentPadding      = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item { EnhancedWelcomeScreen(vm) { text -> inputText = text } }
            } else {
                // Render grouped messages
                itemsIndexed(groupedMessages, key = { idx, _ -> "group_$idx" }) { _, (role, msgs) ->
                    MessageGroup(
                        role = role,
                        messages = msgs,
                        streamingMsgId = streamingMsgId,
                        streamedText = streamedText,
                        onCopy = { text ->
                            val clip = LocalClipboardManager.current
                            clip.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { msg -> /* would need VM method */ },
                        onRegenerate = { /* future */ }
                    )
                }
            }
        }

        // ── Enhanced Input Bar ────────────────────────────────────────────
        EnhancedHybridInputBar(
            text         = inputText,
            isLoading    = uiState.isLoading,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    keyboard?.hide()
                    vm.sendCommand(inputText)
                    inputText = ""
                    scope.launch {
                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                    }
                }
            },
            attachedFiles = emptyList(), // file handling via launcher
            onAttachFile = { /* file picker */ }
        )
    }
}

// ── Animated Top Bar ────────────────────────────────────────────────────────
@Composable
private fun AnimatedTopBar(
    onOpenDrawer: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    workspaceName: String
) {
    var showTitle by remember { mutableStateOf(true) }
    Surface(color = Color(0xFF0F0F0F), tonalElevation = 0.dp) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.Menu, "Menu", tint = Color.White)
            }

            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("INTERNDRA", color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif, letterSpacing = (-0.5).sp)

                if (workspaceName != "General") {
                    Text(workspaceName, color = Accent.copy(alpha = 0.8f),
                        fontSize = 11.sp, letterSpacing = 0.5.sp)
                }
            }

            Row(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = onNavigateToTerminal) {
                    Icon(Icons.Default.Terminal, "Terminal", tint = Color.White)
                }
            }
        }
    }
}

// ── Status Row ──────────────────────────────────────────────────────────────
@Composable
private fun StatusRow(
    mode: PrivacyMode,
    provider: Constants.AiProvider,
    uiState: HybridUiState,
    jailbreakEnabled: Boolean,
    jailbreakLevel: JailbreakLevel
) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PrivacyChip(mode)

        // Provider badge
        val provColor = when (provider) {
            Constants.AiProvider.OPENROUTER -> TerminalBlue
            Constants.AiProvider.GEMINI -> TerminalGreen
        }
        Surface(shape = RoundedCornerShape(50), color = provColor.copy(alpha = 0.15f)) {
            Text("${provider.emoji} ${provider.label.split(" ").first()}",
                color = provColor, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        }

        if (uiState.localModelReady)
            StatusChip("Local AI ✓", true, TerminalGreen)
        else
            StatusChip("Local AI ✗", false, TerminalRed)

        // Jailbreak status
        if (jailbreakEnabled) {
            val jbColor = when (jailbreakLevel) {
                JailbreakLevel.LIGHT -> TerminalGreen
                JailbreakLevel.MEDIUM -> TerminalYellow
                JailbreakLevel.EXTREME -> TerminalRed
                else -> Accent
            }
            Surface(shape = RoundedCornerShape(50), color = jbColor.copy(alpha = 0.15f)) {
                Text("🧠 ${jailbreakLevel.label.first()}", color = jbColor, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
        } else {
            Surface(shape = RoundedCornerShape(50), color = SurfaceLight.copy(alpha = 0.1f)) {
                Text("🧠 Off", color = TerminalWhite.copy(0.4f), fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }

        uiState.lastAiSource?.let { src ->
            StatusChip(
                when (src) {
                    AiSource.LOCAL    -> "🔒 On-device"
                    AiSource.CLOUD    -> "☁️ Cloud"
                    AiSource.FALLBACK -> "⚡ Fallback"
                },
                active = true,
                activeColor = when (src) {
                    AiSource.LOCAL    -> TerminalGreen
                    AiSource.CLOUD    -> TerminalBlue
                    AiSource.FALLBACK -> TerminalYellow
                }
            )
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
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit
) {
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
            .padding(horizontal = if (isUser) 8.dp else 4.dp)
            .animateContentSize(tween(300)),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Timestamp header for group
        Text(
            text = groupTime,
            color = TerminalWhite.copy(alpha = 0.3f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ENHANCED: Connected message bubbles
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
                        color = ChatBg,
                        border = BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier
                            .padding(top = 8.dp, end = 10.dp)
                            .size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, "AI",
                                tint = Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Message content
                Column {
                    Box(
                        modifier = Modifier
                            .widthIn(max = if (isUser) 300.dp else 360.dp)
                            .clip(borderRadius)
                            .background(
                                if (isUser) UserBubble else Color(0xFF1A1B1E)
                            )
                            .padding(
                                horizontal = if (isUser) 16.dp else 12.dp,
                                vertical = if (isUser) 12.dp else 10.dp
                            )
                    ) {
                        if (msg.isLoading) {
                            EnhancedLoadingDots()
                        } else if (isUser) {
                            Text(msg.content, color = TerminalWhite, fontSize = 15.sp, lineHeight = 22.sp)
                        } else {
                            // Streaming or full text
                            val displayText = if (msg.id == streamingMsgId && streamedText.isNotEmpty())
                                streamedText else msg.content
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

                    // Message actions (only for last message in group)
                    if (isLast && !msg.isLoading) {
                        MessageActions(
                            role = msg.role,
                            content = msg.content,
                            onCopy = onCopy,
                            onDelete = { onDelete(msg) },
                            onRegenerate = onRegenerate
                        )
                    }
                }
            }
        }
    }
}

// ── Message Actions ─────────────────────────────────────────────────────────
@Composable
private fun MessageActions(
    role: MessageRole,
    content: String,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit
) {
    var liked by remember { mutableStateOf<Boolean?>(null) }

    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (role == MessageRole.AI) {
            // Copy button
            ActionIcon(Icons.Default.ContentCopy, "Copy", { onCopy(content) })
            // Thumbs up
            ActionIcon(
                if (liked == true) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
                "Like",
                { liked = if (liked == true) null else true },
                tint = if (liked == true) Accent else TerminalWhite.copy(0.4f)
            )
            // Thumbs down
            ActionIcon(
                if (liked == false) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                "Dislike",
                { liked = if (liked == false) null else false },
                tint = if (liked == false) Danger else TerminalWhite.copy(0.4f)
            )
            // Regenerate
            ActionIcon(Icons.Default.Refresh, "Regenerate", onRegenerate)
        } else {
            // User message: edit & delete
            ActionIcon(Icons.Default.Edit, "Edit", { /* future: edit mode */ })
            ActionIcon(Icons.Default.Delete, "Delete", onDelete)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = TerminalWhite.copy(0.4f),
    size: androidx.compose.ui.unit.Dp = 14.dp
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp)
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

// ── Enhanced Loading Dots with text ─────────────────────────────────────────
@Composable
private fun EnhancedLoadingDots() {
    val inf = rememberInfiniteTransition(label = "loading")
    val alpha by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    val scale by inf.animateFloat(0.8f, 1.0f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")

    Column(modifier = Modifier.padding(4.dp)) {
        Row(
            modifier = Modifier.height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("thinking", color = TerminalWhite.copy(0.5f), fontSize = 12.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            repeat(3) { i ->
                val dotColor = if (i == 1) Color(0xFFB388FF) else Color(0xFF00E5FF)
                Box(
                    Modifier
                        .size(8.dp)
                        .scale(scale)
                        .background(dotColor.copy(alpha = alpha), CircleShape)
                )
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

// ── Privacy Chip ───────────────────────────────────────────────────────────
@Composable
private fun PrivacyChip(mode: PrivacyMode) {
    val color = when (mode) {
        PrivacyMode.LOCAL_ONLY     -> TerminalGreen
        PrivacyMode.HYBRID         -> TerminalYellow
        PrivacyMode.CLOUD_ENHANCED -> TerminalBlue
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text("${mode.emoji} ${mode.label}", color = color, fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

// ── Status Chip ────────────────────────────────────────────────────────────
@Composable
private fun StatusChip(label: String, active: Boolean, activeColor: Color = TerminalGreen) {
    Surface(shape = RoundedCornerShape(50),
        color = if (active) activeColor.copy(0.15f) else TerminalRed.copy(0.10f)) {
        Text("● $label", color = if (active) activeColor else TerminalRed,
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

// ── ENHANCED Input Bar with markdown toolbar ───────────────────────────────
@Composable
private fun EnhancedHybridInputBar(
    text: String,
    isLoading: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    attachedFiles: List<String>,
    onAttachFile: () -> Unit
) {
    val context = LocalContext.current
    var showMarkdownTools by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { onTextChange(text + it) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // ── Markdown toolbar (collapsible) ────────────────────────────────
        AnimatedVisibility(visible = showMarkdownTools) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "**" to "Bold", "*" to "Italic", "`" to "Code",
                    "[link](url)" to "Link", "- " to "List", "```" to "Code block"
                ).forEach { (sym, label) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SurfaceLight.copy(0.2f),
                        modifier = Modifier.clickable {
                            onTextChange(text + sym)
                        }
                    ) {
                        Text(
                            label,
                            color = TerminalWhite.copy(0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // ── Main input surface ────────────────────────────────────────────
        Surface(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(24.dp),
            color     = Color(0xFF0F0F0F),
            border    = BorderStroke(1.dp, Color(0xFF2A2A2A))
        ) {
            Row(
                Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Markdown toggle
                IconButton(
                    onClick = { showMarkdownTools = !showMarkdownTools },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (showMarkdownTools) Icons.Default.Code else Icons.Default.CodeOff,
                        "Markdown",
                        tint = if (showMarkdownTools) Accent else Color.White.copy(0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Text field
                TextField(
                    value         = text,
                    onValueChange = onTextChange,
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        Text(
                            if (attachedFiles.isNotEmpty()) "[${attachedFiles.size} file(s)] Ask..." else "Ask anything...",
                            fontSize = 15.sp, color = Color(0xFF666666)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = Accent
                    ),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) onSend()
                    })
                )

                // Character count (when typing)
                if (text.length > 100) {
                    Text(
                        "${text.length}",
                        color = if (text.length > 400) TerminalRed.copy(0.7f) else TerminalWhite.copy(0.3f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                // Send / Voice button
                val hasContent = text.isNotBlank() || attachedFiles.isNotEmpty()
                if (hasContent) {
                    IconButton(
                        onClick = {
                            if (attachedFiles.isNotEmpty()) {
                                val filePrefix = "[Attached: ${attachedFiles.joinToString(", ")}]\n"
                                onTextChange(filePrefix + text)
                            }
                            onSend()
                        },
                        enabled  = !isLoading,
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (isLoading)
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                color = Accent,
                                strokeWidth = 2.dp
                            )
                        else
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                "Send",
                                tint = Accent
                            )
                    }
                } else {
                    IconButton(
                        onClick = {
                            try {
                                speechLauncher.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Voice input not supported",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Outlined.MicNone, "Voice",
                            tint = Color.White.copy(0.8f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "INTERNDRA AI CAN MAKE MISTAKES. VERIFY CRITICAL OUTPUT.",
            color = Color(0xFF444444), fontSize = 9.sp,
            letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
    }
}

// ── ENHANCED Welcome Screen with categorized suggestions ───────────────────
@Composable
private fun EnhancedWelcomeScreen(vm: HybridAgentViewModel, onTextChange: (String) -> Unit) {
    val uiState by vm.uiState.collectAsState()
    var selectedCategory by remember { mutableStateOf(0) }

    val categories = listOf(
        "All" to Icons.Default.AllInclusive,
        "System" to Icons.Default.PhoneAndroid,
        "Web" to Icons.Default.Public,
        "Files" to Icons.Default.Folder,
        "Apps" to Icons.Default.Apps
    )

    val suggestionsByCategory = mapOf(
        "System" to listOf(
            "📊 Battery & storage status",
            "🔋 What's running in background?",
            "📶 Show my Wi-Fi info",
            "📱 Device info & specs",
            "🔦 Turn on flashlight",
            "🔊 Set volume to 50%"
        ),
        "Web" to listOf(
            "🔍 Search the web for latest AI news",
            "🌤 What's the weather today?",
            "📰 Top headlines this week",
            "💵 Current USD to INR rate",
            "🎬 Latest movie releases"
        ),
        "Files" to listOf(
            "📂 List files in Downloads",
            "📸 Find recent screenshots",
            "🗑 Clear junk files larger than 100MB",
            "📥 Show my downloads sorted by size",
            "📁 Check internal storage usage"
        ),
        "Apps" to listOf(
            "🚀 Open WhatsApp",
            "📧 Open Gmail",
            "🎵 Play music on Spotify",
            "📺 Open YouTube",
            "⚙️ Open Settings"
        )
    )

    val currentSuggestions = remember(selectedCategory) {
        if (selectedCategory == 0) {
            suggestionsByCategory.values.flatten()
        } else {
            suggestionsByCategory[categories[selectedCategory].first] ?: emptyList()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Animated glow logo ────────────────────────────────────────────
        val inf = rememberInfiniteTransition(label = "glow")
        val glowAlpha by inf.animateFloat(0.05f, 0.25f,
            infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse), label = "glow")

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
            Box(Modifier.size(80.dp).background(Color(0xFF00E5FF).copy(alpha = glowAlpha), CircleShape))
            Box(Modifier.size(55.dp).background(Color(0xFF00E5FF).copy(alpha = 0.08f), CircleShape))
            Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(38.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("INTERNDRA", color = TerminalWhite, fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        Text("Private AI Operating System", color = TerminalWhite.copy(0.5f), fontSize = 13.sp)

        Spacer(Modifier.height(6.dp))

        // Processing indicator
        val modeColor = when (uiState.privacyMode) {
            PrivacyMode.LOCAL_ONLY     -> TerminalGreen
            PrivacyMode.HYBRID         -> TerminalYellow
            PrivacyMode.CLOUD_ENHANCED -> TerminalBlue
        }
        Surface(shape = RoundedCornerShape(50), color = modeColor.copy(0.12f)) {
            Text("${uiState.privacyMode.emoji} ${uiState.privacyMode.label} Processing",
                color = modeColor, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
        }

        Spacer(Modifier.height(20.dp))

        // ── Category pills ────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEachIndexed { idx, (name, icon) ->
                val selected = idx == selectedCategory
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) Accent.copy(0.2f) else SurfaceLight.copy(0.1f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) Accent.copy(0.5f) else SurfaceLight.copy(0.2f)
                    ),
                    modifier = Modifier.clickable { selectedCategory = idx }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            icon,
                            null,
                            tint = if (selected) Accent else TerminalWhite.copy(0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            name,
                            color = if (selected) Accent else TerminalWhite.copy(0.6f),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "QUICK ACTIONS",
            color = TerminalWhite.copy(0.35f),
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // ── Suggestions grid ──────────────────────────────────────────────
        currentSuggestions.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { onTextChange(suggestion.dropWhile { it != ' ' }.drop(1)) },
                        border  = BorderStroke(1.dp, SurfaceLight.copy(0.5f)),
                        shape   = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            suggestion,
                            color = TerminalWhite.copy(0.8f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Fill empty slot if odd count
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }

        // ── Usage stats (shown if available) ──────────────────────────────
        if (uiState.memoryCount > 0 || uiState.knowledgeCount > 0) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.memoryCount > 0) {
                    Surface(shape = RoundedCornerShape(50), color = VaultPurple.copy(0.12f)) {
                        Text(
                            "🧠 ${uiState.memoryCount} memories",
                            color = VaultPurple.copy(0.7f), fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (uiState.knowledgeCount > 0) {
                    Surface(shape = RoundedCornerShape(50), color = VaultCyan.copy(0.12f)) {
                        Text(
                            "📚 ${uiState.knowledgeCount} entries",
                            color = VaultCyan.copy(0.7f), fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(50), color = Accent.copy(0.12f)) {
                    Text(
                        "⚡ ${uiState.lastLatencyMs}ms",
                        color = Accent.copy(0.7f), fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
