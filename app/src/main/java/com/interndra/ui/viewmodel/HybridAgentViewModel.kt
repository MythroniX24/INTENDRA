package com.interndra.ui.viewmodel

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.interndra.ai.*
import com.interndra.ai.workflow.WorkflowEngine
import com.interndra.ai.workflow.WorkflowPlanner
import com.interndra.ai.agents.AgentPool
import com.interndra.ai.graph.KnowledgeGraph
import com.interndra.ai.intelligence.DeviceIntelligence
import com.interndra.ai.rag.LocalRagEngine
import com.interndra.ai.timeline.TimelineEngine
import com.interndra.data.knowledge.KnowledgeRepository
import com.interndra.data.local.AgentDatabase
import com.interndra.data.local.AgentRepository
import com.interndra.data.model.*
import com.interndra.plugin.PluginManager
import com.interndra.search.WebSearchEngine
import com.interndra.service.AgentAccessibilityService
import com.interndra.service.SmartShell
import com.interndra.services.AutomationEngine
import com.interndra.services.AutomationWorker
import com.interndra.services.InterndraNotificationListener
import com.interndra.util.Constants
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore("interndra_prefs_v2")
private val API_KEY_PREF          = stringPreferencesKey("openrouter_api_key")
private val GEMINI_KEY_PREF       = stringPreferencesKey("gemini_api_key")
private val PROVIDER_PREF         = stringPreferencesKey("ai_provider")
private val MODEL_PREF            = stringPreferencesKey("selected_model")
private val GEMINI_MODEL_PREF     = stringPreferencesKey("gemini_model")
private val PRIVACY_MODE_PREF     = stringPreferencesKey("privacy_mode")
private val WORKSPACE_ID_PREF     = stringPreferencesKey("active_workspace_id")
private val JAILBREAK_ENABLED_PREF = booleanPreferencesKey("jailbreak_enabled")
private val JAILBREAK_LEVEL_PREF   = stringPreferencesKey("jailbreak_level")
private val OBFUSCATION_TECH_PREF  = stringPreferencesKey("obfuscation_technique")
// Phase 2 FIX: remember the user's previous mode so deactivateEmergencyLock()
// can restore it instead of stranding them on LOCAL_ONLY.
private val PRE_LOCK_MODE_PREF    = stringPreferencesKey("pre_lock_privacy_mode")
// COMPILE FIX: must be booleanPreferencesKey, not stringPreferencesKey —
// we store Boolean values (true/false) in this key. With stringPreferencesKey
// the line `prefs[EMERGENCY_LOCK_PREF] = true` fails to compile with
// "The boolean literal does not conform to the expected type String".
private val EMERGENCY_LOCK_PREF   = booleanPreferencesKey("emergency_lock_active")

data class HybridUiState(
    val isLoading: Boolean                  = false,
    val a11yEnabled: Boolean                = false,
    val localModelReady: Boolean            = false,
    val lastAiSource: AiSource?             = null,
    val lastLatencyMs: Long                 = 0L,
    val lastModelUsed: String               = "",
    val privacyMode: PrivacyMode            = PrivacyMode.HYBRID,
    val pendingConfirmation: ConfirmationRequest?   = null,
    val pendingCloudConsent: CloudConsentRequest?   = null,
    val error: String?                      = null,
    val memoryCount: Int                    = 0,
    val knowledgeCount: Int                 = 0,
    val timelineCount: Int                  = 0,
    val emergencyLockActive: Boolean        = false,
    val isTraining: Boolean                 = false,
    val trainStatus: String?                = null,
    val activeWorkspaceId: Long             = 0L,
    val activeWorkspaceName: String         = "General",
    val deviceSnapshot: DeviceIntelligence.DeviceSnapshot? = null,
    // ── New fields ─────────────────────────────────────────────────────
    val aiProvider: Constants.AiProvider    = Constants.AiProvider.OPENROUTER,
    val geminiApiKey: String                = "",
    val selectedGeminiModel: String         = Constants.DEFAULT_GEMINI_MODEL,
    val jailbreakEnabled: Boolean           = false,
    val jailbreakLevel: JailbreakLevel      = JailbreakLevel.OFF,
    val obfuscationTechnique: ObfuscationTechnique = ObfuscationTechnique.NONE
)

data class ConfirmationRequest(
    val sessionId: String,
    val message: String,
    val commandSummary: String,
    val onConfirm: suspend () -> Unit
)

