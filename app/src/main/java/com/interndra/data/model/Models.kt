package com.interndra.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Chat message ────────────────────────────────────────────────────────────
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val workspaceId: Long = 0L
)

enum class MessageRole { USER, AI }

// ── Terminal log ────────────────────────────────────────────────────────────
@Entity(tableName = "terminal_logs")
data class TerminalLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val logType: LogType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LogType {
    AI_INPUT, AI_INTENT, EXECUTION_PLAN,
    COMMAND, TERMUX_CMD,
    STATUS_OK, STATUS_FAIL, INFO
}

// ── Persistent Memory ────────────────────────────────────────────────────────
@Entity(tableName = "memories")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val workspaceId: Long = 0L,
    val tags: String = "",
    val importanceScore: Int = 5,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val actionType: String = "",
    val commandsJson: String = ""
)

// ── Workspaces ───────────────────────────────────────────────────────────────
@Entity(tableName = "workspaces")
data class Workspace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val colorHex: String = "#00E5FF",
    val emoji: String = "📁",
    val persona: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ── Network Transparency ─────────────────────────────────────────────────────
@Entity(tableName = "network_events")
data class NetworkEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val feature: String,
    val method: String = "POST",
    val dataSentBytes: Int = 0,
    val wasBlocked: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Knowledge Vault entry ────────────────────────────────────────────────────
@Entity(tableName = "knowledge_entries")
data class KnowledgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val type: KnowledgeType = KnowledgeType.NOTE,
    val tags: String = "",
    val sourceUrl: String = "",
    val filePath: String = "",
    val wordCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class KnowledgeType(val emoji: String, val label: String) {
    NOTE("📝", "Note"),
    DOCUMENT("📄", "Document"),
    WEB_CLIP("🌐", "Web Clip"),
    CODE("💻", "Code"),
    RESEARCH("🔬", "Research"),
    IMAGE("🖼️", "Image (OCR)"),
    FORMULA("⚗️", "Formula")
}

// ── Timeline entry ───────────────────────────────────────────────────────────
@Entity(tableName = "timeline_entries")
data class TimelineEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TimelineEventType,
    val title: String,
    val detail: String = "",
    val outcome: String = "",
    val durationMs: Long = 0L,
    val tags: String = "",
    val relatedId: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

enum class TimelineEventType(val emoji: String, val label: String) {
    AI_CHAT("💬", "AI Chat"),
    SHELL_COMMAND("⚡", "Shell Command"),
    KNOWLEDGE_ADD("📚", "Knowledge Added"),
    KNOWLEDGE_DELETE("🗑️", "Knowledge Deleted"),
    AUTOMATION_FIRED("🤖", "Automation Fired"),
    MODEL_EVENT("🧠", "Model Event"),
    OCR_RUN("👁️", "OCR Run"),
    FILE_IMPORT("📥", "File Import"),
    SETTINGS_CHANGE("⚙️", "Settings Change"),
    SECURITY_EVENT("🔒", "Security Event")
}

// ── Plugin entry ─────────────────────────────────────────────────────────────
@Entity(tableName = "plugin_entries")
data class PluginEntry(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val status: PluginStatus = PluginStatus.ACTIVE,
    val commands: String = "",       // comma-separated list of supported commands
    val installedAt: Long = System.currentTimeMillis()
)

enum class PluginStatus { ACTIVE, DISABLED, ERROR }


enum class AutomationTriggerType {
    TIME,           // time-based trigger
    APP_OPEN,       // when app is opened
    NOTIFICATION,   // on notification arrival
    BATTERY_LOW,    // when battery below threshold
    WIFI_CONNECTED, // on wifi connection
    MANUAL          // user-triggered
}

// ── Automation rule ──────────────────────────────────────────────────────────
@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val commandType: String,          // "ADB_SHELL" | "ANDROID_INTENT"
    val command: String,
    val delayMinutes: Int = 0,
    val triggerCondition: String = "", // e.g. "on_whatsapp_message:Rahul"
    val isEnabled: Boolean = true,
    val runCount: Int = 0,
    val lastRunAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

// ── Non-persisted data models ────────────────────────────────────────────────
data class AiIntent(
    val action: String,
    val reply: String? = null,
    val app: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val query: String? = null,
    val delayMinutes: Long? = null,
    val triggerCondition: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val steps: List<String> = emptyList(),
    val commands: List<ShellCommand> = emptyList()
)

data class ShellCommand(
    val type: CommandType,
    val command: String,
    val description: String = "",
    /**
     * If true, failure of this step aborts the whole command chain.
     * Defaults to true for the FIRST step in an intent, false otherwise
     * (set explicitly by AiOrchestrator / WorkflowPlanner).
     */
    val critical: Boolean = false
)

enum class CommandType { ADB_SHELL, TERMUX, ANDROID_INTENT, ACCESSIBILITY }

/**
 * A structured, multi-step workflow.
 *
 * Phase 6 — Workflow Engine. Replaces the ad-hoc `List<ShellCommand>` with
 * a first-class object the planner can compose, validate, schedule, and
 * replay. Steps execute in order; a step may declare `dependsOn` for
 * future parallel-execution support and `condition` for branching.
 */
data class Workflow(
    val name: String,
    val description: String,
    val steps: List<WorkflowStep>,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class WorkflowStep(
    val label: String,
    val command: ShellCommand,
    val dependsOn: List<Int> = emptyList(),
    val condition: String? = null,  // optional guard expression, e.g. "prev.success"
    val maxRetries: Int = 0,        // 0 = no retry, 1+ = retry count
    val timeoutMs: Long = 30_000L,  // per-step timeout in ms
    val resultVar: String? = null   // store result in variable for chaining (e.g. "foundFiles")
)

data class WorkflowRunResult(
    val workflowName: String,
    val stepResults: List<ExecutionResult>,
    val overallSuccess: Boolean,
    val durationMs: Long
) {
    val succeededSteps: Int get() = stepResults.count { it.success }
    val failedSteps:   Int get() = stepResults.count { !it.success }
}

enum class PrivacyMode(val emoji: String, val label: String) {
    LOCAL_ONLY("🔒", "Local Only"),
    CLOUD_ENHANCED("☁️", "Cloud Enhanced"),
    HYBRID("⚡", "Hybrid")
}

enum class AiSource(val emoji: String, val label: String) {
    LOCAL("📱", "Local"),
    CLOUD("☁️", "Cloud"),
    FALLBACK("⚠️", "Fallback")
}

data class CommandMemory(
    val userInput: String,
    val aiIntent: String,
    val success: Boolean,
    val actionType: String = "",
    val commandsJson: String = ""
)

data class AiEngineResult(
    val intentJson: String,
    val source: AiSource,
    val isSuccess: Boolean = true,
    val error: String? = null,
    val latencyMs: Long = 0,
    val modelUsed: String = "",
    val tokenCount: Int = 0
)

@Entity(tableName = "web_search_cache")
data class WebSearchCache(
    @PrimaryKey val query: String,
    val jsonResults: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SearchSource(
    val title: String,
    val url: String,
    val content: String,
    val priority: Int = 3
)

data class ExecutionResult(
    val stepIndex: Int,
    val success: Boolean,
    val output: String = "",
    val error: String = ""
)

data class CloudConsentRequest(
    val userInput: String,
    val reason: String,
    val destinationDomain: String,
    val onAllow: suspend () -> Unit,
    val onDeny: () -> Unit
)
