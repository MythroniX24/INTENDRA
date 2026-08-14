package com.interndra.ui.screens

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.AgentActivity
import com.interndra.agent.AgentQuestion
import com.interndra.agent.AgentState
import com.interndra.agent.QuestionAnswer
import com.interndra.ai.ThinkingEpisode
import com.interndra.ai.ThinkingMarker
import com.interndra.ai.provider.ProviderCapability
import com.interndra.ai.provider.ProviderConfig
import com.interndra.ai.provider.ProviderRole
import com.interndra.ai.provider.ProviderState
import com.interndra.ai.provider.ProviderStatus
import com.interndra.ai.tasks.StepStatus
import com.interndra.ai.tasks.TaskPlan
import com.interndra.ai.tasks.TaskStatus
import com.interndra.data.model.ChatMessage
import com.interndra.data.model.MessageRole
import com.interndra.data.model.PrivacyMode
import com.interndra.search.SourceMarker
import com.interndra.ui.components.MarkdownText
import com.interndra.ui.theme.InterndraColors
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.viewmodel.ActiveCommand
import com.interndra.ui.viewmodel.CommandStatus
import com.interndra.ui.viewmodel.HybridAgentViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

// ── Light semantic colors (white-first palette) ──────────────────────────────
private val SuccessGreen = Color(0xFF1E8E3E)
private val DestructiveRed = Color(0xFFD93025)
private val Amber = Color(0xFFB06000)

