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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.data.model.Workspace
import com.interndra.ui.components.*
import com.interndra.ui.theme.*
import com.interndra.ui.viewmodel.HybridAgentViewModel

private val PRESET_WORKSPACES = listOf(
    Triple("Android Dev",  "🤖", "#00E5FF"),
    Triple("Research",     "🔬", "#B388FF"),
    Triple("Etsy Business","🛒", "#FF8A65"),
    Triple("Physics",      "⚛️", "#80DEEA"),
    Triple("Coding",       "💻", "#A5D6A7"),
    Triple("Personal",     "👤", "#EF9A9A")
)

@Composable
fun WorkspaceScreen(
    vm: HybridAgentViewModel,
    onOpenDrawer: () -> Unit = {},
    onWorkspaceSelected: (Workspace) -> Unit = {}
) {
    val workspaces by vm.workspaces.collectAsState()
    val uiState   by vm.uiState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newName          by remember { mutableStateOf("") }
    var newEmoji         by remember { mutableStateOf("📁") }
    var newColor         by remember { mutableStateOf("#00E5FF") }
    var editTarget       by remember { mutableStateOf<Workspace?>(null) }
    var renameText       by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor   = SurfaceCard,
            title            = { Text("New Workspace", color = TerminalWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("Name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = TerminalWhite,
                            unfocusedTextColor   = TerminalWhite,
                            focusedBorderColor   = Accent,
                            unfocusedBorderColor = SurfaceLight
                        )
                    )
                    Text("Emoji", color = TerminalWhite.copy(alpha = 0.6f), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("📁","🤖","🔬","💻","🛒","⚛️","📊","🔐","🌐","✍️").forEach { emoji ->
                            TextButton(
                                onClick = { newEmoji = emoji },
                                colors  = ButtonDefaults.textButtonColors(
                                    containerColor = if (newEmoji == emoji) Accent.copy(0.2f) else Color.Transparent
                                )
                            ) { Text(emoji, fontSize = 20.sp) }
                        }
                    }
                    Text("Quick presets", color = TerminalWhite.copy(alpha = 0.6f), fontSize = 12.sp)
                    PRESET_WORKSPACES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (pname, pemoji, pcolor) ->
                                OutlinedButton(
                                    onClick = { newName = pname; newEmoji = pemoji; newColor = pcolor },
                                    border  = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLight),
                                    contentPadding = PaddingValues(6.dp)
                                ) {
                                    Text("$pemoji $pname", color = TerminalWhite, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            vm.createWorkspace(newName, newEmoji, newColor)
                            showCreateDialog = false
                            newName = ""; newEmoji = "📁"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = TerminalWhite.copy(alpha = 0.6f))
                }
            }
        )
    }

    editTarget?.let { ws ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            containerColor   = SurfaceCard,
            title            = { Text("Rename Workspace", color = TerminalWhite) },
            text = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    label         = { Text("New name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = TerminalWhite,
                        unfocusedTextColor   = TerminalWhite,
                        focusedBorderColor   = Accent,
                        unfocusedBorderColor = SurfaceLight
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            vm.renameWorkspace(ws, renameText)
                            editTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("Cancel", color = TerminalWhite.copy(alpha = 0.6f))
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Background800)) {

        Surface(color = SurfaceCard, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, "Menu", tint = TerminalWhite)
                }
                Text("Workspaces", color = TerminalWhite, fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, "New workspace", tint = Accent)
                }
            }
        }

        // General workspace button
        Card(
            colors   = CardDefaults.cardColors(containerColor = if (uiState.activeWorkspaceId == 0L) Accent.copy(0.15f) else SurfaceCard),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            onClick  = { onWorkspaceSelected(Workspace(id = 0, name = "General", emoji = "💬")) }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💬", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("General", color = TerminalWhite, fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text("Default workspace", color = TerminalWhite.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                if (uiState.activeWorkspaceId == 0L) {
                    Surface(shape = RoundedCornerShape(50), color = Accent.copy(0.2f)) {
                        Text("Active", color = Accent, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }

        if (workspaces.isEmpty()) {
            EmptyState(
                emoji = "📋",
                title = "No workspaces yet.",
                subtitle = "Create one to separate your contexts.",
                action = {
                    Button(onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Create First Workspace")
                    }
                }
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(workspaces, key = { it.id }) { ws ->
                    WorkspaceCard(
                        workspace = ws,
                        isActive  = ws.id == uiState.activeWorkspaceId,
                        onSelect  = { onWorkspaceSelected(ws) },
                        onPin     = { vm.pinWorkspace(ws) },
                        onRename  = { editTarget = ws; renameText = ws.name },
                        onDelete  = { vm.deleteWorkspace(ws) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    isActive: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        colors   = CardDefaults.cardColors(containerColor = if (isActive) Accent.copy(0.12f) else SurfaceCard),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick  = onSelect
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(workspace.emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(workspace.name, color = TerminalWhite, fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    if (workspace.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.PushPin, null, tint = TerminalYellow, modifier = Modifier.size(14.dp))
                    }
                }
                if (workspace.description.isNotBlank()) {
                    Text(workspace.description, color = TerminalWhite.copy(0.5f), fontSize = 12.sp, maxLines = 1)
                }
            }
            if (isActive) {
                Surface(shape = RoundedCornerShape(50), color = Accent.copy(0.2f)) {
                    Text("Active", color = Accent, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "More", tint = TerminalWhite.copy(0.5f), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(SurfaceCard)) {
                    DropdownMenuItem(
                        text = { Text(if (workspace.isPinned) "Unpin" else "Pin", color = TerminalWhite) },
                        onClick = { onPin(); menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", color = TerminalWhite) },
                        onClick = { onRename(); menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = TerminalRed) },
                        onClick = { onDelete(); menuExpanded = false }
                    )
                }
            }
        }
    }
}
