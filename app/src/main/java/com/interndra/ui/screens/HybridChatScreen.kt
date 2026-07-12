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
import com.interndra.ui.components.*
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
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    val keyboard   = LocalSoftwareKeyboardController.current
    val context    = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // ── Streaming state: which FRESH (newly arrived) message is streaming ──
    // FIX: The old code used LaunchedEffect(messages) which triggered on EVERY
    // message list change, including loading old messages from the database.
    // This caused ALL old AI messages to get the typing animation on app startup.
    // Now we only animate messages that arrived AFTER the composable was created.
    val initialMessageCount = remember { messages.size }
    var streamingMsgId by remember { mutableStateOf<Long?>(null) }
    var streamedText by remember { mutableStateOf("") }
    var streamCharsPerFrame by remember { mutableStateOf(3) }

    // Auto-scroll on new messages (use scrollToItem for instant, not animate)
    LaunchedEffect(messages.size, streamedText) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // ── Streaming effect: animate NEW messages after initial load ──
    // FIX: Use a Set<Long> to track which message IDs have been animated,
    // so EVERY new AI message gets the typing effect, not just the first one.
    val animatedMessageIds = remember { mutableSetOf<Long>() }
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull()
        // Only animate AI messages that arrived AFTER initial load and haven't been animated yet
        if (lastMsg != null && messages.size > initialMessageCount &&
            lastMsg.role == MessageRole.AI && !lastMsg.isLoading &&
            lastMsg.content.length > 30 && lastMsg.id !in animatedMessageIds) {
            animatedMessageIds.add(lastMsg.id)
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

    Column(Modifier.fillMaxSize().background(Background800).imePadding()) {

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

        // ── Messages ──────────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier.weight(1f),
            contentPadding      = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item { PremiumWelcomeScreen { text -> inputText = text } }
            } else {
                // Render grouped messages
                itemsIndexed(groupedMessages, key = { idx, _ -> "group_${idx}_${messages.size}" }) { _, (role, msgs) ->
                    MessageGroup(
                        role = role,
                        messages = msgs,
                        streamingMsgId = streamingMsgId,
                        streamedText = streamedText,
                        onCopy = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { msg -> vm.deleteMessage(msg) },
                        onRegenerate = { vm.sendCommand("regenerate last response") }
                    )
                }
            }
        }

        // ── Simple Input Bar ──────────────────────────────────────────────
        SimpleInputBar(
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
            }
        )
    }
}

// ── Simple Top Bar ────────────────────────────────────────────────────────
@Composable
private fun SimpleTopBar(
    onOpenDrawer: () -> Unit
) {
    Surface(color = Color(0xFF0F0F0F), tonalElevation = 0.dp) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.Menu, "Menu", tint = Color.White)
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
                        color = Background800,
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
                            .widthIn(max = if (isUser) 300.dp else 400.dp)
                            .clip(borderRadius)
                            .background(
                                if (isUser) UserBubble else Color(0xFF1A1B1E),
                                shape = borderRadius
                            )
                            .border(
                                if (!isUser) 0.5.dp else 0.dp,
                                if (!isUser) SurfaceLight.copy(alpha = 0.3f) else Color.Transparent,
                                shape = borderRadius
                            )
                            .padding(
                                horizontal = if (isUser) 16.dp else 14.dp,
                                vertical = if (isUser) 12.dp else 6.dp
                            )
                    ) {
                        if (msg.isLoading) {
                            ThinkingIndicator()
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(24.dp),
            color     = Color(0xFF1A1B1E),
            border    = BorderStroke(1.dp, Color(0xFF2A2A2A))
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