class HybridAgentViewModel(private val app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "HybridAgentVM"
    }

    // ── Core systems ──────────────────────────────────────────────────────
    private val db              = AgentDatabase.getInstance(app)
    val repo                    = AgentRepository(db)
    private val shell           = SmartShell(app)
    private val safety          = SafetyEngine()
    private val localEngine     = LocalAiEngine(app)
    val modelDownloader         = ModelDownloadManager(app)
    private val webSearch       = WebSearchEngine(db.dao())

    // ── New AI OS systems ─────────────────────────────────────────────────
    val knowledgeRepo           = KnowledgeRepository(db.dao(), app)
    val timelineEngine          = TimelineEngine(db.dao(), app)
    val ragEngine               = LocalRagEngine(app, knowledgeRepo)
    val memoryTrainer           = MemoryTrainer(knowledgeRepo, webSearch, db.dao())
    val knowledgeGraph          = KnowledgeGraph()
    val deviceIntelligence      = DeviceIntelligence(app)
    val pluginManager           = PluginManager(app)
    val automationEngine        = AutomationEngine(app)
    val agentPool               = AgentPool(viewModelScope)
    val workflowPlanner         = WorkflowPlanner()
    val workflowEngine          = WorkflowEngine(app, repo, shell, safety)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // ── State flows ───────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(HybridUiState())
    val uiState: StateFlow<HybridUiState> = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadManager.DownloadState>(ModelDownloadManager.DownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadManager.DownloadState> = _downloadState.asStateFlow()

    private val _ragResults = MutableStateFlow<List<KnowledgeEntry>>(emptyList())
    val ragResults: StateFlow<List<KnowledgeEntry>> = _ragResults.asStateFlow()

    private val _topConcepts = MutableStateFlow<List<String>>(emptyList())
    val topConcepts: StateFlow<List<String>> = _topConcepts.asStateFlow()

    // ── Persistent preferences ────────────────────────────────────────────
    val apiKey: StateFlow<String> = app.dataStore.data
        .map { it[API_KEY_PREF] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val geminiApiKey: StateFlow<String> = app.dataStore.data
        .map { it[GEMINI_KEY_PREF] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val aiProvider: StateFlow<Constants.AiProvider> = app.dataStore.data
        .map { runCatching { Constants.AiProvider.valueOf(it[PROVIDER_PREF] ?: Constants.AiProvider.OPENROUTER.name) }.getOrDefault(Constants.AiProvider.OPENROUTER) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.AiProvider.OPENROUTER)

    val selectedModel: StateFlow<String> = app.dataStore.data
        .map { it[MODEL_PREF] ?: Constants.DEFAULT_MODEL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_MODEL)

    val selectedGeminiModel: StateFlow<String> = app.dataStore.data
        .map { it[GEMINI_MODEL_PREF] ?: Constants.DEFAULT_GEMINI_MODEL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_GEMINI_MODEL)

    val jailbreakEnabled: StateFlow<Boolean> = app.dataStore.data
        .map { it[JAILBREAK_ENABLED_PREF] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val jailbreakLevel: StateFlow<JailbreakLevel> = app.dataStore.data
        .map { runCatching { JailbreakLevel.valueOf(it[JAILBREAK_LEVEL_PREF] ?: JailbreakLevel.OFF.name) }.getOrDefault(JailbreakLevel.OFF) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JailbreakLevel.OFF)

    val obfuscationTechnique: StateFlow<ObfuscationTechnique> = app.dataStore.data
        .map { runCatching { ObfuscationTechnique.valueOf(it[OBFUSCATION_TECH_PREF] ?: ObfuscationTechnique.NONE.name) }.getOrDefault(ObfuscationTechnique.NONE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ObfuscationTechnique.NONE)

    val privacyMode: StateFlow<PrivacyMode> = app.dataStore.data
        .map { runCatching { PrivacyMode.valueOf(it[PRIVACY_MODE_PREF] ?: PrivacyMode.HYBRID.name) }.getOrDefault(PrivacyMode.HYBRID) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PrivacyMode.HYBRID)

    val activeWorkspaceId: StateFlow<Long> = app.dataStore.data
        .map { it[WORKSPACE_ID_PREF]?.toLongOrNull() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ── DB-backed flows ───────────────────────────────────────────────────
    val messages: StateFlow<List<ChatMessage>> = activeWorkspaceId.flatMapLatest { wsId ->
        repo.getMessages(wsId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val terminalLogs: StateFlow<List<TerminalLog>> =
        repo.getRecentLogs(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allMemories: StateFlow<List<MemoryEntry>> =
        repo.getAllMemories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinnedMemories: StateFlow<List<MemoryEntry>> =
        repo.getPinnedMemories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workspaces: StateFlow<List<Workspace>> =
        repo.getAllWorkspaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val networkEvents: StateFlow<List<NetworkEvent>> =
        repo.getNetworkEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── New DB flows ──────────────────────────────────────────────────────
    val knowledgeEntries: StateFlow<List<KnowledgeEntry>> =
        db.dao().getAllKnowledgeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val timelineEntries: StateFlow<List<TimelineEntry>> =
        db.dao().getRecentTimelineEntries(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val automationRules: StateFlow<List<AutomationRule>> =
        db.dao().getAllAutomationRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plugins: StateFlow<List<PluginEntry>> =
        db.dao().getAllPlugins().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Init ──────────────────────────────────────────────────────────────
    init {
        tts = TextToSpeech(app, this)
        refreshStatus()

        viewModelScope.launch {
            val ready    = localEngine.isModelDownloaded()
            val memCount = repo.memoryCount()
            val knCount  = db.dao().knowledgeCount()
            val tlCount  = db.dao().timelineCount()
            _uiState.update { it.copy(
                localModelReady = ready,
                memoryCount     = memCount,
                knowledgeCount  = knCount,
                timelineCount   = tlCount
            )}
            if (ready) localEngine.loadModel()
        }

        viewModelScope.launch {
            activeWorkspaceId.collect { wsId ->
                repo.setWorkspace(wsId)
                _uiState.update { it.copy(activeWorkspaceId = wsId) }
            }
        }

        viewModelScope.launch {
            knowledgeEntries.collect { entries ->
                if (entries.isNotEmpty()) {
                    knowledgeGraph.rebuild(entries)
                    _topConcepts.value = knowledgeGraph.topConcepts(20).map { it.label }
                    _uiState.update { it.copy(knowledgeCount = entries.size) }
                }
            }
        }

        viewModelScope.launch {
            val activeTriggers = db.dao().getActiveTriggerRules()
            automationEngine.updateRuleList(activeTriggers)
        }

        pluginManager.registerBuiltInPlugins()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.ENGLISH)
            }
            isTtsReady = true
        }
    }

    // ── Preference save ───────────────────────────────────────────────────
    fun saveApiKey(key: String)           = viewModelScope.launch { app.dataStore.edit { it[API_KEY_PREF] = key } }
    fun saveGeminiApiKey(key: String)     = viewModelScope.launch { app.dataStore.edit { it[GEMINI_KEY_PREF] = key } }
    fun saveModel(model: String)          = viewModelScope.launch { app.dataStore.edit { it[MODEL_PREF] = model } }
    fun saveGeminiModel(model: String)    = viewModelScope.launch { app.dataStore.edit { it[GEMINI_MODEL_PREF] = model } }
    fun saveProvider(provider: Constants.AiProvider) = viewModelScope.launch {
        app.dataStore.edit { it[PROVIDER_PREF] = provider.name }
        _uiState.update { it.copy(aiProvider = provider) }
    }
    fun saveJailbreakEnabled(enabled: Boolean) = viewModelScope.launch {
        app.dataStore.edit { it[JAILBREAK_ENABLED_PREF] = enabled }
        JailbreakEngine.activeLevel = if (enabled) (jailbreakLevel.value) else JailbreakLevel.OFF
        _uiState.update { it.copy(jailbreakEnabled = enabled) }
    }
    fun saveJailbreakLevel(level: JailbreakLevel) = viewModelScope.launch {
        app.dataStore.edit { it[JAILBREAK_LEVEL_PREF] = level.name }
        JailbreakEngine.activeLevel = if (jailbreakEnabled.value) level else JailbreakLevel.OFF
        _uiState.update { it.copy(jailbreakLevel = level) }
    }
    fun saveObfuscationTechnique(technique: ObfuscationTechnique) = viewModelScope.launch {
        app.dataStore.edit { it[OBFUSCATION_TECH_PREF] = technique.name }
        JailbreakEngine.obfuscationTechnique = technique
        _uiState.update { it.copy(obfuscationTechnique = technique) }
    }
    fun savePrivacyMode(mode: PrivacyMode) = viewModelScope.launch {
        if (_uiState.value.emergencyLockActive) return@launch
        app.dataStore.edit { it[PRIVACY_MODE_PREF] = mode.name }
        _uiState.update { it.copy(privacyMode = mode) }
    }

    fun switchWorkspace(workspaceId: Long, workspaceName: String) = viewModelScope.launch {
        app.dataStore.edit { it[WORKSPACE_ID_PREF] = workspaceId.toString() }
        _uiState.update { it.copy(activeWorkspaceId = workspaceId, activeWorkspaceName = workspaceName) }
    }

    // ── Emergency privacy lock ────────────────────────────────────────────
    // Phase 2 FIX: previously deactivateEmergencyLock() only cleared the flag
    // but left privacyMode stuck on LOCAL_ONLY. Now we snapshot the user's
    // previous mode when activating, and restore it on deactivate.
    fun activateEmergencyLock() {
        val previousMode = privacyMode.value
        _uiState.update { it.copy(emergencyLockActive = true, privacyMode = PrivacyMode.LOCAL_ONLY) }
        viewModelScope.launch {
            app.dataStore.edit { prefs ->
                prefs[PRE_LOCK_MODE_PREF]  = previousMode.name
                prefs[PRIVACY_MODE_PREF]   = PrivacyMode.LOCAL_ONLY.name
                prefs[EMERGENCY_LOCK_PREF] = true
            }
        }
        Log.i(TAG, "Emergency privacy lock activated — previous mode ($previousMode) saved")
    }

    fun deactivateEmergencyLock() {
        _uiState.update { it.copy(emergencyLockActive = false) }
        viewModelScope.launch {
            val saved = app.dataStore.data
                .map { it[PRE_LOCK_MODE_PREF] }
                .firstOrNull()
            val restoreMode = saved?.let { runCatching { PrivacyMode.valueOf(it) }.getOrNull() }
                ?: PrivacyMode.HYBRID
            app.dataStore.edit { prefs ->
                prefs[PRIVACY_MODE_PREF]   = restoreMode.name
                prefs[EMERGENCY_LOCK_PREF] = false
                prefs.remove(PRE_LOCK_MODE_PREF)
            }
            _uiState.update { it.copy(privacyMode = restoreMode) }
            Log.i(TAG, "Emergency lock deactivated — restored to $restoreMode")
        }
    }

    /** Eagerly read the persisted emergency-lock flag so the UI is correct on cold start. */
    private val emergencyLockPersisted: StateFlow<Boolean> = app.dataStore.data
        .map { it[EMERGENCY_LOCK_PREF] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Main command handler ──────────────────────────────────────────────
    fun sendCommand(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || _uiState.value.isLoading) return

        val mode = privacyMode.value
        val key  = apiKey.value

        if (mode == PrivacyMode.CLOUD_ENHANCED && key.isBlank()) {
            _uiState.update { it.copy(error = "Cloud mode needs OpenRouter API key — set it in Settings") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repo.addUserMessage(trimmed)
            val placeholderId = repo.addAiPlaceholder()
            val session       = repo.newSession()
            val startMs       = System.currentTimeMillis()

            // ── Phase 5/6/7: try the workflow planner FIRST ─────────────────
            // If the user's request maps cleanly to a known workflow (WhatsApp
            // message, find PDFs, launch app, etc.), execute it directly with
            // structured narration — no need to round-trip through the LLM.
            // Falls through to the AI orchestrator if no confident match.
            try {
                val detected = workflowPlanner.detect(trimmed)
                if (detected != null && detected.confidence >= 0.75f) {
                    val workflow = workflowPlanner.plan(detected)
                    if (workflow != null) {
                        repo.log(session, LogType.INFO, "⚙️ Workflow detected: ${workflow.name} (confidence ${(detected.confidence * 100).toInt()}%)")
                        val narrationLines = mutableListOf<String>()
                        val result = workflowEngine.run(workflow, session) { level, msg ->
                            val prefix = when (level) {
                                WorkflowEngine.NarrationLevel.UNDERSTOOD       -> "🧠 Understood"
                                WorkflowEngine.NarrationLevel.DOING            -> "⚡ Doing"
                                WorkflowEngine.NarrationLevel.DONE             -> "✅ Done"
                                WorkflowEngine.NarrationLevel.FAILED           -> "❌ Failed"
                                WorkflowEngine.NarrationLevel.BLOCKED          -> "🚫 Blocked"
                                WorkflowEngine.NarrationLevel.NEEDS_PERMISSION -> "🔐 Needs permission"
                                WorkflowEngine.NarrationLevel.COMPLETE         -> "🎯 Complete"
                                WorkflowEngine.NarrationLevel.PARTIAL          -> "⚠️ Partial"
                            }
                            narrationLines.add("$prefix: $msg")
                            // COMPILE FIX: repo.log() is a suspend function —
                            // wrap in viewModelScope.launch so it can be called
                            // from this non-suspend narration lambda.
                            viewModelScope.launch { repo.log(session, LogType.INFO, "$prefix: $msg") }
                        }

                        val sb = StringBuilder()
                        sb.appendLine("## ⚙️ Workflow: ${workflow.name}")
                        sb.appendLine()
                        sb.appendLine("> ${workflow.description}")
                        sb.appendLine()
                        sb.appendLine("### Steps")
                        narrationLines.forEachIndexed { i, line -> sb.appendLine("${i + 1}. $line") }
                        sb.appendLine()
                        if (result.overallSuccess) {
                            sb.appendLine("> ✅ **Workflow completed successfully** in ${result.durationMs}ms " +
                                          "(${result.succeededSteps}/${result.stepResults.size} steps).")
                        } else {
                            sb.appendLine("> ⚠️ **Workflow finished with ${result.failedSteps} failed step(s)** " +
                                          "out of ${result.stepResults.size}.")
                        }
                        val outputs = result.stepResults.filter { it.success && it.output.isNotBlank() && it.output != "(no output)" }
                        if (outputs.isNotEmpty()) {
                            sb.appendLine()
                            sb.appendLine("### Output")
                            outputs.forEach { r ->
                                val label = workflow.steps.getOrNull(r.stepIndex)?.label ?: "Step ${r.stepIndex + 1}"
                                sb.appendLine("**$label**")
                                sb.append("```")
                                sb.appendLine(r.output.take(600))
                                sb.appendLine("```")
                            }
                        }

                        repo.updateAiMessage(placeholderId, sb.toString().trim())
                        _uiState.update { it.copy(isLoading = false) }

                        if (result.overallSuccess) {
                            repo.rememberSuccess(trimmed, workflow.name,
                                workflow.steps.map { it.command }, "WORKFLOW")
                            _uiState.update { it.copy(memoryCount = repo.memoryCount()) }
                        }
                        timelineEngine.recordCommand(
                            command = workflow.name,
                            output  = "workflow: ${result.succeededSteps}/${result.stepResults.size} ok",
                            success = result.overallSuccess,
                            durationMs = result.durationMs
                        )
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Workflow planner failed, falling back to AI: ${e.message}")
                repo.log(session, LogType.INFO, "⚠️ Workflow planner unavailable, using AI")
            }

            try {
                val cloudEngine  = CloudAiEngine(key, selectedModel.value)
                val geminiEngineVal = GeminiAiEngine(geminiApiKey.value, selectedGeminiModel.value)
                val orchestrator = AiOrchestrator(app, localEngine, cloudEngine, geminiEngineVal)

                // Set provider and jailbreak settings
                orchestrator.activeProvider = aiProvider.value
                orchestrator.jailbreakActive = jailbreakEnabled.value
                orchestrator.jailbreakLevel = jailbreakLevel.value

                // Apply obfuscation settings
                JailbreakEngine.obfuscationTechnique = obfuscationTechnique.value
                JailbreakEngine.activeLevel = if (jailbreakEnabled.value) jailbreakLevel.value else JailbreakLevel.OFF

                // ── RAG context augmentation ──────────────────────────────
                var augmentedInput = trimmed
                if (knowledgeEntries.value.isNotEmpty()) {
                    val ragCtx = ragEngine.buildAugmentedPrompt(trimmed)
                    if (ragCtx != trimmed) {
                        augmentedInput = ragCtx
                        repo.log(session, LogType.INFO, "📚 RAG: vault context injected")
                    }
                }

                // ── Web search ────────────────────────────────────────────
                // Phase 2 FIX: wrapped in withContext(Dispatchers.IO) — OkHttp's
                // synchronous execute() would otherwise block the main thread
                // (viewModelScope.launch defaults to Dispatchers.Main).
                // Phase 4: also fetch + extract page content for top results so
                // the AI can summarize the actual article, not just the snippet.
                var webSources: List<WebSearchEngine.SearchResult> = emptyList()
                if (!_uiState.value.emergencyLockActive && webSearch.shouldSearch(trimmed)) {
                    repo.log(session, LogType.INFO, "🔍 Web search for context...")
                    repo.logNetworkEvent("html.duckduckgo.com", "WebSearch")
                    val (sources, pageDigest) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val s = webSearch.search(trimmed)
                        val digest = webSearch.fetchAndExtract(s, maxPages = 2)
                        s to digest
                    }
                    webSources = sources
                    val ctx = webSearch.buildContext(sources)
                    val fullCtx = if (pageDigest.isNotEmpty()) "$ctx\n\n$pageDigest" else ctx
                    if (fullCtx.isNotEmpty()) {
                        augmentedInput = "$augmentedInput\n\n$fullCtx"
                        repo.log(session, LogType.INFO, "✅ Web: ${sources.size} sources, ${pageDigest.length} chars of page content")
                    }
                }

                // ── Route + parse ─────────────────────────────────────────
                repo.log(session, LogType.INFO, "🧠 Routing: ${mode.emoji} ${mode.label}")

                val memoryContext = repo.buildMemoryContext()
                val chatHistory  = repo.getChatHistory(limit = 16)
                val orchResult = orchestrator.process(
                    userInput   = augmentedInput,
                    memory      = memoryContext,
                    privacyMode = mode,
                    chatHistory = chatHistory,
                    onCloudConsentNeeded = { resumeCallback ->
                        _uiState.update { s ->
                            s.copy(pendingCloudConsent = CloudConsentRequest(
                                userInput         = trimmed,
                                reason            = "Local AI needs help. Send to Cloud AI (${selectedModel.value})?",
                                destinationDomain = Constants.OPENROUTER_DOMAIN,
                                onAllow  = { viewModelScope.launch { resumeCallback(true) } },
                                onDeny   = { viewModelScope.launch { resumeCallback(false) } }
                            ))
                        }
                    }
                )

                _uiState.update { it.copy(pendingCloudConsent = null) }

                val intent    = orchResult.intent
                val engResult = orchResult.engineResult

                if (engResult.source == AiSource.CLOUD || engResult.source == AiSource.FALLBACK) {
                    repo.logNetworkEvent(Constants.OPENROUTER_DOMAIN, "CloudAI", dataSentBytes = trimmed.length * 2)
                }

                _uiState.update { it.copy(
                    lastAiSource  = engResult.source,
                    lastLatencyMs = engResult.latencyMs,
                    lastModelUsed = engResult.modelUsed
                )}

                val fallbackSearchText = "$trimmed ${intent.action.replace('_', ' ')}"
                val commands = intent.commands.ifEmpty {
                    CommandRegistry.findAllMatches(fallbackSearchText)
                }
                val usingFallbackCommands = intent.commands.isEmpty() && commands.isNotEmpty()
                val safetyReports = safety.validateAll(commands)

                if (safety.hasBlocked(safetyReports)) {
                    val blocked = safetyReports.first { it.result == SafetyEngine.ValidationResult.BLOCKED }
                    repo.log(session, LogType.STATUS_FAIL, "🚫 BLOCKED: ${blocked.reason}")
                    repo.updateAiMessage(placeholderId, "⛔ Command blocked by Safety Engine:\n${blocked.reason}")
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                if (safety.hasConfirmRequired(safetyReports)) {
                    val confirmReport = safetyReports.first { it.result == SafetyEngine.ValidationResult.REQUIRES_CONFIRMATION }
                    val summary = commands.joinToString("\n") { "• ${it.description}" }
                    repo.updateAiMessage(placeholderId, buildAiReply(intent, orchResult.explanation, suppressSteps = usingFallbackCommands))
                    _uiState.update { it.copy(
                        isLoading = false,
                        pendingConfirmation = ConfirmationRequest(
                            sessionId      = session,
                            message        = confirmReport.reason,
                            commandSummary = summary,
                            onConfirm      = { executeCommands(session, trimmed, intent, commands) }
                        )
                    )}
                    return@launch
                }

                // ── Execute ───────────────────────────────────────────────
                val replyText = buildAiReply(intent, orchResult.explanation, suppressSteps = usingFallbackCommands) +
                    buildSourcesBlock(webSources)
                repo.updateAiMessage(placeholderId, replyText)
                if (!intent.reply.isNullOrBlank()) speak(intent.reply)
                if (commands.isNotEmpty()) executeCommands(session, trimmed, intent, commands, placeholderId)

                // ── Record to timeline ────────────────────────────────────
                timelineEngine.recordChat(
                    userMessage = trimmed,
                    aiReply     = intent.reply ?: replyText,
                    source      = engResult.source.name,
                    latencyMs   = System.currentTimeMillis() - startMs
                )

            } catch (e: Exception) {
                // Phase 2 FIX: e.message can be null — never show "null" to the user.
                val msg = e.message ?: e::class.simpleName ?: "Unknown error"
                val err = "Error: $msg"
                repo.updateAiMessage(placeholderId, err)
                repo.log(session, LogType.STATUS_FAIL, err)
                _uiState.update { it.copy(error = msg) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── Execute commands ──────────────────────────────────────────────────
    private suspend fun executeCommands(
        session: String, userInput: String,
        intent: AiIntent, commands: List<ShellCommand>,
        chatMessageId: Long? = null
    ) {
        if (!intent.triggerCondition.isNullOrBlank()) {
            val combinedCmd = commands.joinToString(" && ") { it.command }
            InterndraNotificationListener.addTrigger(intent.triggerCondition, combinedCmd)
            repo.log(session, LogType.INFO, "🔔 Trigger registered: ${intent.triggerCondition}")
            return
        }

        if (intent.delayMinutes != null && intent.delayMinutes > 0) {
            val wm = WorkManager.getInstance(app)
            commands.forEach { cmd ->
                val data = Data.Builder()
                    .putString("TYPE", cmd.type.name)
                    .putString("COMMAND", cmd.command)
                    .build()
                wm.enqueue(
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setInitialDelay(intent.delayMinutes, TimeUnit.MINUTES)
                        .setInputData(data)
                        .build()
                )
            }
            repo.log(session, LogType.INFO, "⏰ Scheduled ${commands.size} task(s) in ${intent.delayMinutes}m")
            return
        }

        val engine = HybridExecutionEngine(app, repo, shell, safety)
        var allSuccess = true

        val chatOutputLines = mutableListOf<String>()

        engine.execute(session, userInput, intent.copy(commands = commands)) { result ->
            if (!result.success) allSuccess = false
            val cmd   = commands.getOrNull(result.stepIndex)
            val label = cmd?.description?.ifBlank { null } ?: cmd?.command?.take(40) ?: "Command"

            if (result.success) {
                val outputSnippet = result.output.trim().take(600).ifBlank { "Done" }
                chatOutputLines.add("**$label**\n```\n$outputSnippet\n```")
            } else {
                val rawError = result.error.trim().take(200)
                val friendly = friendlyError(rawError)
                chatOutputLines.add("**$label** — $friendly")
            }

            viewModelScope.launch {
                val logType = if (result.success) LogType.STATUS_OK else LogType.STATUS_FAIL
                val logMsg  = if (result.success) "✅ ${result.output.take(200)}" else "❌ ${result.error.take(200)}"
                repo.log(session, logType, logMsg)
                timelineEngine.recordCommand(
                    command    = cmd?.command ?: "",
                    output     = if (result.success) result.output else result.error,
                    success    = result.success,
                    durationMs = 0L
                )
            }
        }

        // Phase 11 FIX: removed the artificial `delay(200)` — engine.execute
        // runs its callback synchronously inside its forEachIndexed loop, so
        // all results are already collected by the time we reach this point.
        if (chatMessageId != null && chatOutputLines.isNotEmpty()) {
            val allOutput = chatOutputLines.joinToString("\n\n")
            val existing  = repo.getMessageText(chatMessageId) ?: ""
            val updated   = if (existing.isBlank()) allOutput
                            else "$existing\n\n$allOutput"
            repo.updateAiMessage(chatMessageId, updated)
        }

        if (allSuccess && commands.isNotEmpty()) {
            repo.rememberSuccess(userInput, intent.action, commands, _uiState.value.lastAiSource?.name ?: "UNKNOWN")
            val count = repo.memoryCount()
            _uiState.update { it.copy(memoryCount = count) }
        }
    }

    // ── Confirmation actions ──────────────────────────────────────────────
    fun confirmAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isLoading = true) }
        viewModelScope.launch {
            try { pending.onConfirm() }
            catch (e: Exception) { _uiState.update { it.copy(error = e.message) } }
            finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun denyAction() {
        val session = _uiState.value.pendingConfirmation?.sessionId ?: ""
        _uiState.update { it.copy(pendingConfirmation = null) }
        viewModelScope.launch {
            if (session.isNotBlank()) repo.log(session, LogType.INFO, "User cancelled action")
        }
    }

    fun allowCloudConsent() = viewModelScope.launch {
        _uiState.value.pendingCloudConsent?.onAllow?.invoke()
        _uiState.update { it.copy(pendingCloudConsent = null) }
    }

    fun denyCloudConsent() {
        _uiState.value.pendingCloudConsent?.onDeny?.invoke()
        _uiState.update { it.copy(pendingCloudConsent = null) }
    }

    // ── Memory management ─────────────────────────────────────────────────
    fun pinMemory(memory: MemoryEntry)   = viewModelScope.launch { repo.pinMemory(memory.id, !memory.isPinned) }
    fun archiveMemory(memory: MemoryEntry) = viewModelScope.launch { repo.archiveMemory(memory.id) }
    fun deleteMemory(memory: MemoryEntry) = viewModelScope.launch {
        repo.deleteMemory(memory)
        _uiState.update { it.copy(memoryCount = repo.memoryCount()) }
    }
    fun setMemoryImportance(memory: MemoryEntry, score: Int) = viewModelScope.launch {
        repo.setMemoryImportance(memory.id, score)
    }
    fun searchMemories(query: String, onResult: (List<MemoryEntry>) -> Unit) = viewModelScope.launch {
        onResult(repo.searchMemories(query))
    }
    fun clearMemory() = viewModelScope.launch {
        repo.clearMemory()
        _uiState.update { it.copy(memoryCount = 0) }
    }

    // ── Knowledge Vault management ────────────────────────────────────────
    fun addKnowledgeEntry(
        title: String, content: String,
        type: KnowledgeType = KnowledgeType.NOTE, tags: String = ""
    ) = viewModelScope.launch {
        val id = knowledgeRepo.addEntry(title, content, type, tags)
        timelineEngine.recordKnowledgeAdd(title, type.label, id)
        _uiState.update { it.copy(knowledgeCount = db.dao().knowledgeCount()) }
    }

    fun deleteKnowledgeEntry(entry: KnowledgeEntry) = viewModelScope.launch {
        knowledgeRepo.deleteEntry(entry)
        _uiState.update { it.copy(knowledgeCount = db.dao().knowledgeCount()) }
    }

    fun pinKnowledgeEntry(id: Long, pinned: Boolean) = viewModelScope.launch {
        knowledgeRepo.pinEntry(id, pinned)
    }

    fun addWebClip(url: String, title: String, snippet: String) = viewModelScope.launch {
        val id = knowledgeRepo.addWebClip(url, title, snippet)
        timelineEngine.recordKnowledgeAdd(title, KnowledgeType.WEB_CLIP.label, id)
    }

    fun trainMemory() {
        if (_uiState.value.isTraining) return
        viewModelScope.launch {
            _uiState.update { it.copy(isTraining = true, trainStatus = "Training…") }
            try {
                val result = memoryTrainer.train()
                _uiState.update { it.copy(
                    isTraining     = false,
                    trainStatus    = result.summary(),
                    knowledgeCount = db.dao().knowledgeCount()
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isTraining  = false,
                    trainStatus = "❌ Training failed: ${e.message}"
                )}
            }
        }
    }

    fun runRagSearch(query: String, onDone: (() -> Unit)? = null) = viewModelScope.launch {
        val chunks = ragEngine.retrieve(query)
        val entryIds = chunks.map { it.entryId }.toSet()
        _ragResults.value = knowledgeEntries.value.filter { it.id in entryIds }
        onDone?.invoke()
    }

    // ── Timeline ──────────────────────────────────────────────────────────
    fun purgeOldTimeline(days: Int) = viewModelScope.launch {
        timelineEngine.purgeOlderThan(days)
        _uiState.update { it.copy(timelineCount = db.dao().timelineCount()) }
    }

    // ── Device Intelligence ───────────────────────────────────────────────
    fun refreshDeviceSnapshot() = viewModelScope.launch {
        val snapshot = deviceIntelligence.getSnapshot()
        _uiState.update { it.copy(deviceSnapshot = snapshot) }
    }

    // ── Workspace management ──────────────────────────────────────────────
    fun createWorkspace(name: String, emoji: String = "📁", color: String = "#00E5FF") = viewModelScope.launch {
        val id = repo.createWorkspace(name, emoji, color)
        switchWorkspace(id, name)
    }
    fun renameWorkspace(workspace: Workspace, newName: String) = viewModelScope.launch {
        repo.updateWorkspace(workspace.copy(name = newName))
        if (workspace.id == _uiState.value.activeWorkspaceId) _uiState.update { it.copy(activeWorkspaceName = newName) }
    }
    fun deleteWorkspace(workspace: Workspace) = viewModelScope.launch {
        repo.deleteWorkspace(workspace)
        if (workspace.id == _uiState.value.activeWorkspaceId) switchWorkspace(0L, "General")
    }
    fun pinWorkspace(workspace: Workspace) = viewModelScope.launch {
        repo.pinWorkspace(workspace, !workspace.isPinned)
    }

    // ── Network events ────────────────────────────────────────────────────
    fun clearNetworkEvents() = viewModelScope.launch { repo.clearNetworkEvents() }

    // ── Model management ──────────────────────────────────────────────────
    fun downloadModel(useSmall: Boolean = false) = viewModelScope.launch {
        modelDownloader.downloadModel(useSmall).collect { state ->
            _downloadState.value = state
            if (state is ModelDownloadManager.DownloadState.Complete) {
                val loaded = localEngine.loadModel()
                _uiState.update { it.copy(localModelReady = loaded) }
                timelineEngine.recordModelEvent("Model downloaded", LocalAiEngine.DEFAULT_MODEL_FILENAME)
            }
        }
    }
    fun cancelDownload()  { modelDownloader.cancel(); _downloadState.value = ModelDownloadManager.DownloadState.Idle }
    fun deleteLocalModel() {
        localEngine.unload()
        modelDownloader.deleteModel()
        _uiState.update { it.copy(localModelReady = false) }
    }
    fun getLocalModelInfo() = localEngine.getModelInfo()

    // ── Chat management ───────────────────────────────────────────────────
    fun deleteMessage(msg: ChatMessage) = viewModelScope.launch {
        repo.deleteMessage(msg.id)
    }
    fun clearMessages() = viewModelScope.launch { repo.clearMessages() }
    fun clearAll()      = viewModelScope.launch { repo.clearMessages(); repo.clearLogs() }
    fun dismissError()  = _uiState.update { it.copy(error = null) }

    fun refreshStatus() {
        // Phase 2 FIX: previously read privacyMode.value which could still be
        // the default HYBRID before DataStore emitted on cold start. Now sync
        // a11y + model-ready + emergency-lock; privacyMode StateFlow self-corrects.
        // Phase 9 FIX: pass app context to isEnabled() so it does a real system
        // query instead of falling back to the stale _cachedEnabled flag.
        _uiState.update { it.copy(
            a11yEnabled         = AgentAccessibilityService.isEnabled(app),
            localModelReady     = localEngine.isModelDownloaded(),
            emergencyLockActive = emergencyLockPersisted.value
        )}
    }

    // ── Export logs ───────────────────────────────────────────────────────
    fun exportLogs(onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val logs     = terminalLogs.value
            val messages = messages.value
            val vault    = knowledgeEntries.value
            val sb       = StringBuilder()
            sb.appendLine("=== INTERNDRA Export ===")
            sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            sb.appendLine("Chat messages: ${messages.size} | Logs: ${logs.size} | Knowledge: ${vault.size}")
            sb.appendLine("\n=== Chat Messages ===")
            messages.forEach { msg ->
                sb.appendLine("[${msg.role}] ${msg.content}")
            }
            sb.appendLine("\n=== Terminal Logs ===")
            logs.forEach { log ->
                sb.appendLine("[${log.logType}] ${log.content}")
            }
            val exportDir = java.io.File(app.filesDir, "exports")
            exportDir.mkdirs()
            val file = java.io.File(exportDir, "interndra_export_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            onDone(file.absolutePath)
        } catch (e: Exception) {
            onDone("Export failed: ${e.message}")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────
    private fun speak(text: String) {
        if (!isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun friendlyError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "permission denied" in lower ->
                "Android blocks this without root access — can't reach that location."
            "no such file or directory" in lower ->
                "That path doesn't exist on this device."
            "not permitted" in lower ->
                "Android doesn't allow this operation for regular apps."
            "command not found" in lower ->
                "That tool isn't available on this device."
            else -> raw.ifBlank { "Couldn't complete that." }
        }
    }

    /**
     * Builds a compact "Sources" block using markdown link syntax.
     * The app renders AI messages through the native Compose RichMarkdownText
     * component, which turns `[title](url)` into a tappable link — so the user
     * sees a clean clickable title, not a long raw URL cluttering the chat.
     */
    private fun buildSourcesBlock(sources: List<WebSearchEngine.SearchResult>): String {
        if (sources.isEmpty()) return ""
        val sb = StringBuilder("\n\n**🔗 Sources:**\n")
        sources.take(5).forEachIndexed { i, r ->
            val title = r.title.ifBlank { "Source ${i + 1}" }.take(80)
            sb.append("${i + 1}. [$title](${r.url})\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildAiReply(
        intent: AiIntent,
        explanation: String,
        suppressSteps: Boolean = false
    ): String {
        val sb = StringBuilder()

        if (!intent.reply.isNullOrBlank()) {
            sb.append(intent.reply)
        }

        if (intent.steps.isNotEmpty() && !suppressSteps) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("**Steps:**\n")
            intent.steps.forEachIndexed { i, step -> sb.append("${i + 1}. $step\n") }
        }

        if (sb.isEmpty() && suppressSteps) {
            sb.append("🔍 Checking...")
        }

        if (sb.isEmpty()) {
            sb.append("*Processing...*")
        }

        sb.append("\n\n*$explanation*")
        return sb.toString().trim()
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
        localEngine.unload()
    }
}
