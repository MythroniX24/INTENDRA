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
import com.interndra.agent.TerminalAgent
import com.interndra.agent.AgentActivity
import com.interndra.agent.AgentOrchestrator
import com.interndra.agent.AgentState
import com.interndra.agent.AgentQuestion
import com.interndra.agent.ComplexityClassifier
import com.interndra.agent.CommandPlanner
import com.interndra.agent.PreTaskQuestionFlow
import com.interndra.agent.QuestionAnswer
import com.interndra.agent.TerminalTool
import com.interndra.ai.*
import com.interndra.ai.model.*
import com.interndra.ai.provider.ProviderCapability
import com.interndra.ai.provider.ProviderConfig
import com.interndra.ai.provider.ProviderDefaults
import com.interndra.ai.provider.ProviderKind
import com.interndra.ai.provider.ProviderManager as AiProviderManager
import com.interndra.ai.provider.ProviderModel
import com.interndra.ai.provider.ProviderRole
import com.interndra.ai.provider.ProviderState
import com.interndra.ai.provider.ProviderStatus
import com.interndra.ai.provider.ProviderValidation
import com.interndra.ai.system.AiSystemHealthMonitor
import com.interndra.ai.tasks.TaskManager
import com.interndra.ai.tasks.TaskPlan
import com.interndra.ai.tasks.TaskStepResult
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
import com.interndra.search.*
import com.interndra.service.AgentAccessibilityService
import com.interndra.service.ShizukuManager
import com.interndra.service.ShizukuShell
import com.interndra.services.AutomationEngine
import com.interndra.services.AutomationWorker
import com.interndra.service.ShellExecutor
import com.interndra.service.PersistentShell
import com.interndra.service.ProotDistroManager
import com.interndra.service.TermuxBootstrapInstaller
import com.interndra.service.TermuxEnvironment
import com.interndra.service.LinuxEnvironmentManager
import com.interndra.service.TerminalBackend
import com.interndra.service.EmbeddedLinuxBackend
import com.interndra.terminal.TerminalSession
import com.interndra.services.InterndraNotificationListener
import com.interndra.util.Constants
import com.interndra.security.SensitiveDataRedactor
import com.interndra.ai.provider.ProviderSecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.firstOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val Context.dataStore by preferencesDataStore("interndra_prefs_v2")
// Legacy keys are read only for one-time migration. New runtime reads use
// Keystore-encrypted values below, then the legacy entries are deleted.
private val API_KEY_PREF          = stringPreferencesKey("openrouter_api_key")
private val GEMINI_KEY_PREF       = stringPreferencesKey("gemini_api_key")
private val API_KEY_ENCRYPTED_PREF = stringPreferencesKey("openrouter_api_key_keystore_v1")
private val GEMINI_KEY_ENCRYPTED_PREF = stringPreferencesKey("gemini_api_key_keystore_v1")
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
private val TTS_ENABLED_PREF      = booleanPreferencesKey("tts_enabled")
private val OFFLINE_AI_ENABLED_PREF = booleanPreferencesKey("offline_ai_enabled")
private val OFFLINE_AI_MODE_PREF    = stringPreferencesKey("offline_ai_mode")
private val OFFLINE_PLANNER_MODEL_PREF = stringPreferencesKey("offline_planner_model")
private val OFFLINE_CHAT_MODEL_PREF = stringPreferencesKey("offline_chat_model")
private val OFFLINE_REASONING_MODEL_PREF = stringPreferencesKey("offline_reasoning_model")
private val OFFLINE_VISION_MODEL_PREF = stringPreferencesKey("offline_vision_model")
private val OFFLINE_EMBEDDINGS_MODEL_PREF = stringPreferencesKey("offline_embeddings_model")
private val SMART_MEMORY_ENABLED_PREF = booleanPreferencesKey("smart_memory_enabled")
private val SMART_USER_MEMORY_PREF = booleanPreferencesKey("smart_user_memory_enabled")
private val SMART_PROJECT_MEMORY_PREF = booleanPreferencesKey("smart_project_memory_enabled")
private val SMART_CHAT_MEMORY_PREF = booleanPreferencesKey("smart_chat_memory_enabled")
private val SMART_MEMORY_BUDGET_PREF = stringPreferencesKey("smart_memory_token_budget")

// ── Active command tracking for chat UI (like Claude's command indicator) ──
enum class CommandStatus { RUNNING, SUCCESS, FAILED }

