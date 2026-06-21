package com.interndra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.LogType
import com.interndra.data.model.TerminalLog
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TerminalScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val logs      by vm.terminalLogs.collectAsState()
    val listState  = rememberLazyListState()

    // Auto-scroll to bottom on new log
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(Modifier.fillMaxSize().background(ChatBg)) {

        // ── Top bar ───────────────────────────────────────────────────────
        Surface(color = CardSurface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }
                Column(Modifier.weight(1f)) {
                    Text("Terminal Logs", color = TerminalWhite, fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${logs.size} entries", color = TerminalWhite.copy(alpha = 0.45f), fontSize = 11.sp)
                }
                // Scroll to bottom
                IconButton(onClick = { /* state handled by LaunchedEffect */ }) {
                    Icon(Icons.Default.ArrowDownward, "Scroll to bottom",
                        tint = TerminalWhite.copy(alpha = 0.5f))
                }
                // Clear logs
                IconButton(onClick = { vm.clearAll() }) {
                    Icon(Icons.Default.DeleteSweep, "Clear logs",
                        tint = TerminalRed.copy(alpha = 0.7f))
                }
            }
        }

        // ── Empty state ───────────────────────────────────────────────────
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Terminal, null,
                        tint = TerminalWhite.copy(alpha = 0.15f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No logs yet. Send a command to see activity.",
                        color = TerminalWhite.copy(alpha = 0.35f), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        LogLine(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(log: TerminalLog) {
    val color = when (log.logType) {
        LogType.INFO, LogType.AI_INPUT, LogType.AI_INTENT -> TerminalBlue
        LogType.STATUS_OK                                 -> TerminalGreen
        LogType.STATUS_FAIL                               -> TerminalRed
        LogType.EXECUTION_PLAN, LogType.COMMAND, LogType.TERMUX_CMD -> TerminalWhite
    }

    val prefix = when (log.logType) {
        LogType.STATUS_OK   -> "✓ "
        LogType.STATUS_FAIL -> "✗ "
        LogType.COMMAND, LogType.TERMUX_CMD -> "$ "
        else                -> "· "
    }

    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text       = sdf.format(Date(log.timestamp)),
            color      = TerminalWhite.copy(alpha = 0.25f),
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.width(58.dp).padding(top = 1.dp)
        )
        Text(
            text       = prefix + log.content,
            color      = color,
            fontFamily = FontFamily.Monospace,
            fontSize   = 12.sp,
            modifier   = Modifier.weight(1f),
            lineHeight = 17.sp
        )
    }
}
