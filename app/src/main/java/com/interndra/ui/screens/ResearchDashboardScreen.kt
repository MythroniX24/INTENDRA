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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.KnowledgeEntry
import com.interndra.data.model.KnowledgeType
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

/**
 * ResearchDashboardScreen — aggregated view over the knowledge vault.
 *
 * Shows:
 * - Stats bar: total entries, word count, type breakdown
 * - RAG search: query the vault with keyword retrieval
 * - Top concepts: terms that appear across multiple entries (Knowledge Graph)
 * - Recent research entries: type = RESEARCH
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchDashboardScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit
) {
    val knowledge     by vm.knowledgeEntries.collectAsState()
    val ragResults    by vm.ragResults.collectAsState()
    val topConcepts   by vm.topConcepts.collectAsState()
    var ragQuery      by remember { mutableStateOf("") }
    var isSearching   by remember { mutableStateOf(false) }

    val totalWords  = remember(knowledge) { knowledge.sumOf { it.wordCount } }
    val researchEntries = remember(knowledge) { knowledge.filter { it.type == KnowledgeType.RESEARCH } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Research Dashboard", color = TerminalWhite, fontWeight = FontWeight.Bold)
                        Text("${knowledge.size} entries · $totalWords words", color = VaultCyan, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TerminalWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        containerColor = Background800
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Stats row ─────────────────────────────────────────────
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.interndra.ui.components.StatCard("📚", knowledge.size.toString(), "Entries", VaultGold, Modifier.weight(1f))
                    com.interndra.ui.components.StatCard("💬", totalWords.toString(), "Words", VaultCyan, Modifier.weight(1f))
                    com.interndra.ui.components.StatCard("🔬", researchEntries.size.toString(), "Research", VaultPurple, Modifier.weight(1f))
                }
            }

            // ── Type breakdown ────────────────────────────────────────
            item {
                TypeBreakdownCard(knowledge)
            }

            // ── RAG search ────────────────────────────────────────────
            item {
                DashboardCard(title = "🔍 Semantic Search (RAG)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchBar(
                            query = ragQuery,
                            onQueryChange = { ragQuery = it },
                            placeholder = "Search your knowledge base…",
                            accentColor = VaultCyan,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (ragQuery.isNotBlank()) {
                                    isSearching = true
                                    vm.runRagSearch(ragQuery) { isSearching = false }
                                }
                            }
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = VaultCyan, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = VaultCyan)
                            }
                        }
                    }

                        if (ragResults.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Results (${ragResults.size})", color = VaultCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            ragResults.forEach { result ->
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = SurfaceLight
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(result.type.emoji, fontSize = 16.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(result.title, color = TerminalWhite, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(result.content.take(200), color = TerminalWhite.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Top concepts ──────────────────────────────────────────
            item {
                if (topConcepts.isNotEmpty()) {
                    DashboardCard(title = "🕸️ Top Concepts") {
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(topConcepts.take(15), key = { it }) { concept ->
                                    Surface(shape = RoundedCornerShape(50), color = VaultPurple.copy(alpha = 0.15f)) {
                                        Text(
                                            concept,
                                            color = VaultPurple,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            // ── Recent research ───────────────────────────────────────
            item {
                if (researchEntries.isNotEmpty()) {
                    Text("🔬 Recent Research", color = TerminalWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            items(
                if (researchEntries.isNotEmpty()) researchEntries.take(10) else emptyList(),
                key = { it.id }
            ) { entry ->
                CompactKnowledgeCard(entry)
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun TypeBreakdownCard(knowledge: List<KnowledgeEntry>) {
    val counts = KnowledgeType.entries.associateWith { type -> knowledge.count { it.type == type } }
        .filter { it.value > 0 }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("📊 By Type", color = TerminalWhite, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            counts.forEach { (type, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${type.emoji} ${type.label}", color = TerminalWhite.copy(alpha = 0.8f), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(count.toString(), color = VaultGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CompactKnowledgeCard(entry: KnowledgeEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape    = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.type.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = TerminalWhite, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.content.take(80), color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
