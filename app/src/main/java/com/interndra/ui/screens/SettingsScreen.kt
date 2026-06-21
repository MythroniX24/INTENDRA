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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.PrivacyMode
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import com.interndra.util.Constants

@Composable
fun SettingsScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val context       = LocalContext.current
    val apiKey        by vm.apiKey.collectAsState()
    val privacyMode   by vm.privacyMode.collectAsState()
    val uiState       by vm.uiState.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val downloadState by vm.downloadState.collectAsState()

    var tempKey  by remember { mutableStateOf(apiKey) }
    var expanded by remember { mutableStateOf(false) }

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

            // ── Cloud AI Model ─────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cloud AI Model", color = Accent, fontSize = 16.sp,
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

            // ── API Key ────────────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OpenRouter API Key", color = Accent, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Required for Cloud and Hybrid mode. Stored locally (encrypted DataStore).",
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
                        onClick = { vm.saveApiKey(tempKey); Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show() },
                        colors  = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save Key", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
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

                    // FIX: "Export Support Logs" now actually writes to disk
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

                    // ── Train Memory ─────────────────────────────────────
                    // Fetches fresh trending/news info into the Knowledge Vault
                    // and compresses old auto-trained entries — keeps memory
                    // useful and current without ever wiping pinned/manual notes.
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
                    Text("Cloud: OpenRouter (only when you allow it)",
                        color = TerminalWhite.copy(0.5f), fontSize = 12.sp)
                }
            }
        }
    }
}
