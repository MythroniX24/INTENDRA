package com.interndra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.TimelineEntry
import com.interndra.data.model.TimelineEventType
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit
) {
    val timeline    by vm.timelineEntries.collectAsState()
    var filterType  by remember { mutableStateOf<TimelineEventType?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(timeline, filterType, searchQuery) {
        timeline.filter { entry ->
            val matchesType   = filterType == null || entry.type == filterType
            val matchesSearch = searchQuery.isBlank() ||
                entry.title.contains(searchQuery, ignoreCase = true) ||
                entry.detail.contains(searchQuery, ignoreCase = true)
            matchesType && matchesSearch
        }
    }

    val grouped = remember(filtered) {
        filtered.groupBy { entry ->
            val cal = Calendar.getInstance().apply { timeInMillis = entry.timestamp }
            "${cal.get(Calendar.YEAR)}-${String.format("%02d", cal.get(Calendar.MONTH)+1)}-${String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))}"
        }.entries.toList().sortedByDescending { it.key }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Timeline", color = TerminalWhite, fontWeight = FontWeight.Bold)
                        Text("${timeline.size} events recorded", color = TerminalBlue, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TerminalWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.purgeOldTimeline(30) }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Purge old", tint = TerminalWhite.copy(alpha = 0.5f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        containerColor = Background800
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── Search ────────────────────────────────────────────────
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search timeline…",
                accentColor = TerminalBlue,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Event type filter chips ───────────────────────────────
            FilterChipRow(
                items = TimelineEventType.entries.map {
                    FilterChipItem(it.name, "${it.emoji} ${it.label}")
                },
                selectedItem = filterType?.name,
                onItemSelected = { id -> filterType = TimelineEventType.entries.find { it.name == id } },
                accentColor = TerminalBlue
            )

            Spacer(Modifier.height(4.dp))

            // ── Timeline list ─────────────────────────────────────────
            if (filtered.isEmpty()) {
                EmptyState(
                    emoji = "📅",
                    title = if (searchQuery.isBlank()) "No timeline events yet.\nStart chatting or running commands!"
                            else "No events matching \"$searchQuery\""
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    grouped.forEach { (dateKey, entries) ->
                        item(key = "header_$dateKey") {
                            DateHeader(dateKey)
                        }
                        items(entries, key = { it.id }) { entry ->
                            TimelineEventCard(entry)
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(dateKey: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(Modifier.weight(1f), color = SurfaceLight)
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(50), color = SurfaceLight) {
            Text(
                formatDateHeader(dateKey),
                color    = TerminalWhite.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = SurfaceLight)
    }
}

@Composable
private fun TimelineEventCard(entry: TimelineEntry) {
    val accentColor = when (entry.type) {
        TimelineEventType.AI_CHAT         -> TerminalBlue
        TimelineEventType.SHELL_COMMAND   -> TerminalGreen
        TimelineEventType.KNOWLEDGE_ADD   -> VaultGold
        TimelineEventType.KNOWLEDGE_DELETE-> TerminalRed
        TimelineEventType.AUTOMATION_FIRED-> TerminalYellow
        TimelineEventType.MODEL_EVENT     -> VaultPurple
        TimelineEventType.OCR_RUN         -> VaultCyan
        TimelineEventType.FILE_IMPORT     -> VaultCyan
        TimelineEventType.SETTINGS_CHANGE -> TerminalWhite
        TimelineEventType.SECURITY_EVENT  -> TerminalRed
    }

    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestamp))
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Vertical timeline line + dot
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(accentColor)
            )
            Box(
                Modifier.width(2.dp).height(40.dp).background(SurfaceLight)
            )
        }

        Spacer(Modifier.width(12.dp))

        // Event content
        Card(
            modifier = Modifier.weight(1f),
            colors   = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape    = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.type.emoji, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.title,
                        color      = TerminalWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f)
                    )
                    Text(timeStr, color = TerminalWhite.copy(alpha = 0.35f), fontSize = 10.sp)
                }
                if (entry.outcome.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(entry.outcome.take(120), color = accentColor.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.durationMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("⏱ ${entry.durationMs}ms", color = TerminalWhite.copy(alpha = 0.3f), fontSize = 10.sp)
                }
            }
        }
    }
}

private fun formatDateHeader(dateKey: String): String {
    return try {
        val sdf     = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val display = SimpleDateFormat("EEEE, MMM d", Locale.US)
        val date    = sdf.parse(dateKey) ?: return dateKey
        val cal     = Calendar.getInstance().apply { time = date }
        val today   = Calendar.getInstance()
        when {
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR)        == today.get(Calendar.YEAR)         -> "Today"
            else -> display.format(date)
        }
    } catch (e: Exception) { dateKey }
}
