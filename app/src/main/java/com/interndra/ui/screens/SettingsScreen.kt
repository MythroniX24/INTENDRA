package com.interndra.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ai.JailbreakEngine
import com.interndra.ai.JailbreakLevel
import com.interndra.ai.ObfuscationTechnique
import com.interndra.data.model.PrivacyMode
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.util.Constants

@Composable
fun SettingsScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val context       = LocalContext.current
    val apiKey        by vm.apiKey.collectAsState()
    val geminiKey     by vm.geminiApiKey.collectAsState()
    val provider      by vm.aiProvider.collectAsState()
    val privacyMode   by vm.privacyMode.collectAsState()
    val uiState       by vm.uiState.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val selectedGeminiModel by vm.selectedGeminiModel.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val jailbreakEnabled by vm.jailbreakEnabled.collectAsState()
    val jailbreakLevel by vm.jailbreakLevel.collectAsState()
    val obfuscationTech by vm.obfuscationTechnique.collectAsState()
    val ttsEnabled by vm.ttsEnabled.collectAsState()

    var tempKey  by remember { mutableStateOf(apiKey) }
    var tempGeminiKey by remember { mutableStateOf(geminiKey) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var geminiExpanded by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(ChatBg)) {

        Surface(color = CardSurface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }
                Text("Settings", color = TerminalWhite, fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── AI Provider ────────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("AI Provider", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Choose which AI provider to use for cloud requests.",
                        color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)

                    Constants.AiProvider.values().forEach { prov ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (prov == provider),
                                onClick  = { vm.saveProvider(prov) },
                                colors   = RadioButtonDefaults.colors(selectedColor = Accent),
                                enabled  = !uiState.emergencyLockActive
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${prov.emoji} ${prov.label}", color = TerminalWhite, fontSize = 14.sp)
                                Text(
                                    when (prov) {
                                        Constants.AiProvider.OPENROUTER -> "Access to 200+ models via OpenRouter API"
                                        Constants.AiProvider.GEMINI -> "Google's Gemini models (requires free API key)"
                                    },
                                    color = TerminalWhite.copy(alpha = 0.5f), fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Cloud AI Model (OpenRouter) ────────────────────────────────
            if (provider == Constants.AiProvider.OPENROUTER) {
                Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("OpenRouter AI Model", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Box {
                            OutlinedTextField(
                                value         = selectedModel,
                                onValueChange = { },
                                readOnly      = true,
                                label         = { Text("Selected Model") },
                                modifier      = Modifier.fillMaxWidth(),
                                trailingIcon  = { Icon(Icons.Default.ArrowDropDown, "dropdown") },
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = TerminalWhite,
                                    unfocusedTextColor   = TerminalWhite,
                                    focusedBorderColor   = Accent,
                                    unfocusedBorderColor = SurfaceLight
                                )
                            )
                            Box(Modifier.matchParentSize().clickable { expanded = true })
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                                modifier = Modifier.background(CardSurface)) {
                                Constants.FREE_MODELS.forEach { (label, modelValue) ->
                                    DropdownMenuItem(
                                        text    = { Text(label, color = TerminalWhite, fontSize = 13.sp) },
                                        onClick = { vm.saveModel(modelValue); expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── OpenRouter API Key ────────────────────────────────────────
                Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("OpenRouter API Key", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Required for Cloud and Hybrid mode. Get one at openrouter.ai/keys",
                            color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                        OutlinedTextField(
                            value         = tempKey,
                            onValueChange = { tempKey = it },
                            label         = { Text("API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedTextColor     = TerminalWhite,
                                unfocusedTextColor   = TerminalWhite,
                                focusedBorderColor   = Accent,
                                unfocusedBorderColor = SurfaceLight
                            )
                        )
                        Button(
                            onClick = { vm.saveApiKey(tempKey); Toast.makeText(context, "OpenRouter key saved", Toast.LENGTH_SHORT).show() },
                            colors  = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save Key", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    }
                }
            }

            // ── Gemini Section ────────────────────────────────────────────
            if (provider == Constants.AiProvider.GEMINI) {
                Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Google Gemini Model 🟢", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Box {
                            OutlinedTextField(
                                value         = selectedGeminiModel,
                                onValueChange = { },
                                readOnly      = true,
                                label         = { Text("Selected Gemini Model") },
                                modifier      = Modifier.fillMaxWidth(),
                                trailingIcon  = { Icon(Icons.Default.ArrowDropDown, "dropdown") },
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = TerminalWhite,
                                    unfocusedTextColor   = TerminalWhite,
                                    focusedBorderColor   = Accent,
                                    unfocusedBorderColor = SurfaceLight
                                )
                            )
                            Box(Modifier.matchParentSize().clickable { geminiExpanded = true })
                            DropdownMenu(expanded = geminiExpanded, onDismissRequest = { geminiExpanded = false },
                                modifier = Modifier.background(CardSurface)) {
                                Constants.GEMINI_MODELS.forEach { (label, modelValue) ->
                                    DropdownMenuItem(
                                        text    = { Text(label, color = TerminalWhite, fontSize = 13.sp) },
                                        onClick = { vm.saveGeminiModel(modelValue); geminiExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Gemini API Key ────────────────────────────────────────────
                Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Google Gemini API Key", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Get a free API key at makersuite.google.com/app/apikey",
                            color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                        OutlinedTextField(
                            value         = tempGeminiKey,
                            onValueChange = { tempGeminiKey = it },
                            label         = { Text("Gemini API Key") },
                            visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            trailingIcon = {
                                IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                    Icon(
                                        if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        "Toggle visibility",
                                        tint = TerminalWhite.copy(0.5f)
                                    )
                                }
                            },
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedTextColor     = TerminalWhite,
                                unfocusedTextColor   = TerminalWhite,
                                focusedBorderColor   = Accent,
                                unfocusedBorderColor = SurfaceLight
                            )
                        )
                        Button(
                            onClick = { vm.saveGeminiApiKey(tempGeminiKey); Toast.makeText(context, "Gemini key saved", Toast.LENGTH_SHORT).show() },
                            colors  = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save Gemini Key", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                isTesting = true
                                geminiTestResult = null
                                vm.testGeminiApi { ok, msg ->
                                    geminiTestResult = msg
                                    isTesting = false
                                }
                            },
                            enabled  = !isTesting && tempGeminiKey.isNotBlank(),
                            colors   = ButtonDefaults.buttonColors(containerColor = Accent.copy(0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    color = Accent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Testing...", color = Accent)
                            } else {
                                Text("🧪 Test Gemini API", color = Accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                        geminiTestResult?.let { result ->
                            val isOk = result.startsWith("✅")
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isOk) TerminalGreen.copy(0.1f) else TerminalRed.copy(0.1f)
                            ) {
                                Text(
                                    result,
                                    color = if (isOk) TerminalGreen else TerminalRed,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── JAILBREAK SECTION ──────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠 Jailbreak Mode", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
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
                                containerColor = ChatBg,
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
            }

            // ── Privacy Mode ───────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Privacy Mode", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
            }

            // ── Local AI Model ─────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Local AI Model", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                                    Text("Download Model (~500 MB)", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── TTS (Text-to-Speech) ────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊 Speech Output (TTS)", color = Accent, fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
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
            }

            // ── System & Actions ───────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("System", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

                    Button(
                        onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            catch (e: Exception) { Toast.makeText(context, "Settings not available", Toast.LENGTH_SHORT).show() }
                        },
                        colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Accessibility Settings", color = TerminalWhite) }

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
                        Text(status, color = Accent, fontSize = 13.sp)
                    }

                    Button(
                        onClick  = { vm.clearMemory() },
                        colors   = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear Memory & Context", color = TerminalWhite) }

                    Button(
                        onClick  = { vm.clearAll() },
                        colors   = ButtonDefaults.buttonColors(containerColor = TerminalRed.copy(0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear All Messages & Logs", color = TerminalRed) }
                }
            }

            // ── About ──────────────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About INTERNDRA", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Version 2.1.0 — Privacy-First AI OS", color = TerminalWhite.copy(0.7f), fontSize = 13.sp)
                    Text("Local model: Qwen2.5 Q4_K_M via llama.cpp",
                        color = TerminalWhite.copy(0.5f), fontSize = 12.sp)
                    Text("Cloud: OpenRouter + Google Gemini (switchable)",
                        color = TerminalWhite.copy(0.5f), fontSize = 12.sp)
                    Text("Jailbreak: INSURGENT engine — multi-tier bypass",
                        color = if (jailbreakEnabled) TerminalGreen.copy(0.5f) else TerminalWhite.copy(0.3f),
                        fontSize = 12.sp)
                }
            }
        }
    }
}