/**
 * ChatScreen — the new light-first, Claude-inspired conversation screen.
 *
 * Conversation is the hero: user messages are compact right-aligned chips on a
 * near-white surface; AI responses are full-width, document-style markdown with
 * collapsible Thinking + Sources and a compact agent activity panel. The
 * composer floats at the bottom with a model selector, voice input and a
 * Send/Stop button that mirrors the generation state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenModelSettings: () -> Unit = {}
) {
    val messages by vm.messages.collectAsState()
    val uiState by vm.uiState.collectAsState()
    val privacyMode by vm.privacyMode.collectAsState()
    val providerState by vm.providerState.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val activeCommands by vm.activeCommands.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val agentActivities by vm.agentActivity.collectAsState()
    val pendingQuestion by vm.pendingQuestion.collectAsState()
    val activeTask by vm.taskManager.activeTask.collectAsState()

    val colors = LocalInterndraColors.current
    var inputText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    // ── Voice input (speech-to-text) ─────────────────────────────────────────
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
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
        } catch (_: Exception) {
            Toast.makeText(context, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // ── Top bar ─────────────────────────────────────────────────────────
        ChatTopBar(
            workspaceName = uiState.activeWorkspaceName,
            privacyMode = privacyMode,
            localModelReady = uiState.localModelReady,
            providerName = composerProviderName(providerState),
            modelName = selectedModel,
            onMenu = onOpenDrawer,
            onModelClick = {} // model selection is exposed in the composer
        )

        // ── Status banners (kept subtle, not dashboard-like) ────────────────
        if (uiState.emergencyLockActive) {
            EmergencyBanner(onUnlock = { vm.deactivateEmergencyLock() })
        }
        uiState.error?.let { err ->
            ErrorBanner(message = err, onDismiss = { vm.dismissError() })
        }
        uiState.pendingCloudConsent?.let { req ->
            CloudConsentBanner(
                reason = req.reason,
                domain = req.destinationDomain,
                onAllow = { vm.allowCloudConsent() },
                onDeny = { vm.denyCloudConsent() }
            )
        }

        // ── Inline question card ────────────────────────────────────────────
        pendingQuestion?.let { question ->
            QuestionCard(
                question = question,
                onAnswer = { vm.answerQuestion(it) },
                onCancel = { vm.cancelQuestion() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Safety confirmation ─────────────────────────────────────────────
        uiState.pendingConfirmation?.let { req ->
            ConfirmationBanner(
                message = req.message,
                summary = req.commandSummary,
                onAccept = { vm.confirmAction() },
                onDeny = { vm.denyAction() }
            )
        }

        // ── Agent activity (compact, auto-collapses when idle) ──────────────
        if (agentActivities.isNotEmpty() || agentState != AgentState.IDLE) {
            AgentActivityPanel(
                activities = agentActivities,
                state = agentState,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // ── Running commands ────────────────────────────────────────────────
        if (activeCommands.isNotEmpty()) {
            CommandPanel(
                commands = activeCommands,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // ── Conversation ────────────────────────────────────────────────────
        MessageList(
            modifier = Modifier.weight(1f),
            messages = messages,
            activeTask = activeTask,
            colors = colors,
            onSuggestion = { inputText = it },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            onDelete = { vm.deleteMessage(it) },
            onRegenerate = { vm.sendCommand("regenerate last response") },
            onShare = { text ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
            },
            onTaskPause = { vm.taskManager.pause() },
            onTaskResume = { vm.taskManager.resume() },
            onTaskRetry = { vm.taskManager.retryAll() },
            onTaskCancel = { vm.taskManager.cancel() },
            onTaskRetryStep = { idx -> vm.taskManager.retryStep(idx) }
        )

        // ── Composer ────────────────────────────────────────────────────────
        Composer(
            text = inputText,
            onTextChange = { inputText = it },
            isLoading = uiState.isLoading,
            providerName = composerProviderName(providerState),
            modelName = selectedModel,
            providers = providerState.providers,
            defaultProviderId = providerState.defaults.chat,
            onSend = {
                if (inputText.isNotBlank()) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    keyboard?.hide()
                    vm.sendCommand(inputText)
                    inputText = ""
                }
            },
            onStop = { vm.stopGeneration() },
            onVoice = launchVoice,
            onSelectProvider = { vm.setProviderDefault(ProviderRole.CHAT, it) },
            onSelectModel = { p, m -> vm.setActiveProviderModel(p, m) },
            onOpenModelSettings = onOpenModelSettings
        )
    }
}

// ── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    workspaceName: String,
    privacyMode: PrivacyMode,
    localModelReady: Boolean,
    providerName: String,
    modelName: String,
    onMenu: () -> Unit,
    onModelClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, "Menu", tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        workspaceName.ifBlank { "INTENDRA" },
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        privacySubtitle(privacyMode, localModelReady),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(
                    text = privacyLabel(privacyMode),
                    color = privacyColor(privacyMode)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f)) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

private fun privacyLabel(mode: PrivacyMode): String = when (mode) {
    PrivacyMode.LOCAL_ONLY -> "Local"
    PrivacyMode.CLOUD_ENHANCED -> "Cloud"
    PrivacyMode.HYBRID -> "Hybrid"
}

private fun privacyColor(mode: PrivacyMode): Color = when (mode) {
    PrivacyMode.LOCAL_ONLY -> SuccessGreen
    PrivacyMode.HYBRID -> Amber
    PrivacyMode.CLOUD_ENHANCED -> Color(0xFF3578E4)
}

private fun privacySubtitle(mode: PrivacyMode, localModelReady: Boolean): String =
    when {
        mode == PrivacyMode.LOCAL_ONLY && localModelReady -> "On-device · local model ready"
        mode == PrivacyMode.LOCAL_ONLY -> "On-device processing"
        mode == PrivacyMode.HYBRID -> "Local-first, asks before cloud"
        else -> "Cloud AI"
    }

private fun composerProviderName(state: ProviderState): String =
    state.providers.firstOrNull { it.id == state.defaults.chat && it.isReadyForChat }?.name
        ?: state.providers.firstOrNull()?.name
        ?: "No provider"

// ── Banners ─────────────────────────────────────────────────────────────────

@Composable
private fun EmergencyBanner(onUnlock: () -> Unit) {
    Surface(color = DestructiveRed.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = DestructiveRed, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text("Emergency lock — local only", color = DestructiveRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onUnlock) { Text("Unlock", color = DestructiveRed, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = DestructiveRed.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = DestructiveRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("✕", color = DestructiveRed) }
        }
    }
}

@Composable
private fun CloudConsentBanner(reason: String, domain: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    Surface(color = Color(0xFFE8F0FE), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, null, tint = Color(0xFF3578E4), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cloud AI request", color = Color(0xFF1A4B8C), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(reason, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
            Text("Destination: $domain", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3578E4)),
                    modifier = Modifier.weight(1f)
                ) { Text("Allow once", color = Color.White, fontSize = 12.sp) }
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Stay local", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ConfirmationBanner(message: String, summary: String, onAccept: () -> Unit, onDeny: () -> Unit) {
    Surface(color = Amber.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Action required", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
            if (summary.isNotBlank()) {
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed), modifier = Modifier.weight(1f)) {
                    Text("Confirm", color = Color.White, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Cancel", fontSize = 12.sp) }
            }
        }
    }
}

// ── Agent activity panel ────────────────────────────────────────────────────

@Composable
private fun AgentActivityPanel(
    activities: List<AgentActivity>,
    state: AgentState,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val active = state.isActive()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (active) {
                    val pulse by rememberInfiniteTransition(label = "work").animateFloat(
                        0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "work"
                    )
                    Box(Modifier.size(7.dp).alpha(pulse).background(SuccessGreen, CircleShape))
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (active) "Working…" else "Completed",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    activities.forEach { act ->
                        ActivityRow(act)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(act: AgentActivity) {
    val (icon, color) = when (act) {
        is AgentActivity.ToolResult -> if (act.success) ("✓" to SuccessGreen) else ("✕" to DestructiveRed)
        is AgentActivity.Error -> ("✕" to DestructiveRed)
        is AgentActivity.Completed -> ("✓" to SuccessGreen)
        is AgentActivity.Search -> ("🔍" to Color(0xFF3578E4))
        else -> ("•" to MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = color, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            activityMessage(act),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun activityMessage(act: AgentActivity): String = when (act) {
    is AgentActivity.Thinking -> act.message
    is AgentActivity.Planning -> act.message
    is AgentActivity.ToolStart -> act.description.ifBlank { act.command }
    is AgentActivity.ToolResult -> act.summary
    is AgentActivity.Search -> "${act.message}: ${act.query}"
    is AgentActivity.Reading -> act.message
    is AgentActivity.Verification -> act.message
    is AgentActivity.Error -> act.message
    is AgentActivity.Completed -> "Task complete"
}

// ── Command panel ───────────────────────────────────────────────────────────

@Composable
private fun CommandPanel(commands: List<ActiveCommand>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val running = commands.count { it.status == CommandStatus.RUNNING }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        running > 0 -> "Running $running command${if (running > 1) "s" else ""}…"
                        commands.all { it.status == CommandStatus.SUCCESS } -> "✓ Commands completed"
                        commands.all { it.status == CommandStatus.FAILED } -> "✕ Commands failed"
                        else -> "${commands.count { it.status == CommandStatus.SUCCESS }}/${commands.size} completed"
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    commands.forEach { cmd ->
                        val statusColor = when (cmd.status) {
                            CommandStatus.RUNNING -> Color(0xFF3578E4)
                            CommandStatus.SUCCESS -> SuccessGreen
                            CommandStatus.FAILED -> DestructiveRed
                        }
                        Text(
                            "$ ${cmd.command}",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            cmd.description,
                            color = statusColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (cmd.status == CommandStatus.FAILED && cmd.error.isNotBlank()) {
                            Text(
                                cmd.error.take(140),
                                color = DestructiveRed.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

// ── Message list ────────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    modifier: Modifier,
    messages: List<ChatMessage>,
    activeTask: TaskPlan?,
    colors: InterndraColors,
    onSuggestion: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
    onShare: (String) -> Unit,
    onTaskPause: () -> Unit,
    onTaskResume: () -> Unit,
    onTaskRetry: () -> Unit,
    onTaskCancel: () -> Unit,
    onTaskRetryStep: (Int) -> Unit
) {
    val listState = rememberLazyListState()    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var userScrolledUp by remember { mutableStateOf(false) }

    // Stop following while the user scrolls away from the bottom.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val itemBottom = last?.let { it.offset + it.size } ?: Int.MAX_VALUE
            if (itemBottom > info.viewportSize.height + 4) {
                userScrolledUp = true
            }
        }
    }

    // Follow the newest message when it arrives / grows. Instant scroll while
    // streaming avoids animateScrollToItem churn on every token.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (userScrolledUp) return@LaunchedEffect
        val lastIndex = messages.lastIndex
        if (lastIndex >= 0) listState.scrollToItem(lastIndex)
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (messages.isEmpty()) {
                item(key = "welcome") { WelcomeScreen(onSuggestion = onSuggestion) }
            } else {
                itemsIndexed(
                    messages,
                    key = { _, msg -> msg.id }
                ) { _, msg ->
                    MessageRow(
                        msg = msg,
                        isStreaming = msg.isLoading,
                        onCopy = onCopy,
                        onDelete = onDelete,
                        onRegenerate = onRegenerate,
                        onShare = onShare
                    )
                }
                activeTask?.let { task ->
                    item(key = "task_${task.id}") {
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

        // ── Jump to latest ──────────────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible = userScrolledUp && messages.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clickable {
                    userScrolledUp = false
                    keyboard?.hide()
                    scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Jump to latest", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Message row ─────────────────────────────────────────────────────────────

@Composable
private fun MessageRow(
    msg: ChatMessage,
    isStreaming: Boolean,
    onCopy: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
    onShare: (String) -> Unit
) {
    val colors = LocalInterndraColors.current
    val context = LocalContext.current

    if (msg.role == MessageRole.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = Color(0xFFF0F0F2),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    msg.content,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            MessageActions(
                onCopy = { onCopy(msg.content) },
                onDelete = { onDelete(msg) }
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (msg.isLoading && msg.content.isBlank()) {
                ThinkingIndicator()
            } else {
                // Strip hidden markers; render Thinking + Sources separately.
                val sources = remember(msg.id, msg.content) { SourceMarker.decode(msg.content) }
                val thinking = remember(msg.id, msg.content) { ThinkingMarker.decode(msg.content) }
                val visible = ThinkingMarker.strip(SourceMarker.strip(msg.content))

                if (thinking.isNotEmpty()) {
                    ThinkingBlock(episodes = thinking, messageId = msg.id)
                    Spacer(Modifier.height(6.dp))
                }
                MarkdownText(
                    markdown = visible,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    isStreaming = isStreaming
                )
                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    SourcesBox(sources = sources, messageId = msg.id)
                }
            }
            if (!msg.isLoading) {
                val clean = ThinkingMarker.strip(SourceMarker.strip(msg.content))
                MessageActions(
                    onCopy = { onCopy(clean) },
                    onRegenerate = onRegenerate,
                    onShare = { onShare(clean) },
                    onDelete = { onDelete(msg) }
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "thinking")
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Thinking", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), fontSize = 13.sp)
        repeat(3) { i ->
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(5.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun MessageActions(
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIcon(Icons.Default.ContentCopy, "Copy", onCopy)
        onRegenerate?.let { ActionIcon(Icons.Default.Refresh, "Regenerate", it) }
        onShare?.let { ActionIcon(Icons.Default.Share, "Share", it) }
        ActionIcon(Icons.Default.Delete, "Delete", onDelete)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon,
        label,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(5.dp)
    )
}

// ── Thinking block ──────────────────────────────────────────────────────────

@Composable
private fun ThinkingBlock(episodes: List<ThinkingEpisode>, messageId: Long) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    var openEpisode by remember(messageId) { mutableStateOf(-1) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F8),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded; if (!expanded) openEpisode = -1 }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Psychology, null, tint = Color(0xFF3578E4), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (episodes.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("${episodes.size}", color = Color(0xFF3578E4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "Hide" else "View", color = Color(0xFF3578E4), fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    episodes.forEachIndexed { idx, ep ->
                        val isOpen = openEpisode == idx
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { openEpisode = if (isOpen) -1 else idx }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    ep.title.ifBlank { "Reasoning" },
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp)
                                )
                            }
                            if (isOpen) {
                                ep.steps.forEach { step ->
                                    Text(
                                        step,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
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

// ── Sources box ─────────────────────────────────────────────────────────────

@Composable
private fun SourcesBox(sources: List<SourceMarker.SourceLink>, messageId: Long) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F8),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Language, null, tint = Color(0xFF3578E4), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sources", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${sources.size}", color = Color(0xFF3578E4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    sources.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(s.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}.", color = Color(0xFF3578E4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title.ifBlank { s.url }, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(SourceMarker.hostname(s.url), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Task card ───────────────────────────────────────────────────────────────

@Composable
private fun TaskCard(
    task: TaskPlan,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRetryStep: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${task.completedSteps}/${task.steps.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            task.steps.forEach { step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, color) = when (step.status) {
                        StepStatus.COMPLETED -> ("✓" to SuccessGreen)
                        StepStatus.RUNNING -> ("●" to Color(0xFF3578E4))
                        StepStatus.FAILED -> ("✕" to DestructiveRed)
                        else -> ("○" to MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(icon, color = color, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(step.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (step.status == StepStatus.FAILED) {
                        TextButton(onClick = { onRetryStep(step.index) }) { Text("Retry", fontSize = 10.sp) }
                    }
                }
            }
            if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.CANCELLED) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.status == TaskStatus.PAUSED) {
                        TextButton(onClick = onResume) { Text("Resume") }
                    } else {
                        TextButton(onClick = onPause) { Text("Pause") }
                    }
                    TextButton(onClick = onRetry) { Text("Retry all") }
                    TextButton(onClick = onCancel) { Text("Cancel", color = DestructiveRed) }
                }
            }
        }
    }
}

// ── Question card ───────────────────────────────────────────────────────────

@Composable
private fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (QuestionAnswer) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var answered by remember(question.id) { mutableStateOf<String?>(null) }
    var customText by remember(question.id) { mutableStateOf("") }
    var showCustom by remember(question.id) { mutableStateOf(false) }
    var numberText by remember(question.id) { mutableStateOf("") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (answered != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(answered!!, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                return@Column
            }
            Text(question.question, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            when (question) {
                is AgentQuestion.SingleChoice -> {
                    question.options.forEach { opt ->
                        OptionRow(
                            title = opt.title,
                            description = opt.description,
                            recommended = opt.recommended,
                            warning = opt.warning,
                            onClick = {
                                answered = "✓ ${opt.title}"
                                onAnswer(QuestionAnswer.SingleChoiceAnswer(question.id, opt.id))
                            }
                        )
                    }
                    if (question.youDecide) {
                        OptionRow(title = "You decide", description = "", recommended = false, warning = "", onClick = {
                            answered = "✓ You decide"
                            onAnswer(QuestionAnswer.YouDecide(question.id))
                        })
                    }
                    if (question.allowCustomAnswer) {
                        if (showCustom) {
                            CustomAnswerField(
                                value = customText,
                                onChange = { customText = it },
                                onSubmit = {
                                    answered = "✓ $customText"
                                    onAnswer(QuestionAnswer.CustomAnswer(question.id, customText))
                                }
                            )
                        } else {
                            TextButton(onClick = { showCustom = true }) { Text("Other…") }
                        }
                    }
                }
                is AgentQuestion.MultiChoice -> {
                    var selected by remember(question.id) { mutableStateOf(setOf<String>()) }
                    question.options.forEach { opt ->
                        val isSel = opt.id in selected
                        OptionRow(
                            title = opt.title,
                            description = opt.description,
                            recommended = opt.recommended,
                            warning = opt.warning,
                            onClick = {
                                selected = if (isSel) selected - opt.id else selected + opt.id
                            }
                        )
                    }
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            answered = "✓ ${selected.size} selected"
                            onAnswer(QuestionAnswer.MultiChoiceAnswer(question.id, selected.toList()))
                        }
                    ) { Text("Confirm selection") }
                }
                is AgentQuestion.Confirmation -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                answered = "✓ ${question.confirmLabel}"
                                onAnswer(QuestionAnswer.ConfirmationAnswer(question.id, true))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed),
                            modifier = Modifier.weight(1f)
                        ) { Text(question.confirmLabel, color = Color.White) }
                        OutlinedButton(
                            onClick = {
                                answered = "✕ ${question.cancelLabel}"
                                onAnswer(QuestionAnswer.ConfirmationAnswer(question.id, false))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(question.cancelLabel) }
                    }
                }
                is AgentQuestion.YesNo -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { answered = "✓ Yes"; onAnswer(QuestionAnswer.YesNoAnswer(question.id, true)) }, modifier = Modifier.weight(1f)) { Text("Yes") }
                        OutlinedButton(onClick = { answered = "✕ No"; onAnswer(QuestionAnswer.YesNoAnswer(question.id, false)) }, modifier = Modifier.weight(1f)) { Text("No") }
                    }
                }
                is AgentQuestion.TextInput -> {
                    CustomAnswerField(
                        value = customText,
                        onChange = { customText = it },
                        placeholder = question.placeholder,
                        onSubmit = {
                            answered = "✓ $customText"
                            onAnswer(QuestionAnswer.TextAnswer(question.id, customText))
                        }
                    )
                }
                is AgentQuestion.NumberInput -> {
                    OutlinedTextField(
                        value = numberText,
                        onValueChange = { numberText = it.filter { c -> c.isDigit() || c == '-' } },
                        singleLine = true,
                        placeholder = { Text(question.placeholder) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = numberText.isNotBlank(),
                        onClick = {
                            answered = "✓ $numberText"
                            onAnswer(QuestionAnswer.NumberAnswer(question.id, numberText.toIntOrNull() ?: 0))
                        }
                    ) { Text("Submit") }
                }
            }

            TextButton(onClick = onCancel) { Text("Cancel task", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    recommended: Boolean,
    warning: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (recommended) Color(0xFF3578E4).copy(alpha = 0.06f) else Color(0xFFF7F7F8),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (recommended) Color(0xFF3578E4).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (description.isNotBlank()) {
                    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (warning.isNotBlank()) {
                    Text("⚠ $warning", color = Amber, fontSize = 10.sp)
                }
            }
            if (recommended) {
                Text("Recommended", color = Color(0xFF3578E4), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CustomAnswerField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "Type your answer…",
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSubmit() })
    )
    Button(enabled = value.isNotBlank(), onClick = onSubmit) { Text("Submit") }
}

// ── Composer ────────────────────────────────────────────────────────────────

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    isLoading: Boolean,
    providerName: String,
    modelName: String,
    providers: List<ProviderConfig>,
    defaultProviderId: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onOpenModelSettings: () -> Unit
) {
    var showModelSheet by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Model selector chip
        Row(Modifier.padding(start = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showModelSheet = true }
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = Color(0xFF3578E4), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (modelName.isNotBlank() && modelName != "openrouter/auto") modelName.substringAfterLast('/') else providerName,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color(0xFF3578E4), modifier = Modifier.size(16.dp))
                }
            }
        }

        // Input container (floating card)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask INTENDRA anything…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = Color(0xFF3578E4)
                    ),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() })
                )

                when {
                    isLoading -> {
                        Surface(
                            shape = CircleShape,
                            color = DestructiveRed.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onStop)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Stop, "Stop", tint = DestructiveRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    text.isBlank() -> {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF3578E4).copy(alpha = 0.10f),
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onVoice)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Mic, "Voice input", tint = Color(0xFF3578E4), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    else -> {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF3578E4),
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSend)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            providers = providers,
            defaultProviderId = defaultProviderId,
            onDismiss = { showModelSheet = false },
            onSelectProvider = onSelectProvider,
            onSelectModel = onSelectModel,
            onOpenModelSettings = {
                showModelSheet = false
                onOpenModelSettings()
            }
        )
    }
}

// ── Model picker sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    defaultProviderId: String?,
    onDismiss: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultProvider = providers.firstOrNull { it.id == defaultProviderId }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Model & provider", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Switch AI providers and models", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            Text("PROVIDERS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            providers.filter { it.isReadyForChat }.forEach { p ->
                val selected = p.id == defaultProviderId
                Surface(
                    color = if (selected) Color(0xFF3578E4).copy(alpha = 0.06f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(p.id) }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (p.isLocal) "📱" else "☁️", fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        Text(providerStatusLabel(p), color = providerStatusColor(p), fontSize = 10.sp)
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF3578E4), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (defaultProvider != null && defaultProvider.models.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("MODELS — ${defaultProvider.name.uppercase(Locale.ROOT)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                defaultProvider.models.forEach { m ->
                    val active = m.id == defaultProvider.activeModelId
                    Surface(
                        color = if (active) Color(0xFF3578E4).copy(alpha = 0.06f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelectModel(defaultProvider.id, m.id) }
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.displayName.ifBlank { m.id }, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (active) {
                                Icon(Icons.Default.Check, null, tint = Color(0xFF3578E4), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text("No models configured yet — add an API key and fetch models in provider settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenModelSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, null, tint = Color(0xFF3578E4), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Manage providers & API keys", color = Color(0xFF3578E4), fontSize = 13.sp)
            }
        }
    }
}

private fun providerStatusLabel(p: ProviderConfig): String = when (p.status) {
    ProviderStatus.CONNECTED -> "Connected"
    ProviderStatus.CONFIGURED -> "Configured"
    ProviderStatus.NOT_CONFIGURED -> "No key"
    else -> p.status.name.replace('_', ' ').lowercase()
}

private fun providerStatusColor(p: ProviderConfig): Color = when (p.status) {
    ProviderStatus.CONNECTED, ProviderStatus.CONFIGURED -> SuccessGreen
    ProviderStatus.NOT_CONFIGURED -> Amber
    ProviderStatus.INVALID_API_KEY, ProviderStatus.AUTHENTICATION_FAILED -> DestructiveRed
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ── Welcome state ───────────────────────────────────────────────────────────

@Composable
private fun WelcomeScreen(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "Check battery & storage",
        "Search the web",
        "List files in Downloads",
        "Open WhatsApp",
        "Show Wi-Fi info",
        "USD to INR rate"
    )
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF3578E4)),
            contentAlignment = Alignment.Center
        ) {
            Text("I", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(14.dp))
        Text("INTENDRA", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Private AI OS for Android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Local AI" to SuccessGreen, "Privacy" to Color(0xFF3578E4), "Agent" to Color(0xFF3578E4)).forEach { (label, c) ->
                Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.10f)) {
                    Text(label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).clickable { onSuggestion(s) }
                        ) {
                            Text(s, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
