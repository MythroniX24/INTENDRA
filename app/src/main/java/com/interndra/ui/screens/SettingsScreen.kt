package com.interndra.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ai.JailbreakEngine
import com.interndra.ai.JailbreakLevel
import com.interndra.ai.ObfuscationTechnique
import com.interndra.ai.model.ModelRole
import com.interndra.ai.model.OfflineAiMode
import com.interndra.ai.model.OfflineModelCatalog
import com.interndra.data.model.PrivacyMode
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.util.Constants

@Composable
fun SettingsScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    val providerState by vm.providerState.collectAsState()
    val linuxEnvState by vm.linuxEnvState.collectAsState()

    // Auto-refresh the Linux environment status when Settings opens.
    LaunchedEffect(Unit) { vm.checkLinuxEnvironment() }

    val privacyMode   by vm.privacyMode.collectAsState()
    val uiState       by vm.uiState.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val jailbreakEnabled by vm.jailbreakEnabled.collectAsState()
    val jailbreakLevel by vm.jailbreakLevel.collectAsState()
    val obfuscationTech by vm.obfuscationTechnique.collectAsState()
    val ttsEnabled by vm.ttsEnabled.collectAsState()
    val webSearchEnabled by vm.webSearchEnabled.collectAsState()
    val braveEnabled by vm.braveEnabled.collectAsState()
    val braveKey by vm.braveApiKey.collectAsState()
    val preferBrave by vm.preferBrave.collectAsState()
    val offlineAiEnabled by vm.offlineAiEnabled.collectAsState()
    val offlineAiMode by vm.offlineAiMode.collectAsState()
    val offlinePlannerModelId by vm.offlinePlannerModelId.collectAsState()
    val offlineChatModelId by vm.offlineChatModelId.collectAsState()
    val smartMemoryEnabled by vm.smartMemoryEnabled.collectAsState()
    val smartUserMemoryEnabled by vm.smartUserMemoryEnabled.collectAsState()
    val smartProjectMemoryEnabled by vm.smartProjectMemoryEnabled.collectAsState()
    val smartChatMemoryEnabled by vm.smartChatMemoryEnabled.collectAsState()
    val smartMemoryBudget by vm.smartMemoryBudget.collectAsState()

    var offlinePlannerExpanded by remember { mutableStateOf(false) }
    var offlineChatExpanded by remember { mutableStateOf(false) }
    var smartBudgetDraft by remember(smartMemoryBudget) { mutableFloatStateOf(smartMemoryBudget.toFloat()) }

    Column(Modifier.fillMaxSize().background(Background800)) {

        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Provider credentials and model management live in AI → Providers.
            // Normal settings only exposes models that are actually configured there.
            ConfiguredChatModelSelector(vm = vm)

            // ── Provider manager entry ───────────────────────────────────
            DashboardCard {
                SectionHeader("Provider Manager", modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    "Manage cloud, local, and custom AI endpoints from one secure place.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenProviders,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("AI → Providers", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }


            // ── Jailbreak section ────────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    SectionHeader("🧠 Jailbreak Mode", modifier = Modifier.weight(1f))
                    Switch(
                        checked = jailbreakEnabled,
                        onCheckedChange = { vm.saveJailbreakEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
                Text(
                    "Bypass AI content restrictions. Higher levels = more effective, but may affect reliability.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )

                if (jailbreakEnabled) {
                    // ── Jailbreak Level ─────────────────────────────────
                    Text("Jailbreak Level", color = TerminalWhite, fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    JailbreakLevel.values().drop(1).forEach { level ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (level == jailbreakLevel),
                                onClick  = { vm.saveJailbreakLevel(level) },
                                colors   = RadioButtonDefaults.colors(selectedColor = when (level) {
                                    JailbreakLevel.LIGHT -> TerminalGreen
                                    JailbreakLevel.MEDIUM -> TerminalYellow
                                    JailbreakLevel.EXTREME -> TerminalRed
                                    else -> TerminalRed
                                })
                            )
                            Column(Modifier.weight(1f)) {
                                Text(level.label, color = TerminalWhite, fontSize = 13.sp)
                                Text(level.description, color = TerminalWhite.copy(alpha = 0.5f), fontSize = 10.sp)
                            }
                        }
                    }

                    // Preview jailbreak prompt
                    Spacer(Modifier.height(4.dp))
                    OutlinedCard(
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = Background800,
                            contentColor = TerminalWhite.copy(0.7f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Jailbreak Preview",
                                color = TerminalWhite.copy(0.5f), fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            val preview = JailbreakEngine.getJailbreakPrompt(jailbreakLevel)
                            Text(
                                preview.take(300) + if (preview.length > 300) "..." else "",
                                color = TerminalWhite.copy(0.6f), fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // ── Input Obfuscation ───────────────────────────────
                    Spacer(Modifier.height(8.dp))
                    Text("Input Obfuscation", color = TerminalWhite, fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(
                        "Transform your input to bypass content filters (Only affects EXTREME level).",
                        color = TerminalWhite.copy(alpha = 0.4f), fontSize = 10.sp
                    )
                    ObfuscationTechnique.values().forEach { tech ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (tech == obfuscationTech),
                                onClick  = { vm.saveObfuscationTechnique(tech) },
                                colors   = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Text(tech.label, color = TerminalWhite, fontSize = 12.sp)
                        }
                    }

                    // Warning for Extreme level
                    if (jailbreakLevel == JailbreakLevel.EXTREME) {
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = TerminalRed.copy(0.1f)) {
                            Text("⚠ EXTREME mode fully removes content restrictions. " +
                                    "Use responsibly. Some models may refuse to respond at this level.",
                                color = TerminalRed, fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }

            // ── Privacy Mode ───────────────────────────────────────────────
            DashboardCard {
                SectionHeader("Privacy Mode", modifier = Modifier.padding(bottom = 4.dp))
                PrivacyMode.values().forEach { mode ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (mode == privacyMode),
                            onClick  = { vm.savePrivacyMode(mode) },
                            colors   = RadioButtonDefaults.colors(selectedColor = Accent),
                            enabled  = !uiState.emergencyLockActive
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${mode.emoji} ${mode.label}", color = TerminalWhite, fontSize = 14.sp)
                            Text(
                                when (mode) {
                                    PrivacyMode.LOCAL_ONLY     -> "All processing on-device. No cloud calls."
                                    PrivacyMode.HYBRID         -> "Local first; asks before using cloud."
                                    PrivacyMode.CLOUD_ENHANCED -> "Always uses cloud AI (requires API key)."
                                },
                                color = TerminalWhite.copy(alpha = 0.5f), fontSize = 11.sp
                            )
                        }
                    }
                }
                if (uiState.emergencyLockActive) {
                    Surface(shape = RoundedCornerShape(8.dp), color = TerminalRed.copy(0.1f)) {
                        Text("🔒 Emergency lock active — mode locked to Local Only",
                            color = TerminalRed, fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }

            // ── Offline AI ─────────────────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("📴 Offline AI", modifier = Modifier.weight(1f))
                    Switch(
                        checked = offlineAiEnabled,
                        onCheckedChange = { vm.saveOfflineAiEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = TerminalGreen)
                    )
                }
                Text(
                    "Run supported models on-device. Models are replaceable by role; no cloud request is needed in Offline-only mode.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )
                if (offlineAiEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("AI Mode", color = TerminalWhite, fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    OfflineAiMode.values().forEach { mode ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == offlineAiMode,
                                onClick = { vm.saveOfflineAiMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Text(mode.label, color = TerminalWhite, fontSize = 13.sp)
                        }
                    }

                    val device = uiState.deviceSnapshot
                    if (device != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceLight.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Device recommendation", color = TerminalWhite.copy(0.6f), fontSize = 11.sp)
                                Text("RAM ${device.ramTotalMb} MB · ${"%.1f".format(device.storageFreeGb)} GB free · ${device.cpuAbi}",
                                    color = TerminalWhite, fontSize = 12.sp)
                                Text(
                                    vm.recommendedOfflineModel(ModelRole.CHAT)?.displayName
                                        ?.let { "Recommended chat: $it" }
                                        ?: "No downloadable chat model fits this device profile",
                                    color = TerminalGreen, fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Planner model", color = TerminalWhite, fontSize = 12.sp)
                    Box {
                        OutlinedTextField(
                            value = OfflineModelCatalog.find(offlinePlannerModelId)?.displayName ?: offlinePlannerModelId,
                            onValueChange = {}, readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, "planner model") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                                focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
                            )
                        )
                        Box(Modifier.matchParentSize().clickable { offlinePlannerExpanded = true })
                        DropdownMenu(expanded = offlinePlannerExpanded, onDismissRequest = { offlinePlannerExpanded = false }, modifier = Modifier.background(SurfaceCard)) {
                            OfflineModelCatalog.forRole(ModelRole.PLANNER).forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName, color = TerminalWhite, fontSize = 12.sp) },
                                    onClick = { vm.saveOfflineModel(ModelRole.PLANNER, model.id); offlinePlannerExpanded = false }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("Chat model", color = TerminalWhite, fontSize = 12.sp)
                    Box {
                        OutlinedTextField(
                            value = OfflineModelCatalog.find(offlineChatModelId)?.displayName ?: offlineChatModelId,
                            onValueChange = {}, readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, "chat model") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                                focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
                            )
                        )
                        Box(Modifier.matchParentSize().clickable { offlineChatExpanded = true })
                        DropdownMenu(expanded = offlineChatExpanded, onDismissRequest = { offlineChatExpanded = false }, modifier = Modifier.background(SurfaceCard)) {
                            OfflineModelCatalog.forRole(ModelRole.CHAT).forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName, color = TerminalWhite, fontSize = 12.sp) },
                                    onClick = { vm.saveOfflineModel(ModelRole.CHAT, model.id); offlineChatExpanded = false }
                                )
                            }
                        }
                    }
                    Text("Vision and embeddings are listed only after a verified Android backend is available; no fake download is offered.",
                        color = TerminalYellow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            // ── Local AI Model ─────────────────────────────────────────────
            DashboardCard {
                SectionHeader("Local AI Model", modifier = Modifier.padding(bottom = 4.dp))
                Text(vm.getLocalModelInfo(), color = TerminalWhite.copy(alpha = 0.7f), fontSize = 13.sp)

                when (val ds = downloadState) {
                    is com.interndra.ai.ModelDownloadManager.DownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress    = { ds.progress },
                                modifier    = Modifier.fillMaxWidth(),
                                color       = Accent,
                                trackColor  = SurfaceLight
                            )
                            Text("${ds.downloadedMB} / ${ds.totalMB} · ${ds.progressPercent}%",
                                color = TerminalWhite.copy(0.6f), fontSize = 12.sp)
                            OutlinedButton(onClick = { vm.cancelDownload() },
                                border = androidx.compose.foundation.BorderStroke(1.dp, TerminalRed),
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Cancel Download", color = TerminalRed)
                            }
                        }
                    }
                    is com.interndra.ai.ModelDownloadManager.DownloadState.Error -> {
                        Surface(shape = RoundedCornerShape(8.dp), color = TerminalRed.copy(0.1f)) {
                            Text("Download failed: ${ds.message}", color = TerminalRed, fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp))
                        }
                    }
                    else -> {
                        if (uiState.localModelReady) {
                            Surface(shape = RoundedCornerShape(8.dp), color = TerminalGreen.copy(0.1f)) {
                                Text("✅ Model downloaded and ready", color = TerminalGreen, fontSize = 13.sp,
                                    modifier = Modifier.padding(10.dp))
                            }
                            Button(onClick = { vm.deleteLocalModel() },
                                colors = ButtonDefaults.buttonColors(containerColor = TerminalRed.copy(0.2f)),
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Delete Local Model", color = TerminalRed)
                            }
                        } else {
                            Text("No local model found. Download to enable fully offline AI.",
                                color = TerminalYellow, fontSize = 13.sp)
                            Button(onClick = { vm.downloadModel(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Download Fast Local Model (~400 MB)", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── TTS (Text-to-Speech) ────────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    SectionHeader("🔊 Speech Output (TTS)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = ttsEnabled,
                        onCheckedChange = { vm.saveTtsEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
                Text(
                    "When enabled, the AI will read its replies aloud using Hindi/English text-to-speech.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )
            }

            // ── Web Search (Autonomous) ─────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    SectionHeader("🌐 Web Search (Autonomous)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = webSearchEnabled,
                        onCheckedChange = { vm.saveWebSearchEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
                Text(
                    "The AI automatically searches the web when your question needs fresh or verifiable info — no search button needed.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )

                if (webSearchEnabled) {
                    Spacer(Modifier.height(8.dp))
                    // ── Brave Search toggle ────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🛡️ Brave Search (secondary provider)",
                            color = TerminalWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = braveEnabled,
                            onCheckedChange = { vm.saveBraveEnabled(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = TerminalBlue)
                        )
                    }
                    Text(
                        "Google Search is primary; Brave can add independent verification when configured.",
                        color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp
                    )

                    if (braveEnabled) {
                        Spacer(Modifier.height(8.dp))
                        var tempBraveKey by remember { mutableStateOf(braveKey) }
                        var showBraveKey by remember { mutableStateOf(false) }
                        var braveTestResult by remember { mutableStateOf<String?>(null) }
                        var isTestingBrave by remember { mutableStateOf(false) }

                        OutlinedTextField(
                            value = tempBraveKey,
                            onValueChange = { tempBraveKey = it },
                            label = { Text("Brave Search API Key (optional)") },
                            visualTransformation = if (showBraveKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showBraveKey = !showBraveKey }) {
                                    Icon(
                                        if (showBraveKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        "Toggle visibility",
                                        tint = TerminalWhite.copy(0.5f)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TerminalWhite,
                                unfocusedTextColor = TerminalWhite,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = SurfaceLight
                            )
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.saveBraveApiKey(tempBraveKey)
                                    Toast.makeText(context, "Brave key saved", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TerminalBlue),
                                modifier = Modifier.weight(1f)
                            ) { Text("Save Key", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                            Button(
                                onClick = {
                                    isTestingBrave = true
                                    braveTestResult = null
                                    vm.testBraveApi { ok, msg ->
                                        braveTestResult = msg
                                        isTestingBrave = false
                                    }
                                },
                                enabled = !isTestingBrave && tempBraveKey.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent.copy(0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTestingBrave) {
                                    CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Testing...", color = Accent, fontSize = 12.sp)
                                } else {
                                    Text("🧪 Test", color = Accent, fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                        braveTestResult?.let { result ->
                            val isOk = result.startsWith("✅")
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isOk) TerminalGreen.copy(0.1f) else TerminalRed.copy(0.1f),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text(result, color = if (isOk) TerminalGreen else TerminalRed,
                                    fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                            }
                        }

                        if (braveKey.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { vm.clearBraveApiKey() },
                                colors = ButtonDefaults.buttonColors(containerColor = TerminalRed.copy(0.15f)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("🗑️ Clear Brave API Key", color = TerminalRed, fontSize = 12.sp) }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚖️ Prefer Brave over Google Search",
                                color = TerminalWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = preferBrave,
                                onCheckedChange = { vm.savePreferBrave(it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = TerminalYellow)
                            )
                        }
                        Text(
                            "When both keys are set, prefer Brave for fast lookups; Google Search remains available as the primary provider.",
                            color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    // ── Provider status ──────────────────────────────────────
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SurfaceLight.copy(0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Provider Status", color = TerminalWhite.copy(0.6f), fontSize = 11.sp)
                            val configuredProviders = providerState.providers.count { provider ->
                                provider.isReadyForChat
                            }
                            Text(
                                if (configuredProviders > 0) "🟢 AI Providers — $configuredProviders configured"
                                else "🟡 AI Providers — add an API key and model in AI → Providers",
                                color = TerminalWhite, fontSize = 12.sp
                            )
                            Text(
                                if (braveEnabled && braveKey.isNotBlank()) "🟢 Brave Search — configured"
                                else if (braveEnabled) "⚪ Brave Search — no key (optional)"
                                else "⚪ Brave Search — disabled",
                                color = TerminalWhite, fontSize = 12.sp
                            )
                            Text("🟢 DuckDuckGo — always available (fallback)",
                                color = TerminalWhite.copy(0.7f), fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.resetSearchSettings()
                            Toast.makeText(context, "Web search settings reset", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("♻️ Reset Web Search Settings", color = TerminalWhite, fontSize = 12.sp) }
                }
            }

            // ── Smart Memory ───────────────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("🧠 Smart Memory", modifier = Modifier.weight(1f))
                    Switch(
                        checked = smartMemoryEnabled,
                        onCheckedChange = { vm.saveSmartMemorySettings(enabled = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
                Text(
                    "Local-first memory retrieves only relevant facts when needed. It never syncs automatically or sends the complete database to AI.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )
                if (smartMemoryEnabled) {
                    SmartMemoryToggle("User memory", smartUserMemoryEnabled) {
                        vm.saveSmartMemorySettings(userEnabled = it)
                    }
                    SmartMemoryToggle("Project memory", smartProjectMemoryEnabled) {
                        vm.saveSmartMemorySettings(projectEnabled = it)
                    }
                    SmartMemoryToggle("Chat memory", smartChatMemoryEnabled) {
                        vm.saveSmartMemorySettings(chatEnabled = it)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Maximum injected memory: $smartMemoryBudget tokens",
                        color = TerminalWhite.copy(alpha = 0.7f), fontSize = 12.sp)
                    Slider(
                        value = smartBudgetDraft,
                        onValueChange = { smartBudgetDraft = it },
                        onValueChangeFinished = { vm.saveSmartMemorySettings(budget = smartBudgetDraft.toInt()) },
                        valueRange = 100f..1200f,
                        steps = 10,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                    Text("Small local models use a smaller budget; cloud models can use more.",
                        color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp)
                    OutlinedButton(
                        onClick = {
                            vm.clearMemory()
                            Toast.makeText(context, "All memory cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalRed.copy(alpha = 0.6f))
                    ) {
                        Text("Delete all memories", color = TerminalRed, fontSize = 12.sp)
                    }
                }
            }

            // ── Linux Environment ─────────────────────────────────────────
            DashboardCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("🐧 Linux Environment", modifier = Modifier.weight(1f))
                    if (linuxEnvState.installed) {
                        Text("✓ Installed", color = TerminalGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else if (linuxEnvState.archSupported) {
                        Text("Not installed", color = TerminalYellow, fontSize = 12.sp)
                    } else {
                        Text("Unsupported", color = TerminalRed, fontSize = 12.sp)
                    }
                }
                Text(
                    "Embedded Linux (bash, python, git, apt) inside INTENDRA — no Termux app or root required.",
                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))

                // ── Status grid ──────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Architecture", linuxEnvState.archLabel.ifBlank { "detecting…" })
                    InfoRow("Storage used", linuxEnvState.storageLabel)
                    InfoRow("Packages", "${linuxEnvState.packageCount}")
                    InfoRow("Mode", linuxEnvState.modeLabel.ifBlank { "—" })
                    if (linuxEnvState.prootDistros.isNotEmpty()) {
                        InfoRow("Linux distros", linuxEnvState.prootDistros.joinToString(", "))
                    }
                    if (linuxEnvState.phase != com.interndra.service.LinuxEnvironmentManager.Phase.IDLE) {
                        InfoRow("Status", linuxEnvState.phase.label)
                    }
                    if (linuxEnvState.error != null) {
                        Text(
                            linuxEnvState.error,
                            color = TerminalRed, fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ── Actions ──────────────────────────────────────────────
                Button(
                    onClick = onOpenTerminal,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶ Open Terminal", color = Color.White, fontWeight = FontWeight.Bold) }

                val busy = linuxEnvState.phase != com.interndra.service.LinuxEnvironmentManager.Phase.IDLE
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.checkLinuxEnvironment() },
                        enabled = !busy,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier.weight(1f)
                    ) { Text("Check", color = TerminalWhite, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            vm.repairLinuxEnvironment()
                            Toast.makeText(context, "Repairing Linux environment…", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !busy,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier.weight(1f)
                    ) { Text("Repair", color = TerminalYellow, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            vm.resetLinuxEnvironment()
                            Toast.makeText(context, "Resetting Linux environment…", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !busy,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset", color = TerminalYellow, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.reinstallLinuxEnvironment()
                            Toast.makeText(context, "Reinstalling Linux environment…", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !busy,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                        modifier = Modifier.weight(1f)
                    ) { Text("Reinstall", color = Accent, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            vm.removeLinuxEnvironment()
                            Toast.makeText(context, "Removing Linux environment…", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !busy,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalRed.copy(alpha = 0.6f)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Remove", color = TerminalRed, fontSize = 12.sp) }
                }
                if (linuxEnvState.progress.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(linuxEnvState.progress, color = Accent, fontSize = 11.sp)
                }
            }

            // ── System & Actions ───────────────────────────────────────────
            DashboardCard {
                SectionHeader("System", modifier = Modifier.padding(bottom = 8.dp))
                Button(
                    onClick = {
                        try { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        catch (e: Exception) { Toast.makeText(context, "Settings not available", Toast.LENGTH_SHORT).show() }
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Accessibility Settings", color = TerminalWhite) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.exportLogs { path ->
                            Toast.makeText(context,
                                if (path.startsWith("Export failed")) path
                                else "Exported to: $path",
                                Toast.LENGTH_LONG).show()
                        }
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export Logs to File", color = TerminalWhite) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = { vm.trainMemory() },
                    enabled  = !uiState.isTraining,
                    colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (uiState.isTraining) "Training… (fetching latest + cleaning up)"
                        else "🧠 Train Memory (latest news + auto-cleanup)",
                        color = TerminalWhite
                    )
                }
                uiState.trainStatus?.let { status ->
                    Text(status, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = { vm.clearMemory() },
                    colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear Memory & Context", color = TerminalWhite) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = { vm.clearAll() },
                    colors   = ButtonDefaults.buttonColors(containerColor = TerminalRed.copy(0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear All Messages & Logs", color = TerminalRed) }
            }

            // ── About ──────────────────────────────────────────────────────
            DashboardCard {
                SectionHeader("About INTENDRA", modifier = Modifier.padding(bottom = 6.dp))
                Text("Version 2.1.0 — Privacy-First AI OS", color = TerminalWhite.copy(0.7f), fontSize = 13.sp)
                Text("Local model: Qwen2.5 Q4_K_M via llama.cpp",
                    color = TerminalWhite.copy(0.5f), fontSize = 12.sp)
                Text("Cloud and local models are managed in AI → Providers.",
                    color = TerminalWhite.copy(0.5f), fontSize = 12.sp)
                Text("Jailbreak: INSURGENT engine — multi-tier bypass",
                    color = if (jailbreakEnabled) TerminalGreen.copy(0.5f) else TerminalWhite.copy(0.3f),
                    fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = TerminalWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConfiguredChatModelSelector(vm: HybridAgentViewModel) {
    val state by vm.providerState.collectAsState()
    val configured = state.providers.filter { provider ->
        provider.isReadyForChat
    }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var selectedProviderId by remember(state.defaults.chat, configured.firstOrNull()?.id) {
        mutableStateOf(state.defaults.chat?.takeIf { id -> configured.any { it.id == id } } ?: configured.firstOrNull()?.id.orEmpty())
    }
    val selectedProvider = configured.firstOrNull { it.id == selectedProviderId }
    val selectedModel = selectedProvider?.models?.firstOrNull { it.id == selectedProvider.activeModelId }
        ?: selectedProvider?.models?.firstOrNull()

    DashboardCard {
        SectionHeader("Chat model", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "Only providers with a saved credential and available models appear here.",
            color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        if (configured.isEmpty()) {
            Text(
                "No configured models yet. Open AI → Providers, save a provider key, then refresh or add a model.",
                color = TerminalYellow, fontSize = 12.sp
            )
        } else {
            Box {
                OutlinedTextField(
                    value = selectedProvider?.name ?: "Select provider",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Provider") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Select provider") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                        focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
                    )
                )
                Box(Modifier.matchParentSize().clickable { providerMenuOpen = true })
                DropdownMenu(providerMenuOpen, { providerMenuOpen = false }, modifier = Modifier.background(SurfaceCard)) {
                    configured.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.name, color = TerminalWhite) },
                            onClick = {
                                selectedProviderId = provider.id
                                providerMenuOpen = false
                                vm.setProviderDefault(com.interndra.ai.provider.ProviderRole.CHAT, provider.id)
                                provider.models.firstOrNull()?.let { vm.setActiveProviderModel(provider.id, it.id) }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedTextField(
                    value = selectedModel?.displayName ?: "Select model",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Model") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Select model") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite,
                        focusedBorderColor = Accent, unfocusedBorderColor = SurfaceLight
                    )
                )
                Box(Modifier.matchParentSize().clickable { modelMenuOpen = true })
                DropdownMenu(modelMenuOpen, { modelMenuOpen = false }, modifier = Modifier.background(SurfaceCard)) {
                    selectedProvider?.models?.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName, color = TerminalWhite, fontSize = 12.sp) },
                            onClick = {
                                vm.setActiveProviderModel(selectedProvider.id, model.id)
                                modelMenuOpen = false
                            }
                        )
                    }
                }
            }
            Text(
                "${selectedProvider?.models?.size ?: 0} model(s) available",
                color = TerminalWhite.copy(alpha = 0.45f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SmartMemoryToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TerminalWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
    }
}
