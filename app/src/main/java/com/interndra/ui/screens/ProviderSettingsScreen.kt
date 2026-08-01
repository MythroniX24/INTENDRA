package com.interndra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ai.provider.ProviderAuthType
import com.interndra.ai.provider.ProviderCapability
import com.interndra.ai.provider.ProviderConfig
import com.interndra.ai.provider.ProviderKind
import com.interndra.ai.provider.ProviderRole
import com.interndra.ai.provider.ProviderStatus
import com.interndra.ui.theme.Accent
import com.interndra.ui.theme.Background800
import com.interndra.ui.theme.Background900
import com.interndra.ui.theme.SurfaceCard
import com.interndra.ui.theme.SurfaceLight
import com.interndra.ui.theme.TerminalGreen
import com.interndra.ui.theme.TerminalRed
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.theme.TerminalYellow
import com.interndra.ui.viewmodel.HybridAgentViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    vm: HybridAgentViewModel,
    onBack: () -> Unit = {}
) {
    val state by vm.providerState.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ProviderFilter.ALL) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customProviderError by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var refreshingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.initializeProviderManager() }

    val visibleProviders = state.providers
        .filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
        .filter {
            when (filter) {
                ProviderFilter.ALL -> true
                ProviderFilter.CONNECTED -> it.status == ProviderStatus.CONNECTED
                ProviderFilter.LOCAL -> it.kind == ProviderKind.LOCAL
                ProviderFilter.CUSTOM -> it.kind == ProviderKind.CUSTOM
            }
        }
        .sortedBy { it.name.lowercase() }

    Scaffold(
        containerColor = Background800,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Providers", color = TerminalWhite, fontWeight = FontWeight.Bold)
                        Text("Secure connections and model defaults", color = TerminalWhite.copy(alpha = .5f), fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Settings, contentDescription = "Back to settings", tint = TerminalWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showCustomDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add provider", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background800)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, "Search providers", tint = TerminalWhite.copy(.6f)) },
                placeholder = { Text("Search providers", color = TerminalWhite.copy(.4f)) },
                colors = providerFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${visibleProviders.size} providers · ${state.defaults.chat?.let { "Chat: $it" } ?: "No chat default"}",
                color = TerminalWhite.copy(.55f), fontSize = 12.sp
            )
            operationMessage?.let { message ->
                Text(message, color = if (message.startsWith("✅")) TerminalGreen else TerminalYellow, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (visibleProviders.isEmpty()) {
                EmptyProviders(onAdd = { showCustomDialog = true })
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleProviders, key = { it.id }) { provider ->
                        ProviderCard(
                            provider = provider,
                            isRefreshing = refreshingId == provider.id,
                            isDefault = state.defaults.chat == provider.id,
                            onToggle = {
                                vm.toggleProvider(provider.id, it)
                                operationMessage = if (it) "✅ ${provider.name} enabled" else "✅ ${provider.name} disabled"
                            },
                            onTest = {
                                operationMessage = "Testing ${provider.name}…"
                                vm.testProvider(provider.id) { status ->
                                    operationMessage = when (status) {
                                        ProviderStatus.CONNECTED -> "✅ ${provider.name}: connected"
                                        ProviderStatus.CONFIGURED -> "✅ ${provider.name}: key saved — tap Test to verify"
                                        ProviderStatus.NOT_CONFIGURED -> "⚠️ ${provider.name}: API key not configured"
                                        ProviderStatus.AUTHENTICATION_FAILED, ProviderStatus.INVALID_API_KEY -> "⚠️ ${provider.name}: authentication failed"
                                        ProviderStatus.OFFLINE -> "⚠️ ${provider.name}: offline"
                                        ProviderStatus.RATE_LIMITED -> "⚠️ ${provider.name}: rate limited"
                                        else -> "⚠️ ${provider.name}: ${status.name.replace('_', ' ')}"
                                    }
                                }
                            },
                            onRefresh = {
                                refreshingId = provider.id
                                operationMessage = "Refreshing ${provider.name} models…"
                                vm.refreshProviderModels(provider.id) { result ->
                                    refreshingId = null
                                    operationMessage = result.fold(
                                        onSuccess = { models -> "✅ ${provider.name}: ${models.size} models loaded" },
                                        onFailure = { error -> "⚠️ ${provider.name}: ${error.message ?: "model refresh failed"}" }
                                    )
                                }
                            },
                            onSetDefault = {
                                if (provider.supportsManagedChat) {
                                    vm.setProviderDefault(ProviderRole.CHAT, provider.id)
                                }
                            },
                            onSelectModel = { modelId -> vm.setActiveProviderModel(provider.id, modelId) },
                            onSaveCredentials = { apiKey, headers, done ->
                                vm.saveProviderCredentials(provider.id, apiKey, headers) { validation ->
                                    if (validation.isValid) {
                                        operationMessage = "✅ ${provider.name}: API key saved securely"
                                    } else {
                                        operationMessage = "⚠️ ${provider.name}: ${validation.errors.joinToString("; ")}"
                                    }
                                    done(validation.isValid, validation.errors.joinToString("\n"))
                                }
                            },
                            onClearCredentials = { done ->
                                vm.clearProviderCredentials(provider.id) { validation ->
                                    operationMessage = if (validation.isValid) {
                                        "✅ ${provider.name}: credentials cleared"
                                    } else {
                                        "⚠️ ${provider.name}: ${validation.errors.joinToString("; ")}"
                                    }
                                    done(validation.isValid, validation.errors.joinToString("\n"))
                                }
                            },
                            onAddModel = { modelId, displayName, done ->
                                vm.addProviderModel(provider.id, modelId, displayName) { added ->
                                    operationMessage = if (added) {
                                        "✅ ${provider.name}: model added"
                                    } else {
                                        "⚠️ ${provider.name}: model already exists or could not be added"
                                    }
                                    done(added)
                                }
                            },
                            onDelete = { vm.deleteProvider(provider.id) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomProviderDialog(
            errorText = customProviderError,
            onDismiss = {
                customProviderError = null
                showCustomDialog = false
            },
            onSave = { name, url, key, auth, headers, org, project, version, endpoint, notes ->
                vm.saveCustomProvider(name, url, key, auth, headers, org, project, version, endpoint, notes) { validation ->
                    if (validation.isValid) {
                        customProviderError = null
                        showCustomDialog = false
                    } else {
                        customProviderError = validation.errors.joinToString("\n")
                    }
                }
            }
        )
    }
}

private enum class ProviderFilter(val label: String) { ALL("All"), CONNECTED("Connected"), LOCAL("Local"), CUSTOM("Custom") }

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isRefreshing: Boolean,
    isDefault: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onSetDefault: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveCredentials: (String, String, (Boolean, String) -> Unit) -> Unit,
    onClearCredentials: ((Boolean, String) -> Unit) -> Unit,
    onAddModel: (String, String, (Boolean) -> Unit) -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (provider.status) {
        ProviderStatus.CONNECTED, ProviderStatus.CONFIGURED -> TerminalGreen
        ProviderStatus.NOT_CONFIGURED -> TerminalYellow
        ProviderStatus.AUTHENTICATION_FAILED, ProviderStatus.INVALID_API_KEY -> TerminalRed
        else -> TerminalWhite.copy(.55f)
    }
    var showCredentials by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var credentialError by remember { mutableStateOf<String?>(null) }

    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceCard, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = if (provider.isLocal) TerminalGreen.copy(.12f) else Accent.copy(.12f)) {
                    Icon(
                        if (provider.isLocal) Icons.Default.Devices else Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (provider.isLocal) TerminalGreen else Accent,
                        modifier = Modifier.padding(9.dp).size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = TerminalWhite, fontWeight = FontWeight.SemiBold)
                    Text(
                    if (!provider.supportsManagedChat) {
                        "Built-in · adapter not available yet"
                    } else if (provider.isBuiltIn) {
                        "Built-in · ${provider.kind.name.lowercase()}"
                    } else {
                        "Custom · OpenAI-compatible"
                    },
                    color = TerminalWhite.copy(.5f), fontSize = 11.sp
                )
                }
                Text(if (provider.enabled) "Enabled" else "Disabled", color = if (provider.enabled) TerminalGreen else TerminalWhite.copy(.4f), fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Switch(checked = provider.enabled, onCheckedChange = onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusColor, RoundedCornerShape(50)))
                Spacer(Modifier.width(6.dp))
                Text(provider.status.name.replace('_', ' '), color = statusColor, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Text("${provider.modelCount} models", color = TerminalWhite.copy(.55f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(if (provider.apiKeyConfigured || provider.isLocal) "Credential ready" else "Needs key", color = TerminalWhite.copy(.5f), fontSize = 10.sp)
            }
            if (provider.models.isNotEmpty()) {
                var modelMenuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = provider.models.firstOrNull { it.id == provider.activeModelId }?.displayName
                            ?: provider.models.first().displayName,
                        onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text("Active chat model") }, modifier = Modifier.fillMaxWidth(),
                        colors = providerFieldColors()
                    )
                    Box(Modifier.matchParentSize().clickable { modelMenuOpen = true })
                    DropdownMenu(modelMenuOpen, { modelMenuOpen = false }, modifier = Modifier.background(SurfaceCard)) {
                        provider.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName, color = TerminalWhite, fontSize = 12.sp) },
                                onClick = { onSelectModel(model.id); modelMenuOpen = false }
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TerminalYellow.copy(alpha = .08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No model selected. Tap Add model to enter a model ID, or use Models to fetch the provider list.",
                        color = TerminalYellow,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            if (provider.capabilities.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    provider.capabilities.take(5).forEach { capability ->
                        Surface(shape = RoundedCornerShape(6.dp), color = SurfaceLight.copy(.45f)) {
                            Text(capability.shortLabel(), color = TerminalWhite.copy(.7f), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
            Divider(color = TerminalWhite.copy(.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { credentialError = null; showCredentials = true }, enabled = provider.enabled) {
                    Text(if (provider.apiKeyConfigured) "Edit API key" else "Add API key", color = Accent)
                }
                TextButton(onClick = { showAddModel = true }, enabled = provider.enabled) {
                    Text("Add model", color = TerminalWhite.copy(.75f))
                }
                TextButton(onClick = onTest, enabled = provider.enabled) { Text("Test", color = Accent) }
                TextButton(onClick = onRefresh, enabled = provider.enabled && !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(Modifier.size(15.dp), color = Accent, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Refresh models", tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp)); Text("Models", color = Accent)
                }
                TextButton(
                    onClick = onSetDefault,
                    enabled = provider.isReadyForChat
                ) {
                    Text(
                        when {
                            isDefault -> "Default ✓"
                            !provider.supportsManagedChat -> "Adapter pending"
                            !provider.apiKeyConfigured && !provider.isLocal -> "Needs key + model"
                            provider.models.isEmpty() && provider.activeModelId.isBlank() -> "Needs model"
                            else -> "Set default"
                        },
                        color = if (isDefault) TerminalGreen else TerminalWhite.copy(.7f)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!provider.isBuiltIn) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete provider", tint = TerminalRed.copy(.8f)) }
            }
        }
    }

    if (showCredentials) {
        CredentialDialog(
            provider = provider,
            errorText = credentialError,
            onDismiss = { showCredentials = false },
            onClearCredentials = { showClearConfirmation = true },
            onSave = { key, headers ->
                onSaveCredentials(key, headers) { success, error ->
                    if (success) showCredentials = false else credentialError = error.ifBlank { "Could not save credentials" }
                }
            }
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear credentials?") },
            text = { Text("This removes the encrypted API key and custom headers for ${provider.name} from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearCredentials { success, error ->
                        if (success) {
                            showClearConfirmation = false
                            showCredentials = false
                        } else {
                            showClearConfirmation = false
                            credentialError = error.ifBlank { "Could not clear credentials" }
                        }
                    }
                }) {
                    Text("Clear credentials", color = TerminalRed)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") } }
        )
    }
    if (showAddModel) {
        AddModelDialog(
            onDismiss = { showAddModel = false },
            onAdd = { id, displayName ->
                onAddModel(id, displayName) { added -> if (added) showAddModel = false }
            }
        )
    }
}

@Composable
private fun CredentialDialog(
    provider: ProviderConfig,
    errorText: String?,
    onDismiss: () -> Unit,
    onClearCredentials: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.name} credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the API key here. It is encrypted and stored only on this device. Leave it blank to keep the existing key.", fontSize = 11.sp, color = TerminalWhite.copy(.6f))
                errorText?.let { Text(it, color = TerminalRed, fontSize = 11.sp) }
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true,
                    label = { Text("API key") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle key") } },
                    colors = providerFieldColors()
                )
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it }, singleLine = true,
                    label = { Text("Headers JSON (optional)") }, modifier = Modifier.fillMaxWidth(), colors = providerFieldColors()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(key, headers) }) { Text("Save securely") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (provider.apiKeyConfigured) {
                    TextButton(onClick = onClearCredentials) {
                        Text("Clear credentials", color = TerminalRed)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = providerFieldColors())
                OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = providerFieldColors())
            }
        },
        confirmButton = { Button(onClick = { onAdd(id.trim(), name.trim().ifBlank { id.trim() }) }, enabled = id.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun ProviderCapability.shortLabel(): String = when (this) {
    ProviderCapability.STREAMING -> "Stream"
    ProviderCapability.VISION -> "Vision"
    ProviderCapability.TOOL_CALLING -> "Tools"
    ProviderCapability.JSON_MODE -> "JSON"
    ProviderCapability.IMAGE_GENERATION -> "Image"
    ProviderCapability.EMBEDDINGS -> "Embed"
    ProviderCapability.REASONING -> "Think"
    ProviderCapability.AUDIO -> "Audio"
}

@Composable
private fun EmptyProviders(onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No providers found", color = TerminalWhite, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Add a custom OpenAI-compatible endpoint or change the filter.", color = TerminalWhite.copy(.55f), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Add custom provider") }
        }
    }
}

@Composable
private fun CustomProviderDialog(
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, ProviderAuthType, String, String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("/v1/models") }
    var headers by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var auth by remember { mutableStateOf(ProviderAuthType.BEARER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenAI-compatible or custom HTTP endpoint", color = TerminalWhite.copy(.55f), fontSize = 11.sp)
                errorText?.takeIf { it.isNotBlank() }?.let { error ->
                    Surface(shape = RoundedCornerShape(8.dp), color = TerminalRed.copy(alpha = .12f)) {
                        Text(error, color = TerminalRed, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                    }
                }
                ProviderInput(name, { name = it }, "Provider name")
                ProviderInput(url, { url = it }, "Base URL")
                ProviderInput(endpoint, { endpoint = it }, "Model endpoint path")
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("API key (optional for local)") }, singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle key") } },
                    modifier = Modifier.fillMaxWidth(), colors = providerFieldColors()
                )
                Text("Authentication", fontSize = 12.sp, color = TerminalWhite.copy(.7f))
                Row {
                    ProviderAuthType.entries.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(74.dp)) {
                            RadioButton(selected = auth == item, onClick = { auth = item })
                            Text(item.name.take(7), fontSize = 9.sp)
                        }
                    }
                }
                ProviderInput(headers, { headers = it }, "Headers JSON (optional)")
                ProviderInput(organization, { organization = it }, "Organization ID (optional)")
                ProviderInput(project, { project = it }, "Project ID (optional)")
                ProviderInput(version, { version = it }, "API version (optional)")
                ProviderInput(notes, { notes = it }, "Notes (optional)")
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, url, key, auth, headers, organization, project, version, endpoint, notes) }, enabled = name.isNotBlank() && url.isNotBlank()) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProviderInput(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = providerFieldColors())
}

@Composable
private fun providerFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = TerminalWhite,
    unfocusedTextColor = TerminalWhite,
    focusedBorderColor = Accent,
    unfocusedBorderColor = SurfaceLight,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TerminalWhite.copy(.55f),
    cursorColor = Accent
)