data class ActiveCommand(
    val id: Long,
    val command: String,
    val description: String,
    val status: CommandStatus,
    val output: String = "",
    val error: String = "",
    val startTime: Long = System.currentTimeMillis()
)

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
    val obfuscationTechnique: ObfuscationTechnique      = ObfuscationTechnique.NONE,
    val pluginCount: Int                                = 0
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
    private val safety          = SafetyEngine()
    private val legacySecretStore = ProviderSecretStore()
    private val localEngine     = LocalAiEngine(app)
    val modelDownloader         = ModelDownloadManager(app)
    private val webSearch       = WebSearchEngine(db.dao())

    // ── Autonomous web search system ──────────────────────────────────
    val searchPlanner    = SearchPlanner()
    val searchHistory    = SearchHistory()
    val searchCache      = SearchCache(db.dao())
    val webpageReader    = WebpageReader()
    val providerSettings = ProviderSettings(app)
    // New provider manager is independent from AI engines and from the
    // autonomous web-search resolver below. It owns provider metadata,
    // encrypted secrets, defaults, model sync, and custom endpoints.
    val aiProviderManager = AiProviderManager(app)
    val providerState: StateFlow<ProviderState> by lazy {
        safeStateFlow(aiProviderManager.state, ProviderState())
    }
    val providerManager = ProviderManager(app) {
        // Build the Gemini search provider lazily from current AI settings.
        val gKey = geminiApiKey.value
        if (gKey.isNotBlank()) GeminiSearchProvider(gKey, selectedGeminiModel.value) else null
    }
    val searchManager    = SearchManager(
        planner = searchPlanner,
        cache = searchCache,
        history = searchHistory,
        webpageReader = webpageReader,
        providerManager = providerManager
    )

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
    val workflowEngine          = WorkflowEngine(app, repo, ShellExecutor, safety)
    val taskManager              = TaskManager(viewModelScope) { command ->
        // TaskManager can run without a confirmation dialog. Never let a
        // confirmation-required command execute as an autonomous task.
        val report = safety.validate(command)
        if (report.result != SafetyEngine.ValidationResult.SAFE) {
            TaskStepResult(success = false, error = "Refused by SafetyEngine: ${report.reason}")
        } else {
            val result = ShellExecutor.runAsync(command)
            TaskStepResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
        }
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // ── Crash protection ─────────────────────────────────────────────────
    /** Prevents race condition: only one command executes at a time. */
    private val commandGate = AtomicBoolean(false)

    /** The currently running AI generation job — cancelled by stopGeneration(). */
    private var generationJob: kotlinx.coroutines.Job? = null

    /** Catches any unhandled coroutine exception — NEVER crashes silently. */
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "💥 Unhandled coroutine crash: ${throwable.message}", throwable)
        _uiState.update { it.copy(
            isLoading = false,
            error = "App error: ${throwable.message?.take(100) ?: "Unknown"}"
        )}
        // InterndraApplication.writeCrashLog(throwable, app.filesDir, app.getExternalFilesDir(null)) — class not found
    }

    // ── State flows ───────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(HybridUiState())
    val uiState: StateFlow<HybridUiState> = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadManager.DownloadState>(ModelDownloadManager.DownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadManager.DownloadState> = _downloadState.asStateFlow()

    private val _ragResults = MutableStateFlow<List<KnowledgeEntry>>(emptyList())
    val ragResults: StateFlow<List<KnowledgeEntry>> = _ragResults.asStateFlow()

    private val _topConcepts = MutableStateFlow<List<String>>(emptyList())
    val topConcepts: StateFlow<List<String>> = _topConcepts.asStateFlow()

    // ── Active commands (for chat command indicators) ─────────────────
    private val _activeCommands = MutableStateFlow<List<ActiveCommand>>(emptyList())
    val activeCommands: StateFlow<List<ActiveCommand>> = _activeCommands.asStateFlow()

    private var commandIdCounter = 0L
    private fun nextCommandId() = ++commandIdCounter

    // ── Agent state machine + live activity timeline ──────────────────
    // Drives the Claude-style "Working…" panel in the chat: the current
    // state (Analyzing / Planning / Searching / Executing / Verifying…) and
    // a live list of user-safe activity events for the current run.
    val agentOrchestrator = AgentOrchestrator(maxRetriesPerTool = 2, maxToolCallsPerRun = 12)
    val agentState: StateFlow<AgentState> = agentOrchestrator.state
    val agentActivity: StateFlow<List<AgentActivity>> = agentOrchestrator.activities

    /** Emit a live activity event + return its index (caller may want to update it). */
    private fun emitAgent(activity: AgentActivity) = agentOrchestrator.emit(activity)
    private fun setAgentState(state: AgentState) = agentOrchestrator.setState(state)

    // ── Interactive Questioning (Questioning Engine) ────────────────────
    private val _pendingQuestion = MutableStateFlow<AgentQuestion?>(null)
    val pendingQuestion: StateFlow<AgentQuestion?> = _pendingQuestion.asStateFlow()

    /**
     * Pre-task question flow (decide → pause → answer → resume, never re-asks).
     * Pure, so the full flow is unit-testable end-to-end.
     */
    private val preTaskFlow = PreTaskQuestionFlow()

    /** Deferred for the mid-task suspend-and-await question helper. */
    private var pendingAnswer: kotlinx.coroutines.CompletableDeferred<QuestionAnswer>? = null

    /** Suspend-until-answered helper for mid-task questions (spec §12). */
    suspend fun askQuestion(question: AgentQuestion): QuestionAnswer {
        _pendingQuestion.value = question
        agentOrchestrator.setState(AgentState.WAITING_FOR_USER)
        emitAgent(AgentActivity.Thinking("Waiting for your choice"))
        val deferred = kotlinx.coroutines.CompletableDeferred<QuestionAnswer>()
        pendingAnswer = deferred
        return deferred.await()
    }

    /** UI callback: the user answered the pending question (structured answer). */
    fun answerQuestion(answer: QuestionAnswer) {
        pendingAnswer?.complete(answer)
        pendingAnswer = null
        // Pre-task flow: record the answer + return the stored request so the
        // SAME task resumes with the answer in context (never re-asks).
        val resumeInput = preTaskFlow.onAnswered(answer)
        _pendingQuestion.value = null
        agentOrchestrator.setState(AgentState.RESUMING)
        emitAgent(AgentActivity.Verification("Choice received", success = true))
        if (!resumeInput.isNullOrBlank()) {
            viewModelScope.launch {
                delay(250)  // let the UI collapse the question card first
                sendCommand(resumeInput)
            }
        } else if (_uiState.value.isLoading.not()) {
            agentOrchestrator.complete()
        }
    }

    /** UI callback: the user cancelled the waiting task (spec §31). */
    fun cancelQuestion() {
        preTaskFlow.cancel()
        _pendingQuestion.value = null
        pendingAnswer?.complete(QuestionAnswer.Cancelled(""))
        pendingAnswer = null
        agentOrchestrator.reset()
        _uiState.update { it.copy(isLoading = false, error = null) }
        commandGate.set(false)
    }

    /** Format collected answers as context for the resumed task. */
    private fun questionContext(): String = preTaskFlow.contextBlock()

    // ── Workflow deduplication ───────────────────────────────────────
    // Prevents the same workflow from being executed repeatedly for similar
    // user inputs within a short time window.
    private data class LastWorkflowRun(
        val workflowName: String,
        val description: String,
        val timestampMs: Long
    )
    private var lastWorkflowRun: LastWorkflowRun? = null

    // ── Self-correction state (Phase 8) ───────────────────────────────
    // Whether the last command run succeeded after any automatic retries.
    private var lastCommandRunSuccess: Boolean? = null

    // ── Persistent preferences ────────────────────────────────────────────
    // UPGRADE: All stateIn() flows use `lazy {}` so that if DataStore or
    // Database access fails during ViewModel construction, the constructor
    // still completes and the error is logged on first access instead of
    // crashing the entire app.
    val apiKey: StateFlow<String> by lazy {
        safeStateFlow(
            app.dataStore.data.map { prefs ->
                legacySecretStore.decrypt(prefs[API_KEY_ENCRYPTED_PREF].orEmpty())
                    .ifBlank { prefs[API_KEY_PREF].orEmpty() }
            }, ""
        )
    }

    val geminiApiKey: StateFlow<String> by lazy {
        safeStateFlow(
            app.dataStore.data.map { prefs ->
                legacySecretStore.decrypt(prefs[GEMINI_KEY_ENCRYPTED_PREF].orEmpty())
                    .ifBlank { prefs[GEMINI_KEY_PREF].orEmpty() }
            }, ""
        )
    }

    val aiProvider: StateFlow<Constants.AiProvider> by lazy {
        safeStateFlow(app.dataStore.data.map {
            runCatching { Constants.AiProvider.valueOf(it[PROVIDER_PREF] ?: Constants.AiProvider.OPENROUTER.name) }
                .getOrDefault(Constants.AiProvider.OPENROUTER)
        }, Constants.AiProvider.OPENROUTER)
    }

    val selectedModel: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[MODEL_PREF] ?: Constants.DEFAULT_MODEL }, Constants.DEFAULT_MODEL)
    }

    val selectedGeminiModel: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[GEMINI_MODEL_PREF] ?: Constants.DEFAULT_GEMINI_MODEL }, Constants.DEFAULT_GEMINI_MODEL)
    }

    val jailbreakEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[JAILBREAK_ENABLED_PREF] ?: false }, false)
    }

    val jailbreakLevel: StateFlow<JailbreakLevel> by lazy {
        safeStateFlow(app.dataStore.data.map {
            runCatching { JailbreakLevel.valueOf(it[JAILBREAK_LEVEL_PREF] ?: JailbreakLevel.OFF.name) }
                .getOrDefault(JailbreakLevel.OFF)
        }, JailbreakLevel.OFF)
    }

    val obfuscationTechnique: StateFlow<ObfuscationTechnique> by lazy {
        safeStateFlow(app.dataStore.data.map {
            runCatching { ObfuscationTechnique.valueOf(it[OBFUSCATION_TECH_PREF] ?: ObfuscationTechnique.NONE.name) }
                .getOrDefault(ObfuscationTechnique.NONE)
        }, ObfuscationTechnique.NONE)
    }

    // ── Shizuku — elevated shell access ───────────────────────────────────
    val shizukuManager = ShizukuManager(app)
    val shizukuShell = ShizukuShell(app, shizukuManager)
    private val _shizukuAvailable = MutableStateFlow(false)
    val shizukuAvailable: StateFlow<Boolean> = _shizukuAvailable.asStateFlow()
    private val _shizukuAuthorized = MutableStateFlow(false)
    val shizukuAuthorized: StateFlow<Boolean> = _shizukuAuthorized.asStateFlow()
    private val _shizukuUid = MutableStateFlow(-1)
    val shizukuUid: StateFlow<Int> = _shizukuUid.asStateFlow()
    val shizukuPrivilegeLevel: String get() = shizukuManager.privilegeLevel
    val isShizukuElevated: Boolean get() = shizukuShell.isElevatedAvailable

    // Cached runtime capabilities; invalidated when Shizuku status changes.
    @Volatile private var cachedRuntimeCaps: AICommandRegistry.RuntimeCapabilities? = null

    // ── AI System Health Monitor ──────────────────────────────────────
    val healthMonitor = AiSystemHealthMonitor(app)

    // ── Embedded Termux Environment ─────────────────────────────────────
    val termuxBootstrapInstaller = TermuxBootstrapInstaller(app, shizukuShell)
    val termuxEnvironment = TermuxEnvironment(app, shizukuShell, termuxBootstrapInstaller, scope = viewModelScope)

    // ── PRoot-distro Manager (Full Linux Distros) ─────────────────────
    val prootDistroManager = ProotDistroManager(app, termuxEnvironment, shizukuShell)
    private val _prootDistroState = MutableStateFlow(ProotDistroManager.ProotDistroState())
    val prootDistroState: StateFlow<ProotDistroManager.ProotDistroState> = _prootDistroState.asStateFlow()

    /** Refresh proot-distro state and update UI. */
    fun refreshProotDistroState() = viewModelScope.launch(Dispatchers.IO) {
        val state = prootDistroManager.refreshState()
        _prootDistroState.value = state
    }

    // ── Linux environment lifecycle actions (Settings → Linux Environment) ──
    fun checkLinuxEnvironment() = viewModelScope.launch(Dispatchers.IO) {
        linuxEnvironmentManager.check()
    }

    fun repairLinuxEnvironment() = viewModelScope.launch(Dispatchers.IO) {
        linuxEnvironmentManager.repair { progress ->
            _uiState.update { it.copy(error = null) }
            Log.i(TAG, "🔧 Linux repair: $progress")
        }
        linuxEnvironmentManager.check()
    }

    fun resetLinuxEnvironment() = viewModelScope.launch(Dispatchers.IO) {
        linuxEnvironmentManager.reset { progress ->
            Log.i(TAG, "🔄 Linux reset: $progress")
        }
        linuxEnvironmentManager.check()
    }

    fun removeLinuxEnvironment() = viewModelScope.launch(Dispatchers.IO) {
        linuxEnvironmentManager.remove { progress ->
            Log.i(TAG, "🧹 Linux remove: $progress")
        }
        terminalAgent.syncModeFromEnvironment()
    }

    fun reinstallLinuxEnvironment() = viewModelScope.launch(Dispatchers.IO) {
        linuxEnvironmentManager.reinstall { progress ->
            Log.i(TAG, "📦 Linux reinstall: $progress")
        }
        terminalAgent.syncModeFromEnvironment()
        linuxEnvironmentManager.check()
    }

    /** Install a full Linux distro via proot-distro (e.g., "ubuntu", "debian"). */
    fun installLinuxDistro(distroName: String, onProgress: ((String) -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        onProgress?.invoke("📦 Installing proot-distro tool...")
        if (!_prootDistroState.value.isAvailable) {
            val installed = prootDistroManager.installProotDistro()
            if (!installed) {
                onProgress?.invoke("❌ Failed to install proot-distro. Ensure Termux environment is available.")
                return@launch
            }
        }
        onProgress?.invoke("🐧 Installing $distroName Linux distro...")
        prootDistroManager.installDistro(distroName, onProgress)
        refreshProotDistroState()
    }

    // ── Terminal Agent — uses PersistentShell (no Termux needed) ─────
    val terminalAgent = TerminalAgent(app, shizukuShell, termuxEnvironment, scope = viewModelScope)

    // ── TerminalBackend abstraction — the Agent/UI talk only to this ──
    // The embedded Linux runtime is replaceable: swap the implementation and
    // nothing in the Agent or Chat UI changes.
    val terminalBackend: TerminalBackend = EmbeddedLinuxBackend(terminalAgent)

    // ── Structured Terminal Tool — first-class agent tool (spec §1-§2) ──
    // Exposes terminal.execute / send_input / stop / background processes
    // with tool-call IDs, input/output schemas and registry descriptors.
    val terminalTool = TerminalTool(terminalBackend, terminalAgent)
    val terminalToolRegistry: com.interndra.ai.tools.ToolRegistry by lazy {
        com.interndra.ai.tools.ToolRegistry().apply {
            registerAll(terminalTool.toToolDescriptors())
        }
    }

    // ── Linux Environment Manager — status/check/repair/reset/remove ──
    val linuxEnvironmentManager = LinuxEnvironmentManager(
        app, termuxBootstrapInstaller, termuxEnvironment, prootDistroManager, shizukuShell
    )
    val linuxEnvState: StateFlow<LinuxEnvironmentManager.EnvironmentState> =
        linuxEnvironmentManager.state
    private val _terminalSessions = MutableStateFlow(terminalAgent.getSessionNames())
    val terminalSessions: StateFlow<List<String>> = _terminalSessions.asStateFlow()
    private val _activeTerminalSession = MutableStateFlow("default")
    val activeTerminalSession: StateFlow<String> = _activeTerminalSession.asStateFlow()

    fun createTerminalSession(name: String, workdir: String = "/") {
        terminalAgent.createSession(name, workdir)
        _terminalSessions.value = terminalAgent.getSessionNames()
    }

    fun renameTerminalSession(oldName: String, newName: String) {
        // Prevent naming conflicts
        if (newName != oldName && terminalAgent.getSessionNames().contains(newName)) return
        terminalAgent.renameSession(oldName, newName)
        _terminalSessions.value = terminalAgent.getSessionNames()
        if (_activeTerminalSession.value == oldName) {
            _activeTerminalSession.value = newName
        }
    }

    fun removeTerminalSession(name: String) {
        val remaining = _terminalSessions.value.size
        if (remaining <= 1) return // prevent removing the last session
        terminalAgent.removeSession(name)
        _terminalSessions.value = terminalAgent.getSessionNames()
        if (_activeTerminalSession.value == name) {
            _activeTerminalSession.value = "default"
        }
    }

    fun setActiveTerminalSession(name: String) {
        _activeTerminalSession.value = name
    }

    val privacyMode: StateFlow<PrivacyMode> by lazy {
        safeStateFlow(app.dataStore.data.map {
            runCatching { PrivacyMode.valueOf(it[PRIVACY_MODE_PREF] ?: PrivacyMode.HYBRID.name) }
                .getOrDefault(PrivacyMode.HYBRID)
        }, PrivacyMode.HYBRID)
    }

    val activeWorkspaceId: StateFlow<Long> by lazy {
        safeStateFlow(app.dataStore.data.map { it[WORKSPACE_ID_PREF]?.toLongOrNull() ?: 0L }, 0L)
    }

    // ── DB-backed flows ───────────────────────────────────────────────────
    val messages: StateFlow<List<ChatMessage>> by lazy {
        safeStateFlow(
            activeWorkspaceId.flatMapLatest { wsId -> repo.getMessages(wsId).distinctUntilChanged() },
            emptyList()
        )
    }

    val terminalLogs: StateFlow<List<TerminalLog>> by lazy {
        safeStateFlow(repo.getRecentLogs(200), emptyList())
    }

    val allMemories: StateFlow<List<MemoryEntry>> by lazy {
        safeStateFlow(repo.getAllMemories(), emptyList())
    }

    val pinnedMemories: StateFlow<List<MemoryEntry>> by lazy {
        safeStateFlow(repo.getPinnedMemories(), emptyList())
    }

    val smartMemories: StateFlow<List<SmartMemoryEntity>> by lazy {
        safeStateFlow(repo.getAllSmartMemories(), emptyList())
    }

    val smartMemoryEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[SMART_MEMORY_ENABLED_PREF] ?: true }, true)
    }
    val smartUserMemoryEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[SMART_USER_MEMORY_PREF] ?: true }, true)
    }
    val smartProjectMemoryEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[SMART_PROJECT_MEMORY_PREF] ?: true }, true)
    }
    val smartChatMemoryEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[SMART_CHAT_MEMORY_PREF] ?: true }, true)
    }
    val smartMemoryBudget: StateFlow<Int> by lazy {
        safeStateFlow(app.dataStore.data.map {
            it[SMART_MEMORY_BUDGET_PREF]?.toIntOrNull()?.coerceIn(100, SmartMemoryEngine.LARGE_MODEL_BUDGET)
                ?: SmartMemoryEngine.SMALL_MODEL_BUDGET
        }, SmartMemoryEngine.SMALL_MODEL_BUDGET)
    }

    val workspaces: StateFlow<List<Workspace>> by lazy {
        safeStateFlow(repo.getAllWorkspaces(), emptyList())
    }

    val networkEvents: StateFlow<List<NetworkEvent>> by lazy {
        safeStateFlow(repo.getNetworkEvents(), emptyList())
    }

    // ── New DB flows ──────────────────────────────────────────────────────
    val knowledgeEntries: StateFlow<List<KnowledgeEntry>> by lazy {
        safeStateFlow(db.dao().getAllKnowledgeEntries(), emptyList())
    }

    val timelineEntries: StateFlow<List<TimelineEntry>> by lazy {
        safeStateFlow(db.dao().getRecentTimelineEntries(500), emptyList())
    }

    val automationRules: StateFlow<List<AutomationRule>> by lazy {
        safeStateFlow(db.dao().getAllAutomationRules(), emptyList())
    }

    val plugins: StateFlow<List<PluginEntry>> by lazy {
        safeStateFlow(db.dao().getAllPlugins(), emptyList())
    }

    /** Safe wrapper: if stateIn() throws during ViewModel construction, fall back to a plain MutableStateFlow. */
    private fun <T> safeStateFlow(flow: Flow<T>, defaultValue: T): StateFlow<T> {
        return try {
            flow.stateIn(viewModelScope, SharingStarted.Eagerly, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "StateFlow init failed: ${e.message}")
            MutableStateFlow(defaultValue).asStateFlow()
        }
    }

    // ── TTS toggle ──────────────────────────────────────────────────────
    val ttsEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[TTS_ENABLED_PREF] ?: false }, false)
    }

    // ── Offline AI settings ──────────────────────────────────────────────
    // Each role stores only a catalog id. Engines resolve metadata through
    // OfflineModelCatalog, so changing a GGUF never requires UI changes.
    val offlineAiEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_AI_ENABLED_PREF] ?: true }, true)
    }
    val offlineAiMode: StateFlow<OfflineAiMode> by lazy {
        safeStateFlow(app.dataStore.data.map {
            runCatching { OfflineAiMode.valueOf(it[OFFLINE_AI_MODE_PREF] ?: OfflineAiMode.AUTOMATIC.name) }
                .getOrDefault(OfflineAiMode.AUTOMATIC)
        }, OfflineAiMode.AUTOMATIC)
    }
    val offlinePlannerModelId: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_PLANNER_MODEL_PREF] ?: OfflineModelCatalog.PLANNER_MODEL_ID }, OfflineModelCatalog.PLANNER_MODEL_ID)
    }
    val offlineChatModelId: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_CHAT_MODEL_PREF] ?: OfflineModelCatalog.CHAT_MODEL_ID }, OfflineModelCatalog.CHAT_MODEL_ID)
    }
    val offlineReasoningModelId: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_REASONING_MODEL_PREF] ?: OfflineModelCatalog.CHAT_MODEL_ID }, OfflineModelCatalog.CHAT_MODEL_ID)
    }
    val offlineVisionModelId: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_VISION_MODEL_PREF] ?: "" }, "")
    }
    val offlineEmbeddingsModelId: StateFlow<String> by lazy {
        safeStateFlow(app.dataStore.data.map { it[OFFLINE_EMBEDDINGS_MODEL_PREF] ?: "" }, "")
    }

    fun saveOfflineAiEnabled(enabled: Boolean) = viewModelScope.launch {
        app.dataStore.edit { it[OFFLINE_AI_ENABLED_PREF] = enabled }
    }

    fun saveOfflineAiMode(mode: OfflineAiMode) = viewModelScope.launch {
        app.dataStore.edit { it[OFFLINE_AI_MODE_PREF] = mode.name }
    }

    fun saveOfflineModel(role: ModelRole, modelId: String) = viewModelScope.launch {
        val key = when (role) {
            ModelRole.PLANNER -> OFFLINE_PLANNER_MODEL_PREF
            ModelRole.CHAT -> OFFLINE_CHAT_MODEL_PREF
            ModelRole.REASONING -> OFFLINE_REASONING_MODEL_PREF
            ModelRole.VISION -> OFFLINE_VISION_MODEL_PREF
            ModelRole.EMBEDDINGS -> OFFLINE_EMBEDDINGS_MODEL_PREF
            ModelRole.SPEECH -> return@launch
        }
        val selected = OfflineModelCatalog.find(modelId)
        val roleCompatible = selected != null &&
            (selected.role == role || (role == ModelRole.REASONING && selected.role == ModelRole.CHAT))
        if (!roleCompatible) return@launch
        app.dataStore.edit { it[key] = modelId }
        if (role == ModelRole.CHAT) {
            localEngine.setPreferredModel(modelId)
            // Reload only when the selected model is already available. This
            // avoids starting a large download as a side effect of a dropdown.
            if (localEngine.findModelPath(modelId) != null) {
                localEngine.unload()
                val loaded = localEngine.loadModel(modelId)
                _uiState.update { it.copy(localModelReady = loaded) }
            }
        }
    }

    fun recommendedOfflineModel(role: ModelRole): OfflineModelSpec? {
        val snapshot = _uiState.value.deviceSnapshot ?: return null
        return OfflineModelCatalog.recommended(
            DeviceModelProfile(snapshot.ramTotalMb, snapshot.storageFreeGb, snapshot.cpuAbi, android.os.Build.VERSION.SDK_INT),
            role
        )
    }

    // ── Autonomous web search settings ───────────────────────────────────
    val webSearchEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(providerSettings.settings.map { it.searchEnabled }, true)
    }
    val braveEnabled: StateFlow<Boolean> by lazy {
        safeStateFlow(providerSettings.settings.map { it.braveEnabled }, true)
    }
    val braveApiKey: StateFlow<String> by lazy {
        safeStateFlow(providerSettings.settings.map { it.braveApiKey }, "")
    }
    val preferBrave: StateFlow<Boolean> by lazy {
        safeStateFlow(providerSettings.settings.map { it.preferBrave }, false)
    }

    // ── New Provider Manager API ──────────────────────────────────────────
    fun initializeProviderManager() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            aiProviderManager.initialize()
            // Read the legacy DataStore snapshot directly instead of using
            // StateFlow.value, which may still contain its initial default on a
            // cold start. This makes migration deterministic and idempotent.
            val legacy = app.dataStore.data.first()
            val legacyOpenRouter = legacy[API_KEY_PREF].orEmpty()
            val legacyGemini = legacy[GEMINI_KEY_PREF].orEmpty()
            aiProviderManager.migrateLegacy(
                legacyOpenRouter,
                legacyGemini,
                legacy[MODEL_PREF].orEmpty(),
                legacy[GEMINI_MODEL_PREF].orEmpty()
            )
            // Keep the autonomous search resolver in sync with the new
            // Provider Manager. Its provider factory is synchronous, while
            // provider secrets are suspend-read from the encrypted store, so
            // mirror only the encrypted Gemini bridge here.
            syncGeminiSearchBridge()
            // Migrate legacy AI keys into Keystore-backed DataStore values and
            // remove the plaintext copies only after encryption succeeds.
            app.dataStore.edit { prefs ->
                if (legacyOpenRouter.isNotBlank()) {
                    prefs[API_KEY_ENCRYPTED_PREF] = legacySecretStore.encrypt(legacyOpenRouter.trim())
                    prefs.remove(API_KEY_PREF)
                }
                if (legacyGemini.isNotBlank()) {
                    prefs[GEMINI_KEY_ENCRYPTED_PREF] = legacySecretStore.encrypt(legacyGemini.trim())
                    prefs.remove(GEMINI_KEY_PREF)
                }
            }
            providerSettings.migrateLegacySecret()
        }.onFailure { Log.e(TAG, "Provider manager init failed: ${it.message}") }
    }

    fun toggleProvider(providerId: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        aiProviderManager.setEnabled(providerId, enabled)
    }

    fun testProvider(providerId: String, onResult: (ProviderStatus) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        val result = aiProviderManager.testConnection(providerId)
        withContext(Dispatchers.Main.immediate) { onResult(result) }
    }

    fun refreshProviderModels(providerId: String, onResult: (Result<List<ProviderModel>>) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        val result = aiProviderManager.refreshModels(providerId)
        withContext(Dispatchers.Main.immediate) { onResult(result) }
    }

    fun saveProviderCredentials(
        providerId: String,
        apiKey: String,
        headersJson: String = "",
        onResult: (ProviderValidation) -> Unit = {}
    ) = viewModelScope.launch(Dispatchers.IO) {
        val result = aiProviderManager.saveCredentials(providerId, apiKey, headersJson)
        if (result.isValid && providerId == "gemini") syncGeminiSearchBridge()
        withContext(Dispatchers.Main.immediate) { onResult(result) }
    }

    /**
     * Clears a managed provider secret and removes the corresponding legacy
     * search bridge. The bridge is encrypted; it exists only because the
     * autonomous search provider factory is a synchronous callback.
     */
    fun clearProviderCredentials(
        providerId: String,
        onResult: (ProviderValidation) -> Unit = {}
    ) = viewModelScope.launch(Dispatchers.IO) {
        val result = aiProviderManager.clearCredentials(providerId)
        if (result.isValid && providerId == "gemini") {
            app.dataStore.edit { it.remove(GEMINI_KEY_ENCRYPTED_PREF) }
        }
        withContext(Dispatchers.Main.immediate) { onResult(result) }
    }

    private suspend fun syncGeminiSearchBridge() {
        val managedKey = aiProviderManager.getApiKey("gemini")
        app.dataStore.edit { prefs ->
            if (managedKey.isBlank()) prefs.remove(GEMINI_KEY_ENCRYPTED_PREF)
            else prefs[GEMINI_KEY_ENCRYPTED_PREF] = legacySecretStore.encrypt(managedKey)
            prefs.remove(GEMINI_KEY_PREF)
        }
    }

    fun addProviderModel(
        providerId: String,
        modelId: String,
        displayName: String = modelId,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch(Dispatchers.IO) {
        val added = aiProviderManager.addManualModel(providerId, modelId, displayName)
        withContext(Dispatchers.Main.immediate) { onResult(added) }
    }

    fun setActiveProviderModel(providerId: String, modelId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (!aiProviderManager.setActiveModel(providerId, modelId)) return@launch
        when (providerId) {
            "openrouter" -> app.dataStore.edit { it[MODEL_PREF] = modelId }
            "gemini" -> app.dataStore.edit { it[GEMINI_MODEL_PREF] = modelId }
        }
    }

    fun setProviderDefault(role: ProviderRole, providerId: String) = viewModelScope.launch(Dispatchers.IO) {
        val accepted = aiProviderManager.setDefault(role, providerId)
        // Keep the currently supported chat engines compatible while the
        // generic provider adapter is introduced: selecting a built-in chat
        // provider in the new manager also updates the legacy router's enum.
        // Do this only after the managed repository accepts the ready provider;
        // otherwise the two routing sources can disagree.
        if (accepted && role == ProviderRole.CHAT) {
            val legacyProvider = when (providerId) {
                "openrouter" -> Constants.AiProvider.OPENROUTER
                "gemini" -> Constants.AiProvider.GEMINI
                else -> null
            }
            if (legacyProvider != null) {
                app.dataStore.edit { it[PROVIDER_PREF] = legacyProvider.name }
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(aiProvider = legacyProvider) }
                }
            }
        }
    }

    fun saveCustomProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        authType: com.interndra.ai.provider.ProviderAuthType,
        headersJson: String = "",
        organizationId: String = "",
        projectId: String = "",
        apiVersion: String = "",
        endpointPath: String = "/v1/models",
        notes: String = "",
        onResult: (ProviderValidation) -> Unit = {}
    ) = viewModelScope.launch(Dispatchers.IO) {
        val result = aiProviderManager.createCustomProvider(name, baseUrl, apiKey, authType, headersJson, organizationId, projectId, apiVersion, endpointPath, notes)
        withContext(Dispatchers.Main.immediate) { onResult(result) }
    }

    fun deleteProvider(providerId: String) = viewModelScope.launch(Dispatchers.IO) {
        aiProviderManager.deleteProvider(providerId)
    }

    // ── Init ──────────────────────────────────────────────────────────────
    // UPGRADE: Entire init block is wrapped in try-catch so that a crash in
    // ANY component (TTS, Room, DataStore, JNI, plugins, etc.) does NOT take
    // down the app. The error is logged and set on uiState so the user sees
    // a graceful degraded state instead of a crash dialog.
    init {
        try {
            tts = TextToSpeech(app, this)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init failed: ${e.message}")
        }
        refreshStatus()
        refreshDeviceSnapshot()
        initializeProviderManager()

        viewModelScope.launch {
            try {                localEngine.setPreferredModel(offlineChatModelId.value)
                val ready = localEngine.isModelDownloaded()

                val memCount = repo.memoryCount()
                val knCount  = db.dao().knowledgeCount()
                val tlCount  = db.dao().timelineCount()
                // Low-importance memories that have not been used for 90 days
                // leave the active index but remain recoverable in archive storage.
                repo.archiveStaleSmartMemories(
                    before = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L
                )
                _uiState.update { it.copy(
                    localModelReady = ready,
                    memoryCount     = memCount,
                    knowledgeCount  = knCount,
                    timelineCount   = tlCount
                )}
                if (ready) localEngine.loadModel()
            } catch (e: Exception) {
                Log.e(TAG, "Init: model/DB load failed: ${e.message}")
                _uiState.update { it.copy(error = "Init: ${e.message}") }
            }
        }

        viewModelScope.launch {
            try {
                activeWorkspaceId.collect { wsId ->
                    repo.setWorkspace(wsId)
                    _uiState.update { it.copy(activeWorkspaceId = wsId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init: workspace collection failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                knowledgeEntries.collect { entries ->
                    if (entries.isNotEmpty()) {
                        knowledgeGraph.rebuild(entries)
                        _topConcepts.value = knowledgeGraph.topConcepts(20).map { it.label }
                        _uiState.update { it.copy(knowledgeCount = entries.size) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init: knowledge graph failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                val activeTriggers = db.dao().getActiveTriggerRules()
                automationEngine.updateRuleList(activeTriggers)
            } catch (e: Exception) {
                Log.e(TAG, "Init: automation rules failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                pluginManager.registerBuiltInPlugins()
                _uiState.update { it.copy(pluginCount = pluginManager.count()) }
            } catch (e: Exception) {
                Log.e(TAG, "Init: plugin registration failed: ${e.message}")
            }
        }

        // ── Initialize Shizuku ───────────────────────────────────────────
        viewModelScope.launch {
            try {
                shizukuManager.init(onBinderDeath = {
                    _shizukuAvailable.value = false
                    _shizukuAuthorized.value = false
                    _shizukuUid.value = -1
                    Log.w(TAG, "Shizuku binder died — attempting re-auth on next command")
                })
                refreshShizukuStatus()
                startShizukuHealthCheck()
                Log.i(TAG, "Shizuku initialized: available=${_shizukuAvailable.value}, " +
                    "authorized=${_shizukuAuthorized.value}, UID=${_shizukuUid.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Init: Shizuku init failed: ${e.message}")
            }
        }

        // Restore terminal sessions from disk
        viewModelScope.launch {
            try {
                terminalAgent.loadSessionsFromDisk()
                _terminalSessions.value = terminalAgent.getSessionNames()
                Log.i(TAG, "Restored ${_terminalSessions.value.size} terminal sessions")
            } catch (e: Exception) {
                Log.e(TAG, "Init: session restore failed: ${e.message}")
            }
        }

        // ── Initialize Termux Environment ───────────────────────────────
        viewModelScope.launch {
            try {
                termuxEnvironment.init()
                Log.i(TAG, "TermuxEnvironment initialized: ${termuxEnvironment.getMode()}")
                // Update terminal agent's mode
                val envMode = termuxEnvironment.getMode()
                val modeSwitched = terminalAgent.switchMode(envMode)
                if (!modeSwitched) Log.w(TAG, "Failed to switch Termux mode to $envMode")

                // ── Start REAL PTY terminal session if Termux is available ──
                if (termuxEnvironment.hasTermux()) {
                    val envInfo = termuxEnvironment.info.value
                    val config = TerminalSession.TermuxSessionConfig(
                        prefix = envInfo.bootstrapPrefix,
                        homeDir = "${envInfo.bootstrapPrefix}/home",
                        shellPath = "${envInfo.bootstrapPrefix}/usr/bin/bash"
                    )
                    val started = terminalAgent.startPtySession(config)
                    if (started) {
                        Log.i(TAG, "✅ PTY terminal session started successfully")
                    } else {
                        Log.w(TAG, "⚠️ PTY session failed — falling back to pipe-based shell")
                    }
                }
                // ⚡ FIX: Auto-install Termux bootstrap on first launch if Shizuku is available
                else if (shizukuShell.isElevatedAvailable && termuxBootstrapInstaller.isInstalled().not()) {
                    Log.i(TAG, "📦 Auto-installing Termux bootstrap via Shizuku...")
                    val result = termuxBootstrapInstaller.install(progressCallback = { progress ->
                        Log.i(TAG, "Bootstrap progress: $progress")
                    })
                    if (result.success) {
                        termuxEnvironment.refreshStatus()
                        // ⚡ FIX: Sync TerminalAgent mode from environment + invalidate cache
                        terminalAgent.syncModeFromEnvironment()
                        cachedRuntimeCaps = null
                        val newMode = termuxEnvironment.getMode()
                        terminalAgent.switchMode(newMode)
                        if (termuxEnvironment.hasTermux()) {
                            val envInfo = termuxEnvironment.info.value
                            val config = TerminalSession.TermuxSessionConfig(
                                prefix = envInfo.bootstrapPrefix,
                                homeDir = "${envInfo.bootstrapPrefix}/home",
                                shellPath = "${envInfo.bootstrapPrefix}/usr/bin/bash"
                            )
                            terminalAgent.startPtySession(config)
                            Log.i(TAG, "✅ Auto-installed Termux + PTY session started")
                        }
                    } else {
                        Log.w(TAG, "Auto-install failed: ${result.error}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init: TermuxEnvironment failed: ${e.message}")
            }
        }

        // ── Initialize PRoot-distro state ───────────────────────────────
        viewModelScope.launch {
            try {
                refreshProotDistroState()
                Log.i(TAG, "PRoot-distro state refreshed: ${_prootDistroState.value.isAvailable}")
            } catch (e: Exception) {
                Log.w(TAG, "Init: PRoot-distro refresh failed: ${e.message}")
            }
        }
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
    fun saveApiKey(key: String) = viewModelScope.launch {
        val value = key.trim()
        app.dataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(API_KEY_ENCRYPTED_PREF)
            else prefs[API_KEY_ENCRYPTED_PREF] = legacySecretStore.encrypt(value)
            prefs.remove(API_KEY_PREF)
        }
        aiProviderManager.migrateLegacy(value, geminiApiKey.value, selectedModel.value, selectedGeminiModel.value)
    }
    fun saveGeminiApiKey(key: String) = viewModelScope.launch {
        val value = key.trim()
        app.dataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(GEMINI_KEY_ENCRYPTED_PREF)
            else prefs[GEMINI_KEY_ENCRYPTED_PREF] = legacySecretStore.encrypt(value)
            prefs.remove(GEMINI_KEY_PREF)
        }
        aiProviderManager.migrateLegacy(apiKey.value, value, selectedModel.value, selectedGeminiModel.value)
    }

    /** Test Gemini API key by making an actual API call. Returns true on success. */
    fun testGeminiApi(onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        val key = geminiApiKey.value
        val model = selectedGeminiModel.value
        if (key.isBlank()) {
            onResult(false, "❌ Gemini API key is empty. Save your key first.")
            return@launch
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val engine = GeminiAiEngine(key, model)
            val ok = engine.validateApiKey()
            if (ok) {
                onResult(true, "✅ Gemini API is working!\nModel: $model\nKey: ${key.takeLast(4)}")
            } else {
                onResult(false, "❌ Gemini API returned an error.\nPossible causes:\n- Invalid API key\n- Model '$model' not available\n- Check your internet connection\n\nTry: re-save your key or select a different model.")
            }
        } catch (e: Exception) {
            val msg = e.message?.replace(key, "***") ?: "Unknown error"
            onResult(false, "❌ Gemini API test failed:\n$msg")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }
    fun saveModel(model: String)          = viewModelScope.launch {
        app.dataStore.edit { it[MODEL_PREF] = model }
        val legacy = app.dataStore.data.first()
        aiProviderManager.migrateLegacy(
            legacy[API_KEY_PREF].orEmpty(), legacy[GEMINI_KEY_PREF].orEmpty(),
            legacy[MODEL_PREF].orEmpty(), legacy[GEMINI_MODEL_PREF].orEmpty()
        )
    }
    fun saveGeminiModel(model: String)    = viewModelScope.launch {
        app.dataStore.edit { it[GEMINI_MODEL_PREF] = model }
        val legacy = app.dataStore.data.first()
        aiProviderManager.migrateLegacy(
            legacy[API_KEY_PREF].orEmpty(), legacy[GEMINI_KEY_PREF].orEmpty(),
            legacy[MODEL_PREF].orEmpty(), legacy[GEMINI_MODEL_PREF].orEmpty()
        )
    }
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
    fun saveTtsEnabled(enabled: Boolean) = viewModelScope.launch {
        app.dataStore.edit { it[TTS_ENABLED_PREF] = enabled }
    }
    fun saveWebSearchEnabled(enabled: Boolean) = viewModelScope.launch {
        providerSettings.setSearchEnabled(enabled)
    }
    fun saveBraveEnabled(enabled: Boolean) = viewModelScope.launch {
        providerSettings.setBraveEnabled(enabled)
    }
    fun saveBraveApiKey(key: String) = viewModelScope.launch {
        providerSettings.setBraveApiKey(key)
    }
    fun savePreferBrave(prefer: Boolean) = viewModelScope.launch {
        providerSettings.setPreferBrave(prefer)
    }
    fun clearBraveApiKey() = viewModelScope.launch {
        providerSettings.clearBraveKey()
    }
    fun resetSearchSettings() = viewModelScope.launch {
        providerSettings.resetAll()
    }

    /** Test the Brave Search API key with a live request. */
    fun testBraveApi(onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        val key = braveApiKey.value
        if (key.isBlank()) {
            onResult(false, "❌ Brave API key is empty. Save your key first.")
            return@launch
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val ok = providerManager.testBraveApiKey(key)
            if (ok) {
                onResult(true, "✅ Brave Search API is working!\nKey: ${key.takeLast(4)}")
            } else {
                onResult(false, "❌ Brave API returned an error.\nPossible causes:\n- Invalid API key\n- Check your internet connection\n\nTry: re-save your key.")
            }
        } catch (e: Exception) {
            val msg = e.message?.replace(key, "***") ?: "Unknown error"
            onResult(false, "❌ Brave API test failed:\n$msg")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
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
    private val emergencyLockPersisted: StateFlow<Boolean> by lazy {
        safeStateFlow(app.dataStore.data.map { it[EMERGENCY_LOCK_PREF] ?: false }, false)
    }

    /**
     * Returns the configured Provider Manager chat provider, if one is enabled,
     * credentialed (or local), and has at least one selectable model.
     * This is the runtime source of truth; legacy preferences are fallback only.
     */
    private fun currentManagedChatProvider(): ProviderConfig? {
        val state = providerState.value
        fun usable(config: ProviderConfig): Boolean = config.isReadyForChat
        return state.providers.firstOrNull { it.id == state.defaults.chat && usable(it) }
            ?: state.providers.firstOrNull(::usable)
    }

    // ── Main command handler ──────────────────────────────────────────────
    fun sendCommand(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        // AtomicBoolean gate prevents race condition: two rapid calls
        // could both pass the isLoading check before the coroutine sets it.
        if (!commandGate.compareAndSet(false, true)) return

        val mode = privacyMode.value
        val offlineMode = offlineAiMode.value
        val legacyProvider = aiProvider.value
        val managedProvider = currentManagedChatProvider()
        val provider = when (managedProvider?.id) {
            "gemini" -> Constants.AiProvider.GEMINI
            "openrouter" -> Constants.AiProvider.OPENROUTER
            else -> legacyProvider
        }
        val managedProviderReady = managedProvider != null
        val key  = apiKey.value
        val geminiKey = geminiApiKey.value

        // FIX: When provider is configured with a valid API key, auto-promote
        // HYBRID mode to CLOUD_ENHANCED so the AI actually routes to cloud.
        // Without this, HYBRID mode sends short/simple queries to LOCAL,
        // where the rule-based fallback returns "Unable to process this request"
        // instead of using Gemini/OpenRouter.
        // LOCAL_ONLY is always respected — never override user's privacy choice.
        //
        // RACE CONDITION FIX: DataStore-backed StateFlows may still return their
        // default value ("" for keys) when first accessed, even though the actual
        // saved value exists. If the API key is blank but the provider matches,
        // don't force CLOUD_ENHANCED — stay in HYBRID so the error check below
        // doesn't falsely report "set your API key". The user can just re-send.
        val effectiveMode = when {
            !offlineAiEnabled.value -> mode
            offlineMode == OfflineAiMode.OFFLINE_ONLY -> PrivacyMode.LOCAL_ONLY
            offlineMode == OfflineAiMode.CLOUD_ONLY -> PrivacyMode.CLOUD_ENHANCED
            mode == PrivacyMode.LOCAL_ONLY -> PrivacyMode.LOCAL_ONLY
            managedProviderReady -> PrivacyMode.CLOUD_ENHANCED
            provider == Constants.AiProvider.GEMINI && geminiKey.isNotBlank() -> PrivacyMode.CLOUD_ENHANCED
            provider == Constants.AiProvider.GEMINI -> PrivacyMode.HYBRID  // key not loaded yet
            provider == Constants.AiProvider.OPENROUTER && key.isNotBlank() -> PrivacyMode.CLOUD_ENHANCED
            provider == Constants.AiProvider.OPENROUTER -> PrivacyMode.HYBRID  // key not loaded yet
            else -> mode
        }

        // FIX: Check the correct API key based on selected provider
        if (effectiveMode == PrivacyMode.CLOUD_ENHANCED && !managedProviderReady) {
            when (provider) {
                Constants.AiProvider.GEMINI -> {
                    if (geminiKey.isBlank()) {
                        _uiState.update { it.copy(error = "Gemini mode needs Google Gemini API key — set it in Settings") }
                        commandGate.set(false)  // FIX: release gate on early return
                        return
                    }
                }
                Constants.AiProvider.OPENROUTER -> {
                    if (key.isBlank()) {
                        _uiState.update { it.copy(error = "Cloud mode needs OpenRouter API key — set it in Settings") }
                        commandGate.set(false)  // FIX: release gate on early return
                        return
                    }
                }
            }
        }

        generationJob = viewModelScope.launch(crashHandler + kotlinx.coroutines.Dispatchers.Main) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // SAFETY NET: if the entire command takes > 2 minutes, force-reset
            // to prevent "processing forever" bug.
            // WATCHDOG: separate coroutine ensures isLoading + commandGate are
            // ALWAYS reset even if withTimeout fails to cancel cleanly.
            val watchdogJob = launch {
                delay(90_000L)
                if (_uiState.value.isLoading) {  // only reset if truly stuck
                    Log.w(TAG, "⏰ Watchdog: force-resetting loading state")
                    _uiState.update { it.copy(isLoading = false, error = "Request took too long — please try again") }
                    commandGate.set(false)
                }
            }
            try {
                kotlinx.coroutines.withTimeout(120_000L) {
                    processCommand(trimmed, effectiveMode, mode, provider, key)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "⏱ Command timed out after 2 minutes")
                try { repo.addUserMessage("⏱ Command timed out after 2 minutes") } catch (_: Exception) {}
                _uiState.update { it.copy(error = "Command timed out after 2 minutes. Try a simpler request.") }
            } finally {
                watchdogJob.cancel()
                _uiState.update { it.copy(isLoading = false) }
                commandGate.set(false)
            }
        }
    }

    /**
     * Stops the currently running AI generation: cancels the generation
     * coroutine, releases the command gate, and marks a still-pending
     * placeholder message as interrupted so the chat shows it stopped.
     * The user can immediately send a new message afterwards.
     */
    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        commandGate.set(false)
        taskManager.cancel()
        agentOrchestrator.reset()
        _uiState.update { it.copy(isLoading = false, error = null) }
        // Capture the placeholder id synchronously so a brand-new message sent
        // immediately after Stop is never mislabeled as "stopped".
        val placeholderId = messages.value.lastOrNull()?.takeIf { it.isLoading }?.id
        if (placeholderId != null) {
            viewModelScope.launch {
                runCatching { repo.updateAiMessage(placeholderId, "⏹ Generation stopped.") }
            }
        }
    }

    /** Actual command processing — extracted so withTimeout can wrap it. */
    private suspend fun processCommand(
        trimmed: String,
        effectiveMode: PrivacyMode,
        mode: PrivacyMode,
        provider: Constants.AiProvider,
        key: String
    ) {

        // FIX: ALL Room DB operations moved INSIDE try-catch.
            // Previously addUserMessage, addAiPlaceholder, newSession were
            // BEFORE the try-catch — any DB failure (corrupted data, missing
            // migration, null DAO) would crash the app.
            var placeholderId = -1L
            var session = ""
            val startMs = System.currentTimeMillis()

            try {
                repo.addUserMessage(trimmed)
                placeholderId = repo.addAiPlaceholder()
                session       = repo.newSession()
            } catch (dbErr: Exception) {
                Log.e(TAG, "DB init failed: ${dbErr.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Database error: ${dbErr.message}. Try clearing app data."
                )}
                return
            }

            // ── Agent lifecycle: start the state machine + live activity ────
            agentOrchestrator.begin(trimmed)
            val complexity = ComplexityClassifier.classify(trimmed)
            if (complexity == ComplexityClassifier.Complexity.COMPLEX) {
                agentOrchestrator.planning("Planning the steps to complete this task")
            } else if (complexity == ComplexityClassifier.Complexity.MODERATE) {
                agentOrchestrator.thinking("Working through this step by step")
            }

            // ── Interactive questioning: ask only when genuinely needed ──────
            // The Questioning Engine decides; most requests CONTINUE. When a
            // question is required, the task pauses (WAITING_FOR_USER), the
            // question card appears inline, and the SAME request resumes after
            // the user answers (history guard prevents re-asking).
            val question = preTaskFlow.decide(trimmed)
            if (question != null) {
                _pendingQuestion.value = question
                agentOrchestrator.setState(AgentState.WAITING_FOR_USER)
                emitAgent(AgentActivity.Thinking("Waiting for your choice"))
                try { repo.log(session, LogType.INFO, "❓ Question: ${question.question}") } catch (_: Exception) {}
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // ── Phase 5/6/7: try the workflow planner FIRST ─────────────────
            // If the user's request maps cleanly to a known workflow (WhatsApp
            // message, find PDFs, launch app, etc.), execute it directly with
            // structured narration — no need to round-trip through the LLM.
            // Falls through to the AI orchestrator if no confident match.
            try {
                val detected = workflowPlanner.detect(trimmed)
                if (detected != null && detected.confidence >= 0.82f) {
                    val workflow = workflowPlanner.plan(detected)
                    if (workflow != null) {
                        // ── Deduplication: skip if same workflow ran recently ──
                        val now = System.currentTimeMillis()
                        val last = lastWorkflowRun
                        if (last != null &&
                            last.workflowName == workflow.name &&
                            last.description == workflow.description &&
                            now - last.timestampMs < 30_000L) {
                            Log.d(TAG, "⏭️ Skipping duplicate workflow '${workflow.name}' (ran ${now - last.timestampMs}ms ago)")
                            // Must update placeholder so the user doesn't see "Thinking..." forever
                            try {
                                repo.updateAiMessage(placeholderId, "⏭️ Already working on this — please wait a moment.")
                            } catch (_: Exception) {}
                            agentOrchestrator.reset()
                            _uiState.update { it.copy(isLoading = false) }
                            return
                        }
                        repo.log(session, LogType.INFO, "⚙️ Workflow detected: ${workflow.name} (confidence ${(detected.confidence * 100).toInt()}%)")
                        val narrationLines = mutableListOf<String>()
                        // Live agent activity: map narration levels to state-machine
                        // events so the chat shows the workflow running in real time.
                        agentOrchestrator.planning("Running workflow: ${workflow.name}")
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
                                WorkflowEngine.NarrationLevel.SKIPPED          -> "⏭️ Skipped"
                            }
                            narrationLines.add("$prefix: $msg")
                            // Map narration to the agent state machine + live timeline.
                            when (level) {
                                WorkflowEngine.NarrationLevel.DOING ->
                                    agentOrchestrator.toolStart("workflow", msg)
                                WorkflowEngine.NarrationLevel.DONE ->
                                    agentOrchestrator.toolResult("workflow", true, msg)
                                WorkflowEngine.NarrationLevel.FAILED ->
                                    agentOrchestrator.toolResult("workflow", false, msg)
                                WorkflowEngine.NarrationLevel.BLOCKED ->
                                    agentOrchestrator.fail(msg)
                                WorkflowEngine.NarrationLevel.NEEDS_PERMISSION ->
                                    agentOrchestrator.needsConfirmation(msg)
                                WorkflowEngine.NarrationLevel.COMPLETE ->
                                    agentOrchestrator.verifyStart(msg)
                                WorkflowEngine.NarrationLevel.PARTIAL ->
                                    agentOrchestrator.emit(AgentActivity.Verification(msg, success = false))
                                else -> { /* UNDERSTOOD / SKIPPED — no state change */ }
                            }
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

                        // Claude-style: the step narration lives in a collapsible
                        // "Thinking" block instead of cluttering the reply.
                        val workflowThinking = com.interndra.ai.ThinkingMarker.encode(
                            listOf(com.interndra.ai.ThinkingEpisode("Workflow: ${workflow.name}", narrationLines))
                        )
                        repo.updateAiMessage(placeholderId, sb.toString().trim() + workflowThinking)
                        _uiState.update { it.copy(isLoading = false) }

                        // ── Track last workflow run for deduplication ──
                        lastWorkflowRun = LastWorkflowRun(
                            workflowName = workflow.name,
                            description = workflow.description,
                            timestampMs = System.currentTimeMillis()
                        )

                        if (result.overallSuccess) {
                            repo.rememberSuccess(trimmed, workflow.name,
                                workflow.steps.map { it.command }, "WORKFLOW")
                            _uiState.update { it.copy(memoryCount = repo.memoryCount()) }
                            agentOrchestrator.complete()
                        } else {
                            agentOrchestrator.fail("Workflow finished with ${result.failedSteps} failed step(s)")
                        }
                        timelineEngine.recordCommand(
                            command = workflow.name,
                            output  = "workflow: ${result.succeededSteps}/${result.stepResults.size} ok",
                            success = result.overallSuccess,
                            durationMs = result.durationMs
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Workflow planner failed, falling back to AI: ${e.message}")
                // CRASH FIX: repo.log() wrapped in try-catch — if DB is
                // corrupted, logging must not crash the app.
                try { repo.log(session, LogType.INFO, "⚠️ Workflow planner unavailable, using AI") } catch (_: Exception) {}
            }

            try {
                val managedState = providerState.value
                val managedProvider = managedState.providers.firstOrNull { providerConfig ->
                    providerConfig.id == managedState.defaults.chat &&
                        providerConfig.isReadyForChat
                } ?: managedState.providers.firstOrNull { providerConfig ->
                    providerConfig.isReadyForChat
                }
                val managedGeminiKey = if (managedProvider?.id == "gemini") {
                    aiProviderManager.getApiKey("gemini").ifBlank { geminiApiKey.value }
                } else geminiApiKey.value
                val managedGeminiModel = if (managedProvider?.id == "gemini") {
                    managedProvider.activeModelId.ifBlank { managedProvider.models.firstOrNull()?.id.orEmpty() }
                        .ifBlank { selectedGeminiModel.value }
                } else selectedGeminiModel.value
                val cloudEngine  = CloudAiEngine(
                    if (managedProvider?.id == "openrouter") aiProviderManager.getApiKey("openrouter").ifBlank { key } else key,
                    if (managedProvider?.id == "openrouter") {
                        managedProvider.activeModelId.ifBlank { managedProvider.models.firstOrNull()?.id.orEmpty() }
                            .ifBlank { selectedModel.value }
                    } else selectedModel.value
                )
                val geminiEngineVal = GeminiAiEngine(managedGeminiKey, managedGeminiModel)
                val orchestrator = AiOrchestrator(
                    app, localEngine, cloudEngine, geminiEngineVal,
                    managedCloudConfigured = {
                        managedProvider != null && managedProvider.id != "gemini"
                    },
                    managedCloudRequest = managedProvider?.takeIf { it.id != "gemini" }?.let { providerConfig ->
                        { input, memoryItems, history, jailbreak, level, runtime ->
                            runCatching {
                                aiProviderManager.parseChat(
                                    providerId = providerConfig.id,
                                    modelId = providerConfig.activeModelId.ifBlank { providerConfig.models.firstOrNull()?.id.orEmpty() },
                                    userInput = input,
                                    memory = memoryItems,
                                    chatHistory = history,
                                    jailbreakActive = jailbreak,
                                    jailbreakLevel = level,
                                    runtimeContext = runtime
                                )
                            }.getOrElse { error ->
                                // The selected Provider Manager endpoint is
                                // authoritative. Do not silently send the same
                                // request through a different legacy provider.
                                Log.w(TAG, "Managed provider ${providerConfig.id} failed: ${error.message}")
                                val safeMessage = error.message.orEmpty().take(180)
                                AiEngineResult(
                                    intentJson = com.google.gson.Gson().toJson(mapOf(
                                        "action" to "chat",
                                        "reply" to "⚠️ ${providerConfig.name} request failed: $safeMessage",
                                        "commands" to emptyList<Any>()
                                    )),
                                    // This is an error from the selected cloud
                                    // provider, not a fallback request.
                                    source = AiSource.CLOUD,
                                    modelUsed = "${providerConfig.id}-error",
                                    latencyMs = 0L
                                )
                            }
                        }
                    } ?: { _, _, _, _, _, _ -> null }
                )

                // Set provider and jailbreak settings. Provider Manager defaults
                // take precedence over the legacy two-provider preference.
                orchestrator.activeProvider = when (managedProvider?.id) {
                    "gemini" -> Constants.AiProvider.GEMINI
                    "openrouter" -> Constants.AiProvider.OPENROUTER
                    else -> provider
                }
                orchestrator.jailbreakActive = jailbreakEnabled.value
                orchestrator.jailbreakLevel = jailbreakLevel.value

                // Apply obfuscation settings
                JailbreakEngine.obfuscationTechnique = obfuscationTechnique.value
                JailbreakEngine.activeLevel = if (jailbreakEnabled.value) jailbreakLevel.value else JailbreakLevel.OFF

                // ── RAG context augmentation ──────────────────────────────
                // Collected question answers (platform, language, preferences)
                // are injected so the resumed task knows the user's choices.
                var augmentedInput = trimmed + questionContext()
                if (knowledgeEntries.value.isNotEmpty()) {
                    val ragCtx = ragEngine.buildAugmentedPrompt(trimmed)
                    if (ragCtx != trimmed) {
                        augmentedInput = ragCtx
                        repo.log(session, LogType.INFO, "📚 RAG: vault context injected")
                    }
                }

                // ── AUTONOMOUS web search ─────────────────────────────────
                // Fully automatic: the SearchPlanner silently decides whether a
                // search is needed (freshness, entities, tech docs…). No search
                // button, no mode, no toggle — just natural chat. The manager
                // picks providers (Gemini Google Search primary, Brave secondary,
                // DuckDuckGo fallback), caches, ranks, reads pages, and reports
                // subtle progress so the chat shows what's happening.
                var webSources: List<SearchResult> = emptyList()
                if (!_uiState.value.emergencyLockActive && webSearchEnabled.value) {
                    val settings = WebSearchSettings(
                        searchEnabled = webSearchEnabled.value,
                        braveEnabled = braveEnabled.value,
                        braveApiKey = braveApiKey.value,
                        preferBrave = preferBrave.value
                    )
                    repo.log(session, LogType.INFO, "🔍 Autonomous web search planner running...")
                    // Live agent activity for the research phase.
                    agentOrchestrator.searching("\"${trimmed.take(80)}\"")
                    val digest = searchManager.runAutonomous(trimmed, settings) { status ->
                        // Subtle progress inside the chat — no user interaction.
                        val hint = when (status) {
                            is SearchStatus.Searching -> "🔍 Searching the web..."
                            is SearchStatus.Found -> "✅ Found ${status.count} trusted sources..."
                            is SearchStatus.Reading -> "📖 Reading webpages..."
                            is SearchStatus.Analyzing -> "🧠 Analyzing information..."
                            is SearchStatus.Done -> "✅ Search complete"
                            is SearchStatus.Failed -> "⚠️ ${status.message}"
                        }
                        // Map search phases to the live activity timeline.
                        when (status) {
                            is SearchStatus.Searching -> agentOrchestrator.emit(AgentActivity.Search(trimmed.take(80)))
                            is SearchStatus.Found -> agentOrchestrator.toolResult("web", true, "Found ${status.count} trusted sources")
                            is SearchStatus.Reading -> agentOrchestrator.reading("Reading ${status.count} webpages")
                            is SearchStatus.Analyzing -> agentOrchestrator.thinking("Analyzing information")
                            is SearchStatus.Done -> agentOrchestrator.verifyStart("Verified sources via ${status.providerUsed.label}")
                            is SearchStatus.Failed -> agentOrchestrator.emit(AgentActivity.Error(status.message))
                        }
                        // Late hints must not overwrite the final message after
                        // Stop (or a brand-new command started). Guard against
                        // both by only writing while this exact job runs.
                        val jobAtDispatch = generationJob
                        viewModelScope.launch {
                            if (jobAtDispatch?.isActive == true) {
                                repo.updateAiMessage(placeholderId, hint)
                            }
                        }
                    }
                    webSources = digest.results
                    val fullCtx = buildString {
                        if (digest.context.isNotBlank()) append(digest.context)
                        if (digest.pageDigests.isNotBlank()) append("\n\n").append(digest.pageDigests)
                    }
                    if (fullCtx.isNotBlank()) {
                        augmentedInput = "$augmentedInput\n\n$fullCtx"
                        repo.log(session, LogType.INFO,
                            "✅ Web (${digest.providersUsed.joinToString { it.label }}): " +
                                "${digest.results.size} sources, cacheHit=${digest.cacheHit}")
                        // Network transparency: report the actual domain(s) used.
                        digest.providersUsed.forEach { provider ->
                            val domain = when (provider) {
                                SearchProviderId.GEMINI -> Constants.GEMINI_DOMAIN
                                SearchProviderId.BRAVE -> Constants.BRAVE_DOMAIN
                                else -> "html.duckduckgo.com"
                            }
                            repo.logNetworkEvent(domain, "WebSearch")
                        }
                    }
                }

                // ── Route + parse ─────────────────────────────────────────
                agentOrchestrator.thinking("Routing to ${effectiveMode.label}")
                repo.log(session, LogType.INFO, "🧠 Routing: ${effectiveMode.emoji} ${effectiveMode.label} (privacyMode: ${mode.name})")

                val memoryPolicy = SmartMemoryPolicy(
                    enabled = smartMemoryEnabled.value,
                    userEnabled = smartUserMemoryEnabled.value,
                    projectEnabled = smartProjectMemoryEnabled.value,
                    chatEnabled = smartChatMemoryEnabled.value,
                    maxTokens = smartMemoryBudget.value
                )
                val memoryContext = repo.buildSmartMemoryContext(
                    query = augmentedInput,
                    chatId = activeWorkspaceId.value,
                    // A workspace is the current project namespace when the
                    // user explicitly saves project memory; this keeps it
                    // isolated from other workspaces without a cloud account.
                    projectId = activeWorkspaceId.value,
                    maxTokens = if (effectiveMode == PrivacyMode.LOCAL_ONLY) {
                        SmartMemoryEngine.SMALL_MODEL_BUDGET
                    } else {
                        SmartMemoryEngine.LARGE_MODEL_BUDGET
                    },
                    policy = memoryPolicy
                )
                // Keep only a small recency window; durable context is handled
                // by SmartMemoryEngine and is never the complete chat database.
                val chatHistory  = repo.getChatHistory(limit = 8)
                val runtimeContext = withContext(Dispatchers.IO) { buildTerminalRuntimeContext() }
                val orchResult = orchestrator.process(
                    userInput   = augmentedInput,
                    memory      = memoryContext,
                    privacyMode = effectiveMode,
                    chatHistory = chatHistory,
                    runtimeContext = runtimeContext,
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
                    agentOrchestrator.fail("Command blocked by Safety Engine: ${blocked.reason}")
                    repo.updateAiMessage(placeholderId, "⛔ Command blocked by Safety Engine:\n${blocked.reason}")
                    _uiState.update { it.copy(isLoading = false) }
                    return
                }

                if (safety.hasConfirmRequired(safetyReports)) {
                    val confirmReport = safetyReports.first { it.result == SafetyEngine.ValidationResult.REQUIRES_CONFIRMATION }
                    val summary = commands.joinToString("\n") { "• ${it.description}" }
                    // Enter WAITING_FOR_CONFIRMATION state so the UI shows it needs approval.
                    agentOrchestrator.needsConfirmation(confirmReport.reason)
                    repo.updateAiMessage(placeholderId, buildAiReply(intent, orchResult.explanation, suppressSteps = usingFallbackCommands))
                    _uiState.update { it.copy(
                        isLoading = false,
                        pendingConfirmation = ConfirmationRequest(
                            sessionId      = session,
                            message        = confirmReport.reason,
                            commandSummary = summary,
                            onConfirm      = { executeCommands(session, trimmed, intent, commands, approvedByUser = true) }
                        )
                    )}
                    return
                }

                // ── Special: Install Embedded Termux Bootstrap ──────────
                if (intent.action == "install_bootstrap") {
                    val statusMsg = intent.reply?.take(200) ?: "📦 Installing Embedded Termux bootstrap..."
                    repo.updateAiMessage(placeholderId, statusMsg)
                    _uiState.update { it.copy(isLoading = true) }
                    viewModelScope.launch(Dispatchers.IO) {
                        agentOrchestrator.toolStart("termux", "Installing Embedded Termux bootstrap", "install_bootstrap")
                        val result = termuxBootstrapInstaller.install(progressCallback = { progress ->
                            viewModelScope.launch {
                                repo.updateAiMessage(placeholderId, "📦 $progress")
                            }
                        })
                        if (result.success) {
                            termuxEnvironment.refreshStatus()
                            // ⚡ FIX: Sync TerminalAgent mode from environment + invalidate cache
                            terminalAgent.syncModeFromEnvironment()
                            cachedRuntimeCaps = null
                            // ⚡ FIX: Sync TerminalAgent mode → Termux and start PTY session
                            val envMode = termuxEnvironment.getMode()
                            terminalAgent.switchMode(envMode)
                            if (termuxEnvironment.hasTermux()) {
                                val envInfo = termuxEnvironment.info.value
                                val config = TerminalSession.TermuxSessionConfig(
                                    prefix = envInfo.bootstrapPrefix,
                                    homeDir = "${envInfo.bootstrapPrefix}/home",
                                    shellPath = "${envInfo.bootstrapPrefix}/usr/bin/bash"
                                )
                                terminalAgent.startPtySession(config)
                                Log.i(TAG, "✅ Post-install: PTY started, mode=${termuxEnvironment.getMode()}")
                            }
                            val msg = "✅ Embedded Termux installed! You can now use python3, git, node, npm, pip, apt."
                            repo.updateAiMessage(placeholderId, msg)
                            agentOrchestrator.toolResult("termux", true, "Bootstrap installed")
                            agentOrchestrator.complete()
                        } else {
                            val err = "❌ Installation failed: ${result.error?.take(200) ?: "Unknown error"}"
                            repo.updateAiMessage(placeholderId, err)
                            agentOrchestrator.toolResult("termux", false, "Bootstrap install failed")
                            agentOrchestrator.fail("Bootstrap installation failed")
                        }
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    return
                }

                // ── Execute ───────────────────────────────────────────────
                // Claude-style collapsible "Thinking" block: shows the agent's
                // reasoning (intent, plan, commands, search) without dumping it
                // into the visible reply text.
                val thinkingSteps = mutableListOf<String>()
                thinkingSteps.add("🤔 Intent: ${intent.action.replace('_', ' ')}")
                if (!orchResult.explanation.isNullOrBlank()) {
                    thinkingSteps.add(orchResult.explanation.trim().take(500))
                }
                if (commands.isNotEmpty()) {
                    thinkingSteps.add("⚡ ${commands.size} command${if (commands.size > 1) "s" else ""} planned:")
                    commands.take(8).forEach { thinkingSteps.add("   • ${it.description}") }
                }
                if (webSources.isNotEmpty()) {
                    thinkingSteps.add("🔍 Web search: ${webSources.size} source${if (webSources.size > 1) "s" else ""} analyzed")
                }
                val replyText = buildAiReply(intent, orchResult.explanation, suppressSteps = usingFallbackCommands) +
                    com.interndra.ai.ThinkingMarker.encode(
                        listOf(com.interndra.ai.ThinkingEpisode("Reasoning", thinkingSteps))
                    ) +
                    buildSourcesBlock(webSources)
                repo.updateAiMessage(placeholderId, replyText)

                // Auto-title: if workspace is still "New Chat", generate a title
                autoTitleIfNeeded(trimmed)

                // TTS: only speak if user has enabled it in Settings
                if (!intent.reply.isNullOrBlank() && ttsEnabled.value) speak(intent.reply)
                if (commands.isNotEmpty()) {
                    // If the intent has multiple steps, use the TaskManager for
                    // Claude-like step-by-step execution with progress tracking.
                    // Single-step commands use the legacy direct execution path.
                    if (commands.size > 1 || intent.steps.size > 1) {
                        executeViaTaskManager(session, trimmed, intent, commands, placeholderId)
                    } else {
                        try {
                            executeCommands(session, trimmed, intent, commands, placeholderId)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Command execution failed: ${e.message}")
                            try { repo.log(session, LogType.STATUS_FAIL, "❌ Execution error: ${e.message}") } catch (_: Exception) {}
                        }
                    }
                } else {
                    // Pure chat answer — no tools needed. Mark the run complete.
                    agentOrchestrator.complete()
                }

                // ── Record to timeline ────────────────────────────────────
                timelineEngine.recordChat(
                    userMessage = trimmed,
                    aiReply     = intent.reply ?: replyText,
                    source      = engResult.source.name,
                    latencyMs   = System.currentTimeMillis() - startMs
                )

            } catch (e: Exception) {
                // CRASH FIX: All Room operations protected by inner try-catch.
                // If placeholder or session is invalid, the update/log calls
                // would crash the app before the error reaches the user.
                val msg = e.message ?: e::class.simpleName ?: "Unknown error"
                val err = "Error: $msg"
                try { repo.updateAiMessage(placeholderId, err) } catch (_: Exception) {}
                try { repo.log(session, LogType.STATUS_FAIL, err) } catch (_: Exception) {}
                agentOrchestrator.fail(msg)
                _uiState.update { it.copy(error = msg) }
            }
    }

    // ── Execute commands ──────────────────────────────────────────────────
    private suspend fun executeCommands(
        session: String, userInput: String,
        intent: AiIntent, commands: List<ShellCommand>,
        chatMessageId: Long? = null,
        approvedByUser: Boolean = false
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

        // ⚡ CRITICAL FIX: Pass terminalAgent so AI commands route through
        // Termux/Shizuku (persistent shell) instead of sandboxed ShellExecutor.
        // Without this, ALL AI commands bypass Termux and run in the app sandbox.
        val engine = HybridExecutionEngine(app, repo, ShellExecutor, safety, terminalAgent)
        var allSuccess = true
        val failedSteps = mutableListOf<com.interndra.data.model.ExecutionResult>()

        val chatOutputLines = mutableListOf<String>()

        // ── Track commands in UI ────────────────────────────────────────
        val cmdList = commands.mapIndexed { idx, cmd ->
            val desc = cmd.description.ifBlank { cmd.command.take(40) }
            ActiveCommand(
                id = if (idx == 0) nextCommandId() else 0L,
                command = cmd.command,
                description = desc,
                status = CommandStatus.RUNNING
            )
        }
        // Ensure unique IDs
        val trackedCmds = cmdList.map { it.copy(id = nextCommandId()) }
        _activeCommands.value = trackedCmds

        // Live agent activity: announce each command before the engine starts.
        commands.forEach { cmd ->
            agentOrchestrator.toolStart(
                tool = "terminal",
                description = cmd.description.ifBlank { cmd.command.take(60) },
                command = cmd.command
            )
        }

        engine.execute(
            session = session,
            userInput = userInput,
            intent = intent.copy(commands = commands),
            approvedByUser = approvedByUser,
            onStepResult = { result ->
            if (!result.success) { allSuccess = false; failedSteps.add(result) }
            val cmd   = commands.getOrNull(result.stepIndex)
            val label = cmd?.description?.ifBlank { null } ?: cmd?.command?.take(40) ?: "Command"

            if (result.success) {
                val outputSnippet = SensitiveDataRedactor.redact(result.output.trim()).take(600).ifBlank { "Done" }
                chatOutputLines.add("**$label**\n```\n$outputSnippet\n```")
            } else {
                val rawError = SensitiveDataRedactor.redact(result.error.trim()).take(200)
                val friendly = friendlyError(rawError)
                chatOutputLines.add("**$label** — $friendly")
            }

            // Live agent activity: report each tool result as it lands.
            agentOrchestrator.toolResult(
                tool = "terminal",
                success = result.success,
                summary = if (result.success) (cmd?.command ?: label).take(120)
                          else (result.error.ifBlank { "Command failed" }).take(120)
            )

            // Update command tracking
            _activeCommands.update { current ->
                current.map { ac ->
                    if (ac.id == trackedCmds.getOrNull(result.stepIndex)?.id) {
                        ac.copy(
                            status = if (result.success) CommandStatus.SUCCESS else CommandStatus.FAILED,
                            output = result.output.take(500),
                            error = result.error.take(500)
                        )
                    } else ac
                }
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
        )

        // ── Self-correction (Phase 8) ───────────────────────────────────
        // A failed step is diagnosed and retried ONCE with a corrected
        // invocation (drop sudo, add --yes / --no-input, capture stderr).
        // Bounded by AgentOrchestrator.maxRetriesPerTool (2).
        if (failedSteps.isNotEmpty()) {
            for (failed in failedSteps) {
                val cmd = commands.getOrNull(failed.stepIndex) ?: continue
                val corrected = correctCommand(cmd.command)
                if (corrected.isBlank() || corrected == cmd.command) continue
                agentOrchestrator.thinking("Diagnosing failure: ${friendlyError(failed.error).take(140)}")
                agentOrchestrator.toolStart("terminal", "Retrying with corrected command", corrected)
                // Run the corrected command through the TerminalBackend abstraction
                // (embedded Linux when available, Android shell fallback otherwise),
                // with an inferred timeout for the command class.
                val retryOutcome = runCatching {
                    val r = terminalBackend.execute(
                        "ai_persistent", corrected,
                        CommandPlanner.inferTimeout(corrected)
                    )
                    Triple(r.success, r.stdout, r.stderr)
                }.getOrNull()
                val retryOk = retryOutcome?.first == true
                val retryStdout = retryOutcome?.second.orEmpty()
                val retryStderr = retryOutcome?.third.orEmpty()
                if (retryOk) allSuccess = true
                agentOrchestrator.toolResult(
                    tool = "terminal",
                    success = retryOk,
                    summary = if (retryOk) "Recovered: ${cmd.command.take(100)}"
                              else "Retry failed: ${(retryStderr.ifBlank { failed.error }).take(120)}"
                )
                // Update the chat output + command tracker for the retried step.
                val retryMsg = if (retryOk) {
                    "**${cmd.description.ifBlank { cmd.command.take(40) }}** (retried)\n```\n" +
                        SensitiveDataRedactor.redact(retryStdout.trim()).take(400).ifBlank { "Recovered" } + "\n```"
                } else {
                    "**${cmd.description.ifBlank { cmd.command.take(40) }}** — retry failed: " +
                        friendlyError((retryStderr.ifBlank { failed.error }).take(160))
                }
                chatOutputLines.add(retryMsg)
                _activeCommands.update { current ->
                    current.map { ac ->
                        if (ac.id == trackedCmds.getOrNull(failed.stepIndex)?.id) {
                            ac.copy(
                                status = if (retryOk) CommandStatus.SUCCESS else CommandStatus.FAILED,
                                error = if (retryOk) "" else retryStderr.ifBlank { failed.error }.take(500)
                            )
                        } else ac
                    }
                }
            }
        }

        // Phase 11 FIX: removed the artificial `delay(200)` — engine.execute
        // runs its callback synchronously inside its forEachIndexed loop, so
        // all results are already collected by the time we reach this point.
        try {
            if (chatMessageId != null && chatOutputLines.isNotEmpty()) {
                val allOutput = chatOutputLines.joinToString("\n\n")
                val existing  = repo.getMessageText(chatMessageId) ?: ""
                // Insert output BEFORE the hidden sources marker so appended
                // command results stay visible (strip() hides the marker only).
                val updated   = if (existing.isBlank()) allOutput
                                else SourceMarker.insertBeforeMarker(existing, allOutput)
                repo.updateAiMessage(chatMessageId, updated)
            }
            // Brief delay so user sees the final status before clearing
            delay(1500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update chat message with command output: ${e.message}")
        } finally {
            // Always clear active commands — even if the above throws
            _activeCommands.value = emptyList()
        }

        // ── Verification (Phase 9) + run completion ────────────────────
        // Never claim success without checking: if every command (including
        // any self-corrected retries) succeeded, the run is verified.
        lastCommandRunSuccess = allSuccess
        if (commands.isNotEmpty()) {
            if (allSuccess) {
                repo.rememberSuccess(userInput, intent.action, commands, _uiState.value.lastAiSource?.name ?: "UNKNOWN")
                val count = repo.memoryCount()
                _uiState.update { it.copy(memoryCount = count) }
                agentOrchestrator.verifyStart("Verifying command results")
                emitAgent(AgentActivity.Verification(
                    "All ${commands.size} command(s) completed", success = true))
                agentOrchestrator.complete()
            } else {
                agentOrchestrator.fail("${failedSteps.size} command(s) failed after retries")
            }
        }
    }

    /**
     * Build a corrected invocation of a failed command — the self-correction
     * primitive. Returns the original command when no correction applies.
     */
    private fun correctCommand(command: String): String {
        var c = command.trim()
        // Termux has no sudo — the sandboxed fallback has no root either.
        if (c.startsWith("sudo ")) c = c.removePrefix("sudo ").trim()
        // Package managers: add the non-interactive flag so they never hang.
        if (c.startsWith("pkg ") && !c.contains("--yes") && !c.contains("-y ")) c += " --yes"
        if (c.startsWith("apt ") && !c.contains("--yes") && !c.contains("-y ")) c += " -y"
        if (c.startsWith("apt-get ") && !c.contains("--yes") && !c.contains("-y ")) c += " -y"
        if ((c.startsWith("pip ") || c.startsWith("pip3 ") || c.startsWith("pip install")) && !c.contains("--no-input")) c += " --no-input"
        if (c.startsWith("npm install") && !c.contains("--no-audit")) c += " --no-audit --no-fund"
        // Capture stderr so a retry failure can be diagnosed properly.
        if (!c.contains("2>&1") && !c.contains("|| ") && !c.contains("&& ")) c += " 2>&1"
        return c
    }

    /**
     * Execute commands via TaskManager for Claude-like step-by-step execution
     * with live progress tracking, pause/resume/retry/cancel support.
     */
    private suspend fun executeViaTaskManager(
        session: String, userInput: String,
        intent: AiIntent, commands: List<ShellCommand>,
        chatMessageId: Long
    ) {
        val steps = commands.mapIndexed { i, cmd ->
            val label = intent.steps.getOrNull(i)?.ifBlank { null }
                ?: cmd.description.ifBlank { null }
                ?: "Step ${i + 1}"
            label to cmd.command
        }

        val task = taskManager.createTask(
            title = intent.action.replace('_', ' ')
                .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } },
            description = intent.reply?.take(100) ?: "",
            steps = steps
        )

        // Collect step outputs to update the chat message
        val stepOutputs = mutableListOf<String>()
        val originalReply = repo.getMessageText(chatMessageId) ?: ""

        taskManager.execute(task) { step, output ->
            val icon = if (step.status == com.interndra.ai.tasks.StepStatus.COMPLETED) "✅" else "❌"
            val detail = if (output.isNotBlank()) output.take(200) else "(no output)"
            stepOutputs.add("$icon **${step.label}**\n```\n$detail\n```")

            // Live agent activity per step.
            val ok = step.status == com.interndra.ai.tasks.StepStatus.COMPLETED
            agentOrchestrator.toolStart("task", step.label, step.command)
            agentOrchestrator.toolResult("task", ok, if (ok) step.label else "Step failed", 0L)

            // Rebuild message cleanly each step (no duplication). Keep the
            // hidden sources marker at the end; insert the task block before it.
            val taskBlock = stepOutputs.joinToString("\n")
            repo.updateAiMessage(chatMessageId,
                SourceMarker.insertBeforeMarker(
                    originalReply,
                    "### ${intent.action.replace('_', ' ')}\n$taskBlock"
                ))
        }

        // Log to timeline
        val finalTask = taskManager.activeTask.value
        if (finalTask != null) {
            timelineEngine.recordCommand(
                command = intent.action,
                output = "Task: ${finalTask.completedSteps}/${finalTask.steps.size} steps completed",
                success = finalTask.status == com.interndra.ai.tasks.TaskStatus.COMPLETED,
                durationMs = finalTask.totalDurationMs
            )
            if (finalTask.status == com.interndra.ai.tasks.TaskStatus.COMPLETED) {
                repo.rememberSuccess(userInput, intent.action, commands,
                    _uiState.value.lastAiSource?.name ?: "UNKNOWN")
                _uiState.update { it.copy(memoryCount = repo.memoryCount()) }
                // Verification + completion for the multi-step task path.
                agentOrchestrator.verifyStart("Verifying task results")
                emitAgent(AgentActivity.Verification(
                    "${finalTask.completedSteps}/${finalTask.steps.size} steps completed", success = true))
                agentOrchestrator.complete()
            } else {
                agentOrchestrator.fail("Task ended with status ${finalTask.status}")
            }
        }
    }

    // ── Confirmation actions ──────────────────────────────────────────────
    fun confirmAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isLoading = true) }
        // User approved the destructive/sensitive operation — resume executing.
        agentOrchestrator.setState(AgentState.EXECUTING)
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
        repo.clearSmartMemory()
        _uiState.update { it.copy(memoryCount = 0) }
    }

    fun searchSmartMemories(query: String, onResult: (List<SmartMemoryEntity>) -> Unit) = viewModelScope.launch {
        onResult(repo.searchSmartMemories(query))
    }

    fun updateSmartMemory(memory: SmartMemoryEntity) = viewModelScope.launch {
        repo.updateSmartMemory(memory)
    }

    fun archiveSmartMemory(memory: SmartMemoryEntity) = viewModelScope.launch {
        repo.archiveSmartMemory(memory.id)
    }

    fun deleteSmartMemory(memory: SmartMemoryEntity) = viewModelScope.launch {
        repo.deleteSmartMemory(memory.id)
    }

    fun rememberUserPreference(title: String, summary: String) = viewModelScope.launch {
        repo.addSmartMemory(SmartMemoryType.USER, title, summary, importanceScore = 9)
    }

    fun rememberProjectDecision(title: String, summary: String) = viewModelScope.launch {
        val projectId = activeWorkspaceId.value
        repo.addSmartMemory(SmartMemoryType.PROJECT, title, summary, projectId = projectId, importanceScore = 9)
    }

    fun saveSmartMemorySettings(
        enabled: Boolean? = null,
        userEnabled: Boolean? = null,
        projectEnabled: Boolean? = null,
        chatEnabled: Boolean? = null,
        budget: Int? = null
    ) = viewModelScope.launch {
        app.dataStore.edit { prefs ->
            enabled?.let { prefs[SMART_MEMORY_ENABLED_PREF] = it }
            userEnabled?.let { prefs[SMART_USER_MEMORY_PREF] = it }
            projectEnabled?.let { prefs[SMART_PROJECT_MEMORY_PREF] = it }
            chatEnabled?.let { prefs[SMART_CHAT_MEMORY_PREF] = it }
            budget?.let { prefs[SMART_MEMORY_BUDGET_PREF] = it.coerceIn(100, SmartMemoryEngine.LARGE_MODEL_BUDGET).toString() }
        }
    }

    fun rememberInCurrentChat(title: String, summary: String, importance: Int = 8) = viewModelScope.launch {
        if (summary.isBlank()) return@launch
        repo.addSmartMemory(
            type = SmartMemoryType.CHAT,
            title = title.ifBlank { "Saved conversation" },
            summary = summary,
            chatId = activeWorkspaceId.value,
            importanceScore = importance
        )
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

    // ── Auto-title generation ────────────────────────────────────────────
    /**
     * If the current workspace is still named "New Chat", generate a short
     * title (2-7 words) from the first user message and update the workspace.
     * Uses keyword extraction for instant titles without additional API calls.
     */
    private fun autoTitleIfNeeded(firstUserMessage: String) {
        val state = _uiState.value
        val wsName = state.activeWorkspaceName
        if (wsName != "New Chat" && !wsName.startsWith("New")) return

        val title = generateChatTitle(firstUserMessage)
        if (title.isBlank() || title == "New Chat") return

        val wsId = state.activeWorkspaceId
        if (wsId <= 0) return

        viewModelScope.launch {
            try {
                val ws = workspaces.value.find { it.id == wsId }
                if (ws != null) {
                    repo.updateWorkspace(ws.copy(name = title))
                    _uiState.update { it.copy(activeWorkspaceName = title) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-title failed: ${e.message}")
            }
        }
    }

    /** Generate a short chat title (2-7 words) from a user message. */
    fun generateChatTitle(message: String): String = ChatTitleGenerator.generate(message)

    // ── Workspace management ──────────────────────────────────────────────
    fun createWorkspace(name: String, emoji: String = "📁", color: String = "#00E5FF") = viewModelScope.launch {
        val id = repo.createWorkspace(name, emoji, color)
        switchWorkspace(id, name)
    }
    fun renameWorkspace(workspace: Workspace, newName: String) = viewModelScope.launch {
        repo.updateWorkspace(workspace.copy(name = newName))
        if (workspace.id == _uiState.value.activeWorkspaceId) _uiState.update { it.copy(activeWorkspaceName = newName) }
    }

    /** Rename a workspace by ID (fetches the workspace first to avoid data loss). */
    fun renameWorkspaceById(workspaceId: Long, newName: String) = viewModelScope.launch {
        val ws = workspaces.value.find { it.id == workspaceId } ?: return@launch
        repo.updateWorkspace(ws.copy(name = newName))
        if (workspaceId == _uiState.value.activeWorkspaceId) _uiState.update { it.copy(activeWorkspaceName = newName) }
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
        if (useSmall) localEngine.setPreferredModel(OfflineModelCatalog.PLANNER_MODEL_ID)
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

        // ══════════════════════════════════════════════════════════════════════
    //  EMBEDDED TERMUX INSTALL (from TerminalScreen button)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Install Embedded Termux bootstrap manually (triggered from TerminalScreen).
     * After successful install, syncs TerminalAgent mode and starts PTY session.
     */
    fun installEmbeddedTermux(
        onProgress: ((String) -> Unit)? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onProgress?.invoke("📦 Installing Embedded Termux bootstrap...") }
                val result = termuxBootstrapInstaller.install(progressCallback = { progress ->
                    viewModelScope.launch(Dispatchers.Main) { onProgress?.invoke(progress) }
                })
                if (result.success) {
                    termuxEnvironment.refreshStatus()
                    // ⚡ FIX: Sync TerminalAgent mode from environment + invalidate cache
                    terminalAgent.syncModeFromEnvironment()
                    cachedRuntimeCaps = null
                    val envMode = termuxEnvironment.getMode()
                    terminalAgent.switchMode(envMode)
                    if (termuxEnvironment.hasTermux()) {
                        val envInfo = termuxEnvironment.info.value
                        val config = TerminalSession.TermuxSessionConfig(
                            prefix = envInfo.bootstrapPrefix,
                            homeDir = "${envInfo.bootstrapPrefix}/home",
                            shellPath = "${envInfo.bootstrapPrefix}/usr/bin/bash"
                        )
                        terminalAgent.startPtySession(config)
                    }
                    withContext(Dispatchers.Main) { onComplete?.invoke(true, "✅ Embedded Termux installed!") }
                } else {
                    withContext(Dispatchers.Main) { onComplete?.invoke(false, "❌ Installation failed: ${result.error?.take(200) ?: "Unknown error"}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete?.invoke(false, "❌ Error: ${e.message?.take(200) ?: "Unknown error"}") }
            }
        }
    }

    // ── Shizuku management ────────────────────────────────────────────────

    /**
     * Request Shizuku authorization from the user.
     * This opens the Shizuku authorization dialog if not already authorized.
     */
    fun requestShizukuPermission() {
        val started = shizukuManager.ensureAuthorized { granted ->
            _shizukuAuthorized.value = granted
            if (granted) {
                shizukuManager.refreshStatus()
                _shizukuUid.value = shizukuManager.shizukuUid
                Log.i(TAG, "Shizuku permission granted! UID=${shizukuManager.shizukuUid}")
            } else {
                Log.w(TAG, "Shizuku permission denied by user")
            }
        }
        if (!started) {
            // Binder not alive — guide user to start Shizuku first
            _uiState.update { it.copy(error = "Shizuku is not running. Please open the Shizuku app and start it first.") }
        }
    }

    /** Refresh Shizuku status. */
    fun refreshShizukuStatus() {
        shizukuManager.refreshStatus()
        _shizukuAvailable.value = shizukuManager.isBinderAlive
        _shizukuAuthorized.value = shizukuManager.isAuthorized()
        _shizukuUid.value = shizukuManager.shizukuUid
        // ⚡ FIX: Sync TerminalAgent mode — Shizuku status changes may affect exec mode
        terminalAgent.syncModeFromEnvironment()
        // Invalidate runtime capabilities cache so the next prompt sees fresh state.
        cachedRuntimeCaps = null
    }

    /** Periodically re-check Shizuku health so the UI doesn't lie about authorization. */
    private fun startShizukuHealthCheck(intervalMs: Long = 15_000L) {
        viewModelScope.launch {
            while (true) {
                delay(intervalMs)
                try {
                    val previouslyAuthorized = _shizukuAuthorized.value
                    refreshShizukuStatus()
                    if (previouslyAuthorized && !_shizukuAuthorized.value) {
                        Log.w(TAG, "Shizuku authorization lost — persistent shell may need restart")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku health check failed: ${e.message}")
                }
            }
        }
    }

    /** Whether Shizuku is the active execution backend. */
    val executionBackendDescription: String get() = terminalAgent.executionBackendDescription

    /**
     * Build a runtime context block describing the current shell environment.
     * This is injected into the AI system prompt so the model knows what
     * commands are actually available before generating shell commands.
     */
    fun buildTerminalRuntimeContext(): String {
        val sb = StringBuilder()
        sb.appendLine("### ⚡ CURRENT TERMINAL STATE (live from device)")
        sb.appendLine()
        
        val caps = cachedRuntimeCaps ?: AICommandRegistry.detectRuntimeCapabilities(app, shizukuShell, termuxEnvironment).also { cachedRuntimeCaps = it }
        val envInfo = termuxEnvironment.info.value
        
        // ── Direct terminal access status (most important for AI) ──
        val accessLevel = when {
            caps.isShizukuAuthorized && caps.shizukuPrivilegeLevel.contains("Root", ignoreCase = true) -> "✅ FULL ROOT ACCESS (Shizuku authorized)"
            caps.isShizukuAuthorized -> "✅ ELEVATED ACCESS (Shizuku authorized — UID ${shizukuManager.shizukuUid})"
            caps.hasEmbeddedTermux -> "✅ EMBEDDED TERMUX (bash + packages)"
            else -> "⚠️ SANDBOXED (basic shell, no elevated access)"
        }
        sb.appendLine("- **Terminal Access**: $accessLevel")
        sb.appendLine("- **Execution Backend**: ${terminalAgent.executionBackendDescription}")
        sb.appendLine("- **Execution Mode**: ${terminalAgent.getModeDescription()}")
        sb.appendLine("- **Shizuku**: ${if (caps.isShizukuAuthorized) "✅ Authorized (${caps.shizukuPrivilegeLevel})" else "❌ Not authorized"}")
        sb.appendLine()
        
        // ── Shizuku details ──
        if (caps.hasShizuku) {
            sb.appendLine("- Shizuku app installed: ✅")
            sb.appendLine("- Shizuku authorized: ${if (caps.isShizukuAuthorized) "✅ YES — You have elevated shell access" else "❌ NO — User needs to authorize in Shizuku app"}")
            sb.appendLine("- Privilege: ${caps.shizukuPrivilegeLevel}")
        } else {
            sb.appendLine("- Shizuku app installed: ❌ No")
        }
        
        // ── Execution backend priority ──
        sb.appendLine()
        sb.appendLine("**📊 EXECUTION BACKEND PRIORITY:**")
        if (caps.isShizukuAuthorized) {
            sb.appendLine("🚀 **1. Shizuku 🛡️ [PRIMARY] ✅ Authorized** — ${caps.shizukuPrivilegeLevel}")
            sb.appendLine("   - Run ANY shell command system-wide")
            sb.appendLine("   - Access all device storage, settings, installed packages")
            sb.appendLine("   - Elevated `pm install`, `settings put`, system_service commands")
            sb.appendLine("   - Embedded Termux available as secondary")
        } else if (caps.hasEmbeddedTermux) {
            sb.appendLine("🐧 **1. Shizuku ❌ Not authorized** — user needs to authorize in Shizuku app")
            sb.appendLine("🚀 **2. Embedded Termux 🐧 [ACTIVE] ✅ Installed** — python3, git, node, npm, pip")
            sb.appendLine("   - Install packages via apt/pkg")
            sb.appendLine("   - Full bash environment")
        } else {
            sb.appendLine("🚫 **1. Shizuku 🛡️ ❌ Not authorized** — user needs to authorize in Shizuku app")
            sb.appendLine("🚫 **2. Embedded Termux 🐧 ❌ Not installed** — python/git/node unavailable")
            sb.appendLine("⚠️ **3. ShellExecutor ⚠️ [FALLBACK ONLY] ✅ Available** — basic commands only")
            sb.appendLine("   - `ls`, `cd`, `cat`, `echo`, `pwd`, `grep`, `find`")
            sb.appendLine("   - `df -h`, `free -h`, `ps -A`, `dumpsys battery`")
            sb.appendLine("   - `ping`, `curl`, `wget` — network")
            sb.appendLine("   - `pm`, `am`, `input`, `settings` — Android tools")
            sb.appendLine("   - Limited to app dirs + shared storage")
            sb.appendLine("   - User can authorize Shizuku in the drawer menu for full access")
        }
        sb.appendLine()
        
        // ── Session details ──
        sb.appendLine("- **Current workdir**: `${terminalAgent.getWorkdir(activeTerminalSession.value)}`")
        
        // ── Termux info ──
        if (caps.hasTermux) sb.appendLine("- External Termux app (optional): ✅ installed${if (!caps.hasTermuxPermission) " (RUN_COMMAND not granted — Embedded Termux used instead)" else ""}")
        else sb.appendLine("- External Termux app: ❌ not installed (not needed — Embedded Termux handles all packages)")
        
        // Embedded Termux info
        if (envInfo.bootstrapInstalled) {
            sb.appendLine("- Embedded Termux: ✅ ready")
            sb.appendLine("- Termux prefix: `${envInfo.bootstrapPrefix}`")
            sb.appendLine("- Bash available: ${if (envInfo.bashAvailable) "yes" else "no"}")
            sb.appendLine("- APT/pkg available: ${if (envInfo.aptAvailable) "yes" else "no"}")
            if (envInfo.installedPackages.isNotEmpty()) {
                sb.appendLine("- Installed packages (${envInfo.installedPackages.size}): ${envInfo.installedPackages.take(10).joinToString(", ")}")
            }
            sb.appendLine("- Available commands: pkg install, apt, python3, git, node, npm, pip")
            sb.appendLine("- To install: `pkg install python git nodejs` etc.")
            sb.appendLine("- Switch mode: say \"switch to Shizuku\" for system commands")
        } else {
            sb.appendLine("- Embedded Termux: ❌ not installed — use action \"install_bootstrap\" to install it now (25MB download, 30-60s)")
        }

        val recentOutput = terminalAgent.getOutputLines(activeTerminalSession.value)
            .takeLast(10)
            .map { stripAnsi(it).take(120) }
        if (recentOutput.isNotEmpty()) {
            sb.appendLine("- Recent terminal output (last ${recentOutput.size} lines):")
            recentOutput.forEach { line ->
                sb.appendLine("    $line")
            }
        }

        val envVars = if (envInfo.bootstrapInstalled && envInfo.bootstrapPrefix.isNotBlank()) {
            "${envInfo.bootstrapPrefix}/usr/bin:/system/bin:/system/xbin"
        } else {
            terminalAgent.getEnv(activeTerminalSession.value, "PATH") ?: "/system/bin"
        }
        sb.appendLine("- PATH: $envVars")
        sb.appendLine("- Instruction: Use ADB_SHELL for basic commands, TERMUX for dev tools, SHIZUKU for system commands. Embedded Termux has pkg/apt/python/git/node. Switch with \"switch to Shizuku\".")
        return sb.toString().trimEnd()
    }

    /** Strip ANSI escape sequences so terminal output doesn't confuse the AI. */
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    }

    fun refreshStatus() {
        // Phase 2 FIX: previously read privacyMode.value which could still be
        // the default HYBRID before DataStore emitted on cold start. Now sync
        // a11y + model-ready + emergency-lock; privacyMode StateFlow self-corrects.
        // Phase 9 FIX: pass app context to isEnabled() so it does a real system
        // query instead of falling back to the stale _cachedEnabled flag.
        // UPGRADE: Wrap everything in try-catch so a failure in any component
        // snapshot (a11y, model, DataStore) doesn't crash the app.
        try {
            val a11y  = AgentAccessibilityService.isEnabled(app)
            val ready = localEngine.isModelDownloaded()
            val lock  = try { emergencyLockPersisted.value } catch (_: Exception) { false }
            _uiState.update { it.copy(
                a11yEnabled         = a11y,
                localModelReady     = ready,
                emergencyLockActive = lock
            )}
        } catch (e: Exception) {
            Log.e(TAG, "refreshStatus failed: ${e.message}")
        }
    }

    // ── Export logs ───────────────────────────────────────────────────────
    fun exportLogs(onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val logs     = terminalLogs.value
            val messages = messages.value
            val vault    = knowledgeEntries.value
            val sb       = StringBuilder()
            sb.appendLine("=== INTENDRA Export ===")
            sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            sb.appendLine("Chat messages: ${messages.size} | Logs: ${logs.size} | Knowledge: ${vault.size}")
            sb.appendLine("\n=== Chat Messages ===")
            messages.forEach { msg ->
                sb.appendLine("[${msg.role}] ${SensitiveDataRedactor.redact(com.interndra.ai.ThinkingMarker.strip(SourceMarker.strip(msg.content)))}")
            }
            sb.appendLine("\n=== Terminal Logs ===")
            logs.forEach { log ->
                sb.appendLine("[${log.logType}] ${SensitiveDataRedactor.redact(log.content)}")
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
     * Embeds web-search sources as a hidden marker block at the end of the AI
     * reply. The reply text itself stays clean — no links dumped into the
     * chat. The UI (HybridChatScreen) parses the marker and renders a
     * collapsible "Sources" box below the message; tapping it reveals the
     * links. See [com.interndra.search.SourceMarker].
     */
    private fun buildSourcesBlock(sources: List<SearchResult>): String =
        SourceMarker.encode(sources)

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
            sb.append("Got it! Let me handle this for you. 🔧")
        }

        return sb.toString().trim()
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
        localEngine.close()
        shizukuManager.shutdown()
        terminalAgent.shutdown()
        healthMonitor.saveReport()
    }
}
