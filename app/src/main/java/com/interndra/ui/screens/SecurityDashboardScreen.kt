package com.interndra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.NetworkEvent
import com.interndra.data.model.PrivacyMode
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SecurityDashboardScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val uiState      by vm.uiState.collectAsState()
    val networkEvents by vm.networkEvents.collectAsState()
    val privacyMode  by vm.privacyMode.collectAsState()

    Column(Modifier.fillMaxSize().background(Background800)) {

        // ── Top bar ───────────────────────────────────────────────────────
        Surface(color = SurfaceCard, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }
                Text("Security & Privacy", color = TerminalWhite, fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (uiState.emergencyLockActive) {
                    Icon(Icons.Default.Lock, null, tint = TerminalRed)
                }
            }
        }

        LazyColumn(
            modifier        = Modifier.fillMaxSize(),
            contentPadding  = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Emergency Lock ─────────────────────────────────────────────
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockPerson, null, tint = TerminalRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Emergency Privacy Lock", color = TerminalRed, fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Text(
                            if (uiState.emergencyLockActive)
                                "🔒 ACTIVE — All cloud features disabled. Local processing only."
                            else
                                "One-tap lock: disables all cloud AI, web search, and external requests.",
                            color = TerminalWhite.copy(alpha = 0.8f), fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                if (uiState.emergencyLockActive) vm.deactivateEmergencyLock()
                                else vm.activateEmergencyLock()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.emergencyLockActive) TerminalGreen else TerminalRed
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (uiState.emergencyLockActive) Icons.Default.LockOpen else Icons.Default.Lock,
                                null, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (uiState.emergencyLockActive) "Deactivate Lock" else "Activate Emergency Lock",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── Current Privacy Status ─────────────────────────────────────
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, tint = Accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Current Privacy State", color = Accent, fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }

                        StatusRow("Processing Mode",
                            "${privacyMode.emoji} ${privacyMode.label}",
                            when (privacyMode) {
                                PrivacyMode.LOCAL_ONLY     -> TerminalGreen
                                PrivacyMode.HYBRID         -> TerminalYellow
                                PrivacyMode.CLOUD_ENHANCED -> TerminalBlue
                            }
                        )
                        StatusRow("Local AI", if (uiState.localModelReady) "Ready" else "Not loaded",
                            if (uiState.localModelReady) TerminalGreen else TerminalRed)
                        StatusRow("Accessibility Service", if (uiState.a11yEnabled) "Enabled" else "Disabled",
                            if (uiState.a11yEnabled) TerminalGreen else TerminalYellow)
                        StatusRow("Emergency Lock", if (uiState.emergencyLockActive) "ACTIVE" else "Off",
                            if (uiState.emergencyLockActive) TerminalRed else TerminalGreen)
                        StatusRow("Cloud Requests (7d)", "${networkEvents.count { !it.wasBlocked }} sent",
                            if (networkEvents.isEmpty()) TerminalGreen else TerminalYellow)
                    }
                }
            }

            // ── Privacy Audit Checklist ────────────────────────────────────
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FactCheck, null, tint = Accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Privacy Audit", color = Accent, fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        AuditRow("Local files stay on device", true)
                        AuditRow("Notification content not logged", true)
                        AuditRow("API key stored in DataStore (encrypted)", true)
                        AuditRow("Shell commands safety-checked before run", true)
                        AuditRow("Cloud consent required in Hybrid mode", true)
                        AuditRow("Emergency lock available", true)
                        AuditRow("Network requests logged transparently", true)
                        AuditRow("Memory encrypted at rest (Room + Android Keystore)", uiState.localModelReady)
                    }
                }
            }

            // ── Network Transparency ───────────────────────────────────────
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NetworkCheck, null, tint = Accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Network Transparency", color = Accent, fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearNetworkEvents() }) {
                                Text("Clear", color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (networkEvents.isEmpty()) {
                            Text("No outgoing requests recorded.", color = TerminalWhite.copy(alpha = 0.4f),
                                fontSize = 13.sp)
                        } else {
                            Text("${networkEvents.size} request(s) in the last 7 days:",
                                color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
            }

            items(networkEvents.take(20), key = { it.id }) { event ->
                NetworkEventRow(event)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TerminalWhite.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = valueColor.copy(alpha = 0.15f)) {
            Text(value, color = valueColor, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun AuditRow(label: String, passed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
            null,
            tint = if (passed) TerminalGreen else TerminalYellow,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = TerminalWhite.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

@Composable
private fun NetworkEventRow(event: NetworkEvent) {
    val sdf = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceLight),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (event.wasBlocked) Icons.Default.Block else Icons.Default.CloudUpload,
                null,
                tint = if (event.wasBlocked) TerminalRed else TerminalBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(event.domain, color = TerminalWhite, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
                Text("${event.feature} · ${event.dataSentBytes}B · ${sdf.format(Date(event.timestamp))}",
                    color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp)
            }
            if (event.wasBlocked) {
                Surface(shape = RoundedCornerShape(50), color = TerminalRed.copy(alpha = 0.2f)) {
                    Text("BLOCKED", color = TerminalRed, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}
