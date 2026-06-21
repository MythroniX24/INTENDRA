package com.interndra.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.*
import com.interndra.ui.components.RichMarkdownText
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.launch

@Composable
fun HybridChatScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {}
) {
    val messages by vm.messages.collectAsState()
    val uiState  by vm.uiState.collectAsState()
    val mode     by vm.privacyMode.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()

    // Auto-scroll on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {

        // ── Top bar ───────────────────────────────────────────────────────
        Surface(color = Color(0xFF0F0F0F), tonalElevation = 0.dp) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.Menu, "Menu", tint = Color.White)
                }

                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("INTERNDRA", color = Color.White, fontSize = 22.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        fontFamily = FontFamily.SansSerif, letterSpacing = (-0.5).sp)

                    // Workspace label
                    if (uiState.activeWorkspaceName != "General" || uiState.activeWorkspaceId != 0L) {
                        Text(uiState.activeWorkspaceName, color = Accent.copy(alpha = 0.8f),
                            fontSize = 11.sp, letterSpacing = 0.5.sp)
                    }
                }

                IconButton(onClick = onNavigateToTerminal, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Default.Terminal, "Terminal", tint = Color.White)
                }
            }
        }

        // ── Emergency lock banner ─────────────────────────────────────────
        AnimatedVisibility(visible = uiState.emergencyLockActive) {
            Surface(color = TerminalRed.copy(0.15f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = TerminalRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Emergency Privacy Lock Active — Local Only",
                        color = TerminalRed, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
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
            CloudConsentBanner(
                reason      = req.reason,
                domain      = req.destinationDomain,
                onAllow     = { vm.allowCloudConsent() },
                onDeny      = { vm.denyCloudConsent() }
            )
        }

        // ── Safety confirmation dialog ─────────────────────────────────────
        uiState.pendingConfirmation?.let { req ->
            ConfirmationBanner(
                message  = req.message,
                summary  = req.commandSummary,
                onAccept = { vm.confirmAction() },
                onDeny   = { vm.denyAction() }
            )
        }

        // ── Status row ────────────────────────────────────────────────────
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrivacyChip(mode)
            if (uiState.localModelReady)
                StatusChip("Local AI ✓", true, TerminalGreen)
            else
                StatusChip("Local AI ✗", false, TerminalRed)

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

        // ── Messages ──────────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier.weight(1f),
            contentPadding      = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) item { WelcomeHybridScreen(vm) { text -> inputText = text } }
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }

        // ── Input bar ─────────────────────────────────────────────────────
        HybridInputBar(
            text         = inputText,
            isLoading    = uiState.isLoading,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
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

// ── Cloud Consent Banner ───────────────────────────────────────────────────
@Composable
private fun CloudConsentBanner(
    reason: String,
    domain: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Surface(color = Color(0xFF1A2040), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, null, tint = TerminalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cloud AI Request", color = TerminalBlue, fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                    Text("Allow Once", color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                Text("Action Required", color = TerminalYellow, fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                    Text("Confirm", color = Color.Black,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                OutlinedButton(onClick = onDeny,
                    border = BorderStroke(1.dp, TerminalRed),
                    modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = TerminalRed,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}

// ── Message Bubble ─────────────────────────────────────────────────────────
@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser  = msg.role == MessageRole.USER
    val context = LocalContext.current  // hoisted — used inside the non-composable onLinkClick lambda

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Surface(shape = RoundedCornerShape(12.dp), color = ChatBg,
                border = BorderStroke(1.dp, SurfaceLight),
                modifier = Modifier.padding(top = 8.dp, end = 12.dp).size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, "AI", tint = Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = if (isUser) 280.dp else 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (isUser) UserBubble else AiBubble)
                .padding(
                    horizontal = if (isUser) 18.dp else 10.dp,
                    vertical   = if (isUser) 14.dp else 8.dp
                )
        ) {
            if (msg.isLoading) {
                LoadingDots()
            } else if (isUser) {
                Text(msg.content, color = TerminalWhite, fontSize = 15.sp, lineHeight = 22.sp)
            } else {
                // Phase 3 + Phase 11: native Compose rich markdown renderer.
                // Replaces the per-bubble Markwon instance (which created one
                // expensive Markwon object per message — 100 messages = 100
                // Markwon instances with their plugin trees). Now a single
                // lightweight parser runs per message and renders with native
                // Compose primitives — faster, themeable, and interactive.
                RichMarkdownText(
                    markdown = msg.content,
                    modifier = Modifier.fillMaxWidth(),
                    onLinkClick = { url ->
                        // Links open in the browser via ACTION_VIEW
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }
    }
}

// ── Loading Dots ───────────────────────────────────────────────────────────
@Composable
private fun LoadingDots() {
    val inf = rememberInfiniteTransition(label = "loading")
    val alpha by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    val scale by inf.animateFloat(0.8f, 1.0f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")

    Row(Modifier.padding(12.dp).height(24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val dotColor = if (i == 1) Color(0xFFB388FF) else Color(0xFF00E5FF)
            Box(Modifier.size(10.dp).scale(scale).background(dotColor.copy(alpha = alpha), CircleShape))
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

// ── Status Chip ────────────────────────────────────────────────────────────
@Composable
private fun StatusChip(label: String, active: Boolean, activeColor: Color = TerminalGreen) {
    Surface(shape = RoundedCornerShape(50),
        color = if (active) activeColor.copy(0.15f) else TerminalRed.copy(0.10f)) {
        Text("● $label", color = if (active) activeColor else TerminalRed,
            fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

// ── Input Bar ──────────────────────────────────────────────────────────────
@Composable
private fun HybridInputBar(
    text: String, isLoading: Boolean,
    onTextChange: (String) -> Unit, onSend: () -> Unit
) {
    var attachedFiles by remember { mutableStateOf(listOf<String>()) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        // Note: actual file content reading would require ContentResolver
        // For now we attach the filename as context; real RAG would index the file
        val names = uris.map { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
            } ?: uri.lastPathSegment ?: "file"
        }
        attachedFiles = attachedFiles + names
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { onTextChange(text + it) }
        }
    }

    Column(Modifier.fillMaxWidth().background(Color(0xFF0F0F0F))
        .padding(horizontal = 16.dp, vertical = 10.dp)) {

        // Attached files
        if (attachedFiles.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachedFiles.forEach { file ->
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1E1E1E),
                        border = BorderStroke(1.dp, Color(0xFF333333))) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Icon(Icons.Default.AttachFile, null, tint = Accent, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(file.take(14) + if (file.length > 14) "…" else "",
                                color = Color.White, fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Close, "Remove", tint = Color.Gray,
                                modifier = Modifier.size(13.dp).clickable {
                                    attachedFiles = attachedFiles - file
                                })
                        }
                    }
                }
            }
        }

        Surface(
            modifier  = Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp),
            shape     = RoundedCornerShape(24.dp),
            color     = Color(0xFF0F0F0F),
            border    = BorderStroke(1.dp, Color(0xFF2A2A2A))
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Add, "Attach", tint = Color.White.copy(0.8f), modifier = Modifier.size(22.dp))
                }

                TextField(
                    value         = text,
                    onValueChange = onTextChange,
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        val prefix = if (attachedFiles.isNotEmpty()) "[${attachedFiles.size} file(s)] " else ""
                        Text("${prefix}Ask anything...", fontSize = 15.sp, color = Color(0xFF666666))
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
                    // FIX: was ImeAction.Default with onSend registered — will now never fire
                    // Use ImeAction.Send or newline (ImeAction.Default is correct for multiline)
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )

                val hasContent = text.isNotBlank() || attachedFiles.isNotEmpty()
                if (hasContent) {
                    IconButton(
                        onClick = {
                            val filePrefix = if (attachedFiles.isNotEmpty())
                                "[Attached: ${attachedFiles.joinToString(", ")}]\n" else ""
                            onTextChange(filePrefix + text)
                            onSend()
                            attachedFiles = emptyList()
                        },
                        enabled  = !isLoading,
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (isLoading)
                            CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                        else
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Accent)
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
                                android.widget.Toast.makeText(context, "Voice input not supported",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Outlined.MicNone, "Voice", tint = Color.White.copy(0.8f), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "INTERNDRA AI CAN MAKE MISTAKES. VERIFY CRITICAL OUTPUT.",
            color = Color(0xFF444444), fontSize = 9.sp,
            letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally), textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
    }
}

// ── Welcome Screen ─────────────────────────────────────────────────────────
@Composable
private fun WelcomeHybridScreen(vm: HybridAgentViewModel, onTextChange: (String) -> Unit) {
    val uiState by vm.uiState.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {

        val inf = rememberInfiniteTransition(label = "glow")
        val glowAlpha by inf.animateFloat(0.05f, 0.25f,
            infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse), label = "glow")

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(Modifier.size(100.dp).background(Color(0xFF00E5FF).copy(alpha = glowAlpha), CircleShape))
            Box(Modifier.size(70.dp).background(Color(0xFF00E5FF).copy(alpha = 0.08f), CircleShape))
            Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(44.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("INTERNDRA", color = TerminalWhite, fontSize = 28.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 2.sp)
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

        Spacer(Modifier.height(28.dp))
        Text("QUICK ACTIONS", color = TerminalWhite.copy(0.35f), fontSize = 10.sp,
            letterSpacing = 2.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        val suggestions = listOf(
            "📊 Battery & storage status",
            "📂 List files in Downloads",
            "🔋 What's running in background?",
            "📸 Take a screenshot",
            "🔍 Search the web for latest AI news",
            "🚀 Open WhatsApp"
        )
        suggestions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { onTextChange(suggestion.drop(3)) },
                        border  = BorderStroke(1.dp, SurfaceLight),
                        shape   = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(suggestion, color = TerminalWhite.copy(0.8f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
