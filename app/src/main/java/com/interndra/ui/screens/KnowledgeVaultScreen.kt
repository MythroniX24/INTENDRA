package com.interndra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.KnowledgeEntry
import com.interndra.data.model.KnowledgeType
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeVaultScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit
) {
    val knowledge by vm.knowledgeEntries.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<KnowledgeType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filtered = remember(knowledge, searchQuery, selectedType) {
        knowledge.filter { entry ->
            val matchesSearch = searchQuery.isBlank() ||
                entry.title.contains(searchQuery, ignoreCase = true) ||
                entry.content.contains(searchQuery, ignoreCase = true) ||
                entry.tags.contains(searchQuery, ignoreCase = true)
            val matchesType = selectedType == null || entry.type == selectedType
            matchesSearch && matchesType && !entry.isArchived
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Knowledge Vault", color = TerminalWhite, fontWeight = FontWeight.Bold)
                        Text("${knowledge.size} entries", color = VaultGold, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TerminalWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = VaultGold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = VaultGold,
                contentColor = Background800
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add knowledge")
            }
        },
        containerColor = Background800
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── Search bar ────────────────────────────────────────────
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search vault…",
                accentColor = VaultGold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Type filter chips ─────────────────────────────────────
            FilterChipRow(
                items = KnowledgeType.entries.map {
                    FilterChipItem(it.name, "${it.emoji} ${it.label}")
                },
                selectedItem = selectedType?.name,
                onItemSelected = { id -> selectedType = KnowledgeType.entries.find { it.name == id } },
                accentColor = VaultGold
            )

            Spacer(Modifier.height(4.dp))

            // ── Entry list ────────────────────────────────────────────
            if (filtered.isEmpty()) {
                EmptyState(
                    emoji = "📚",
                    title = if (searchQuery.isBlank()) "Your vault is empty.\nTap + to add your first entry."
                            else "No results for \"$searchQuery\""
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        KnowledgeCard(
                            entry    = entry,
                            onPin    = { vm.pinKnowledgeEntry(entry.id, !entry.isPinned) },
                            onDelete = { vm.deleteKnowledgeEntry(entry) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    // ── Add dialog ────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddKnowledgeDialog(
            onDismiss = { showAddDialog = false },
            onAdd     = { title, content, type, tags ->
                vm.addKnowledgeEntry(title, content, type, tags)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun KnowledgeCard(
    entry: KnowledgeEntry,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    DashboardCard(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.type.emoji, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        color      = TerminalWhite,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.type.label, color = VaultGold, fontSize = 11.sp)
                        if (entry.wordCount > 0) Text("${entry.wordCount} words", color = TerminalWhite.copy(alpha = 0.4f), fontSize = 11.sp)
                    }
                }
                if (entry.isPinned) Icon(Icons.Default.PushPin, contentDescription = null, tint = VaultGold, modifier = Modifier.size(16.dp))
                IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (entry.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                        contentDescription = "Pin",
                        tint = if (entry.isPinned) VaultGold else TerminalWhite.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TerminalRed.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = SurfaceLight)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        entry.content.take(800),
                        color    = TerminalWhite.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    if (entry.tags.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            entry.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                                Surface(shape = RoundedCornerShape(50), color = VaultPurple.copy(alpha = 0.15f)) {
                                    Text("#${tag.trim()}", color = VaultPurple, fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                    if (entry.sourceUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("🔗 ${entry.sourceUrl.take(60)}", color = VaultCyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddKnowledgeDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, KnowledgeType, String) -> Unit
) {
    var title     by remember { mutableStateOf("") }
    var content   by remember { mutableStateOf("") }
    var tags      by remember { mutableStateOf("") }
    var type      by remember { mutableStateOf(KnowledgeType.NOTE) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfaceCard,
        title = { Text("Add to Knowledge Vault", color = TerminalWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title", color = TerminalWhite.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = vaultTextFieldColors(),
                    singleLine = true
                )
                // Type selector
                Box {
                    OutlinedTextField(
                        value = "${type.emoji} ${type.label}",
                        onValueChange = {},
                        label = { Text("Type", color = TerminalWhite.copy(alpha = 0.6f)) },
                        modifier = Modifier.fillMaxWidth().clickable { typeDropdownExpanded = true },
                        enabled = false,
                        colors = vaultTextFieldColors(),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, tint = VaultGold) }
                    )
                    DropdownMenu(
                        expanded  = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false },
                        containerColor = SurfaceCard
                    ) {
                        KnowledgeType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("${t.emoji} ${t.label}", color = TerminalWhite) },
                                onClick = { type = t; typeDropdownExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    label = { Text("Content", color = TerminalWhite.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    colors = vaultTextFieldColors()
                )
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text("Tags (comma-separated)", color = TerminalWhite.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = vaultTextFieldColors(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank() && content.isNotBlank()) onAdd(title, content, type, tags) },
                enabled = title.isNotBlank() && content.isNotBlank()
            ) { Text("Add", color = VaultGold, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TerminalWhite.copy(alpha = 0.6f)) }
        }
    )
}

@Composable
private fun vaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = VaultGold,
    unfocusedBorderColor = SurfaceLight,
    focusedTextColor     = TerminalWhite,
    unfocusedTextColor   = TerminalWhite,
    cursorColor          = VaultGold,
    disabledBorderColor  = SurfaceLight,
    disabledTextColor    = TerminalWhite
)
