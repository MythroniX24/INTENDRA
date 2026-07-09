package com.interndra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.MemoryEntry
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryDashboardScreen(vm: HybridAgentViewModel, onOpenDrawer: () -> Unit = {}) {
    val allMemories  by vm.allMemories.collectAsState()
    val pinned       by vm.pinnedMemories.collectAsState()
    val uiState      by vm.uiState.collectAsState()

    var searchQuery      by remember { mutableStateOf("") }
    var searchResults    by remember { mutableStateOf<List<MemoryEntry>?>(null) }
    var selectedTab      by remember { mutableStateOf(0) }
    val kbController     = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().background(Background800)) {

        // ── Top bar ───────────────────────────────────────────────────────
        Surface(color = SurfaceCard, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TerminalWhite)
                }
                Column(Modifier.weight(1f)) {
                    Text("Memory Dashboard", color = TerminalWhite, fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${uiState.memoryCount} memories stored",
                        color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                IconButton(onClick = { vm.clearMemory() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all", tint = TerminalRed.copy(alpha = 0.7f))
                }
            }
        }

        // ── Search bar ────────────────────────────────────────────────────
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search memories...",
            accentColor = Accent,
            onSearch = {
                vm.searchMemories(searchQuery) { results -> searchResults = results }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ── Tab row ───────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = SurfaceCard,
            contentColor     = Accent
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; searchResults = null }) {
                Text("All (${allMemories.size})", modifier = Modifier.padding(vertical = 12.dp),
                    color = if (selectedTab == 0) Accent else TerminalWhite.copy(alpha = 0.6f))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; searchResults = null }) {
                Text("Pinned (${pinned.size})", modifier = Modifier.padding(vertical = 12.dp),
                    color = if (selectedTab == 1) Accent else TerminalWhite.copy(alpha = 0.6f))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("Search", modifier = Modifier.padding(vertical = 12.dp),
                    color = if (selectedTab == 2) Accent else TerminalWhite.copy(alpha = 0.6f))
            }
        }

        val displayList = when {
            searchResults != null -> searchResults!!
            selectedTab == 1     -> pinned
            else                 -> allMemories
        }

        if (displayList.isEmpty()) {
            EmptyState(
                emoji = "🧠",
                title = if (selectedTab == 1) "No pinned memories yet"
                        else if (searchResults != null) "No results for \"$searchQuery\""
                        else "No memories yet.",
                subtitle = if (selectedTab == 0 && searchResults == null)
                    "Successful commands will be remembered automatically." else null
            )
        } else {
            LazyColumn(
                modifier        = Modifier.fillMaxSize(),
                contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayList, key = { it.id }) { memory ->
                    MemoryCard(
                        memory    = memory,
                        onPin     = { vm.pinMemory(memory) },
                        onArchive = { vm.archiveMemory(memory) },
                        onDelete  = { vm.deleteMemory(memory) },
                        onImportance = { score -> vm.setMemoryImportance(memory, score) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryEntry,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onImportance: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape  = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Importance indicator dot
                val impColor = when {
                    memory.importanceScore >= 8 -> TerminalRed
                    memory.importanceScore >= 6 -> TerminalYellow
                    else                        -> TerminalGreen
                }
                Box(Modifier.size(8.dp).background(impColor, shape = androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(memory.title.take(60), color = TerminalWhite, fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sdf.format(Date(memory.timestamp)),
                            color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp)
                        if (memory.actionType.isNotBlank()) {
                            Text("· ${memory.actionType}", color = Accent.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }

                if (memory.isPinned) {
                    Icon(Icons.Default.PushPin, contentDescription = null,
                        tint = TerminalYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }

                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        tint = TerminalWhite.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    // Full content
                    Text(memory.content.take(400), color = TerminalWhite.copy(alpha = 0.8f),
                        fontSize = 13.sp, lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace)

                    // Tags
                    if (memory.tags.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row {
                            memory.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                                Surface(shape = RoundedCornerShape(50), color = Accent.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(end = 4.dp)) {
                                    Text(tag.trim(), color = Accent, fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // Importance slider
                    Spacer(Modifier.height(8.dp))
                    Text("Importance: ${memory.importanceScore}/10",
                        color = TerminalWhite.copy(alpha = 0.5f), fontSize = 11.sp)
                    Slider(
                        value         = memory.importanceScore.toFloat(),
                        onValueChange = { onImportance(it.toInt()) },
                        valueRange    = 1f..10f,
                        steps         = 8,
                        colors        = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                        modifier      = Modifier.fillMaxWidth()
                    )

                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPin, modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (memory.isPinned) TerminalYellow else SurfaceLight)) {
                            Icon(if (memory.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                                contentDescription = null, tint = if (memory.isPinned) TerminalYellow else TerminalWhite,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (memory.isPinned) "Unpin" else "Pin",
                                color = if (memory.isPinned) TerminalYellow else TerminalWhite, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onArchive, modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight)) {
                            Text("Archive", color = TerminalWhite, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TerminalRed.copy(alpha = 0.5f))) {
                            Text("Delete", color = TerminalRed, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
