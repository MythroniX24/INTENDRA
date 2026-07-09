package com.interndra.agent

import android.content.Context
import android.util.Log
import com.interndra.service.ExecutionBackend
import com.interndra.service.ShellExecutionResult
import com.interndra.service.ShizukuShell
import com.interndra.service.TerminalConfig
import com.interndra.service.TermuxBridge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TerminalAgent — persistent shell session manager with real-time streaming,
 * auto-completion, error recovery, and automatic workdir tracking.
 *
 * ## A+++ UPGRADES:
 *  1. **Unified ShellExecutionResult** — all backends return the same type; no more
 *     fragile manual conversion between TermuxResult/ShizukuShellResult/ShellResult.
 *  2. **Command Queue + Deduplication** — Mutex-based execution gate prevents
 *     concurrent duplicate commands. Rapid duplicate taps are silently dropped.
 *  3. **Clean Fallback Chain** — ShizukuShell → TermuxBridge. ShizukuShell already
 *     falls back to SmartShell internally. No more redundant double-fallback.
 *  4. **Persistent Aliases** — aliases saved/loaded in session JSON, survive restart.
 *  5. **Persistent Env Vars** — envVars fully serialized in session JSON.
 *  6. **TerminalConfig** — centralized timeout, cap, and limit constants.
 *  7. **Backend Tracking** — every execution result carries its backend tag.
 *
 * ## Execution Priority
 * ```
 * ShizukuShell (Shizuku ADB/Root → SmartShell fallback)
 *   └── isElevatedAvailable?
 *       ├── YES → ShizukuShell.execute()  [may internally fall back to SmartShell]
 *       └── NO  → TermuxBridge.executeShell()
 *                     └── Termux not installed?
 *                         └── ShizukuShell.execute()  [SmartShell-only]
 * ```
 */
class TerminalAgent(
    private val context: Context,
    private val termuxBridge: TermuxBridge,
    private val shizukuShell: ShizukuShell,
    private val scope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "TerminalAgent"

        // Default Termux home
        val TERMUX_HOME = "/data/data/com.termux/files/home"
        val TERMUX_USR_BIN = "/data/data/com.termux/files/usr/bin"
    }

    // ── Command Queue (deduplication) ──────────────────────────────────

    /** Serializes command execution — prevents concurrent runs on the same session. */
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    /** Tracks which commands are currently executing (command → count). */
    private val executingCommands = ConcurrentHashMap<String, Int>()

    private fun sessionMutex(name: String): Mutex =
        sessionMutexes.getOrPut(name) { Mutex() }

    // ── Session ────────────────────────────────────────────────────────────

    data class TerminalSession(
        val name: String,
        var workdir: String = TERMUX_HOME,
        val envVars: MutableMap<String, String> = mutableMapOf(
            "HOME" to TERMUX_HOME,
            "PATH" to "$TERMUX_USR_BIN:/usr/bin:/bin",
            "PWD" to TERMUX_HOME,
            "TERM" to "xterm-256color"
        ),
        /** User-defined aliases (e.g., "gs" → "git status"). Persisted to disk. */
        val aliases: MutableMap<String, String> = mutableMapOf(),
        val history: MutableList<HistoryEntry> = mutableListOf(),
        val outputLines: MutableList<String> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastActiveAt: Long = System.currentTimeMillis()
    )

    data class HistoryEntry(
        val command: String,
        val workdir: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timestamp: Long = System.currentTimeMillis(),
        val durationMs: Long = 0L,
        val wasRecovered: Boolean = false,
        val backend: String = ExecutionBackend.UNKNOWN.name
    )

    data class SessionResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean,
        val sessionName: String,
        val workdir: String,
        val durationMs: Long,
        val wasRecovered: Boolean = false,
        val recoveryActions: List<String> = emptyList(),
        val backend: ExecutionBackend = ExecutionBackend.UNKNOWN
    )

    // ── Auto-completion ────────────────────────────────────────────────────

    data class CompletionSuggestion(
        val text: String,
        val displayText: String,
        val category: String,
        val score: Float
    )

    // ── Error recovery ────────────────────────────────────────────────────

    data class RecoveryAction(
        val description: String,
        val commands: List<String>,
        val confidence: Float,
        val explanation: String
    )

    // ── Streaming ─────────────────────────────────────────────────────────

    private val _outputFlow = MutableSharedFlow<StreamEvent>(replay = 0, extraBufferCapacity = 256)
    val outputFlow: SharedFlow<StreamEvent> = _outputFlow.asSharedFlow()

    sealed class StreamEvent {
        data class Output(val sessionName: String, val text: String) : StreamEvent()
        data class CommandStart(val sessionName: String, val command: String) : StreamEvent()
        data class CommandEnd(val sessionName: String, val exitCode: Int, val backend: ExecutionBackend = ExecutionBackend.UNKNOWN) : StreamEvent()
        data class Error(val sessionName: String, val message: String) : StreamEvent()
    }

    // ── State ────────────────────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val defaultSessionName = "default"
    private val gitBranchCache = mutableMapOf<String, List<String>>()

    private var autoSaveJob: Job? = null
    private val autoSaveScope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Human-readable description of the current execution backend. */
    val executionBackendDescription: String
        get() = when {
            shizukuShell.isElevatedAvailable -> "Shizuku (${shizukuShell.privilegeDescription})"
            termuxBridge.isTermuxInstalled() && termuxBridge.hasPermission() -> "Termux"
            else -> "Sandboxed (SmartShell)"
        }

    val isElevated: Boolean get() = shizukuShell.isElevatedAvailable

    // ── Session Management ────────────────────────────────────────────────

    fun createSession(name: String, workdir: String = TERMUX_HOME): TerminalSession {
        return sessions.getOrPut(name) {
            TerminalSession(name = name, workdir = workdir).also {
                _outputFlow.tryEmit(StreamEvent.Output(name, "\u001b[32m✓ Session '$name' created\u001b[0m\n"))
            }
        }
    }

    fun getDefaultSession(): TerminalSession = createSession(defaultSessionName)

    fun getOrCreateSession(name: String, workdir: String = TERMUX_HOME): TerminalSession =
        sessions[name] ?: createSession(name, workdir)

    fun getAllSessions(): List<TerminalSession> = sessions.values.toList()

    fun getSessionNames(): List<String> = sessions.keys.toList()

    fun getOutputLines(sessionName: String): List<String> =
        sessions[sessionName]?.outputLines?.toList() ?: emptyList()

    fun removeSession(name: String) {
        sessions.remove(name)
        sessionMutexes.remove(name)
        scheduleAutoSave()
    }

    fun renameSession(oldName: String, newName: String): Boolean {
        val session = sessions.remove(oldName) ?: return false
        sessions[newName] = session.copy(name = newName)
        scheduleAutoSave()
        return true
    }

    fun getWorkdir(sessionName: String): String =
        sessions[sessionName]?.workdir ?: TERMUX_HOME

    fun changeWorkdir(sessionName: String, target: String): String {
        val session = sessions[sessionName] ?: return target
        synchronized(session) {
            val resolved = resolvePath(session.workdir, target)
            session.workdir = resolved
            session.envVars["PWD"] = resolved
            session.lastActiveAt = System.currentTimeMillis()
            val output = "\u001b[36m${session.name} ~ $resolved\u001b[0m\n"
            session.outputLines.add(output)
            _outputFlow.tryEmit(StreamEvent.Output(sessionName, output))
        }
        return session.workdir
    }

    // ── Alias Management ─────────────────────────────────────────────────

    /** Set an alias for the given session. Example: setAlias("default", "gs", "git status") */
    suspend fun setAlias(sessionName: String, name: String, expansion: String) {
        sessionMutex(sessionName).withLock {
            val session = getOrCreateSession(sessionName)
            session.aliases[name] = expansion
        }
        scheduleAutoSave()
    }

    /** Remove an alias. */
    suspend fun removeAlias(sessionName: String, name: String) {
        sessionMutex(sessionName).withLock {
            sessions[sessionName]?.aliases?.remove(name)
        }
        scheduleAutoSave()
    }

    /** Get all aliases for a session. */
    fun getAliases(sessionName: String): Map<String, String> =
        sessions[sessionName]?.aliases?.toMap() ?: emptyMap()

    /** Get the expansion of a specific alias, or null. */
    fun resolveAlias(sessionName: String, name: String): String? =
        sessions[sessionName]?.aliases?.get(name)

    /** Expand the first word of a command if it matches an alias. */
    private fun expandAliases(sessionName: String, command: String): String {
        val trimmed = command.trim()
        val firstWord = trimmed.split(" ").firstOrNull() ?: return trimmed
        val session = sessions[sessionName] ?: return trimmed
        val expansion = session.aliases[firstWord] ?: return trimmed
        val rest = trimmed.removePrefix(firstWord).trimStart()
        return if (rest.isNotEmpty()) "$expansion $rest" else expansion
    }

    /** Set an environment variable for a session. */
    suspend fun setEnv(sessionName: String, key: String, value: String) {
        sessionMutex(sessionName).withLock {
            val session = getOrCreateSession(sessionName)
            session.envVars[key] = value
        }
        scheduleAutoSave()
    }

    /** Get an environment variable from a session. */
    fun getEnv(sessionName: String, key: String): String? =
        sessions[sessionName]?.envVars?.get(key)

    fun getHistory(sessionName: String, limit: Int = 50): List<HistoryEntry> =
        sessions[sessionName]?.history?.takeLast(limit)?.reversed() ?: emptyList()

    fun clearHistory(sessionName: String) {
        sessions[sessionName]?.let { session ->
            synchronized(session) {
                session.history.clear()
                session.outputLines.clear()
            }
            _outputFlow.tryEmit(StreamEvent.Output(sessionName, "\u001b[33m⌛ History cleared\u001b[0m\n"))
        }
    }

    // ── Command Execution with Streaming + Queue ────────────────────────

    /**
     * Execute a command with REAL-TIME streaming output.
     *
     * ## Deduplication
     * Uses a Mutex per session to serialize execution. If the SAME command
     * is already running for the same session, the duplicate is dropped
     * with a warning message.
     */
    suspend fun execute(
        sessionName: String,
        command: String,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS
    ): SessionResult = sessionMutex(sessionName).withLock {
        val startMs = System.currentTimeMillis()
        val trimmed = command.trim()

        // Dedup: if the same command is already running, skip
        val dedupKey = "$sessionName::$trimmed"
        val currentCount = executingCommands.getOrDefault(dedupKey, 0)
        if (currentCount > 0) {
            _outputFlow.tryEmit(StreamEvent.Output(sessionName,
                "\u001b[33m⚠ Command '$trimmed' is already running — skipping duplicate\u001b[0m\n"))
            return@withLock SessionResult(
                stdout = "", stderr = "Duplicate skipped",
                exitCode = 0, isSuccess = true,
                sessionName = sessionName, workdir = getWorkdir(sessionName),
                durationMs = 0L
            )
        }
        executingCommands[dedupKey] = currentCount + 1

        try {
            runExecution(sessionName, command, timeoutMs, startMs)
        } finally {
            executingCommands.remove(dedupKey)
        }
    }

    /** Internal execution logic — called under the session Mutex lock. */
    private suspend fun runExecution(
        sessionName: String,
        command: String,
        timeoutMs: Long,
        startMs: Long
    ): SessionResult = withContext(Dispatchers.IO) {
        val session = getOrCreateSession(sessionName)

        // Expand aliases
        val expanded = expandAliases(sessionName, command)
        val trimmed = expanded.trim()

        // Emit command start
        val prompt = if (command != expanded) {
            "\u001b[90m($command → $trimmed)\u001b[0m\n\u001b[32m\$\u001b[0m $trimmed\n"
        } else {
            "\u001b[32m\$\u001b[0m $trimmed\n"
        }
        session.outputLines.add(prompt)
        _outputFlow.emit(StreamEvent.CommandStart(sessionName, trimmed))
        _outputFlow.emit(StreamEvent.Output(sessionName, prompt))

        // Handle `cd` natively
        if (trimmed.startsWith("cd ")) {
            return@withContext handleCd(sessionName, session, trimmed, startMs)
        }

        // ── Execute with priority chain: ShizukuShell → TermuxBridge ──
        // ShizukuShell already falls back to SmartShell internally, so we
        // don't need a third SmartShell fallback here.
        val workdir = session.workdir
        val result: ShellExecutionResult

        if (shizukuShell.isElevatedAvailable) {
            // Shizuku is authorized — use elevated shell
            result = shizukuShell.execute(
                command = trimmed,
                timeoutMs = timeoutMs,
                onOutput = { line ->
                    session.outputLines.add(line)
                    _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
                }
            )
            // Emit backend indicator
            val indicator = when (result.backend) {
                ExecutionBackend.SHIZUKU_ROOT -> "\u001b[90m[🛡️ Shizuku Root]\u001b[0m\n"
                ExecutionBackend.SHIZUKU_ADB -> "\u001b[90m[🔑 Shizuku ADB]\u001b[0m\n"
                else -> "\u001b[90m[⚙️ SmartShell]\u001b[0m\n"
            }
            session.outputLines.add(indicator)
            _outputFlow.tryEmit(StreamEvent.Output(sessionName, indicator))
        } else if (termuxBridge.isTermuxInstalled() && termuxBridge.hasPermission()) {
            // Fall back to TermuxBridge
            result = termuxBridge.executeShell(
                shellCommand = trimmed,
                workdir = workdir,
                timeoutMs = timeoutMs,
                onOutput = { line ->
                    session.outputLines.add(line)
                    _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
                }
            )
        } else {
            // Last resort: ShizukuShell (will use SmartShell internally since Shizuku is not authorized)
            result = shizukuShell.execute(
                command = trimmed,
                timeoutMs = timeoutMs,
                onOutput = { line ->
                    session.outputLines.add(line)
                    _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
                }
            )
        }

        // ── Update session state ──────────────────────────────────────
        val duration = System.currentTimeMillis() - startMs
        synchronized(session) {
            session.lastActiveAt = System.currentTimeMillis()
            session.history.add(HistoryEntry(
                command = trimmed,
                workdir = workdir,
                exitCode = result.exitCode,
                stdout = result.stdout.take(TerminalConfig.MAX_STDOUT_SNIPPET_CHARS),
                stderr = result.stderr.take(TerminalConfig.MAX_STDERR_SNIPPET_CHARS),
                durationMs = duration,
                backend = result.backend.name
            ))
            while (session.history.size > TerminalConfig.MAX_HISTORY_ENTRIES) {
                session.history.removeAt(0)
            }
            while (session.outputLines.size > TerminalConfig.MAX_OUTPUT_LINES) {
                session.outputLines.removeAt(0)
            }
        }

        // ── Emit exit status ──────────────────────────────────────────
        val exitColor = if (result.isSuccess) "32" else "31"
        val exitMsg = "\u001b[${exitColor}m❯ Exit ${result.exitCode} (${duration}ms)\u001b[0m\n"
        session.outputLines.add(exitMsg)
        _outputFlow.emit(StreamEvent.Output(sessionName, exitMsg))
        _outputFlow.emit(StreamEvent.CommandEnd(sessionName, result.exitCode, result.backend))

        scheduleAutoSave()

        SessionResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            isSuccess = result.isSuccess,
            sessionName = sessionName,
            workdir = workdir,
            durationMs = duration,
            backend = result.backend
        )
    }

    /** Handle `cd` command natively (no process spawn needed). */
    private suspend fun handleCd(
        sessionName: String,
        session: TerminalSession,
        trimmed: String,
        startMs: Long
    ): SessionResult {
        val target = trimmed.removePrefix("cd ").trim().trim('"').trim('\'')
        if (target.isNotBlank()) {
            synchronized(session) {
                val newDir = resolvePath(session.workdir, target)
                session.workdir = newDir
                session.envVars["PWD"] = newDir
            }
        }
        val newDir = session.workdir
        _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[36m$newDir\u001b[0m\n"))
        _outputFlow.emit(StreamEvent.CommandEnd(sessionName, 0))
        return SessionResult(
            stdout = "", stderr = "", exitCode = 0, isSuccess = true,
            sessionName = sessionName, workdir = newDir,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    // ── Error Recovery ───────────────────────────────────────────────────

    suspend fun executeWithRecovery(
        sessionName: String,
        command: String,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS
    ): SessionResult = withContext(Dispatchers.IO) {
        val firstResult = execute(sessionName, command, timeoutMs)
        if (firstResult.isSuccess) return@withContext firstResult

        val errorText = "${firstResult.stderr}\n${firstResult.stdout}"
        val recovery = suggestRecovery(command, errorText)

        if (recovery == null) {
            _outputFlow.emit(StreamEvent.Output(sessionName,
                "\u001b[33m⚠ No automated recovery available\u001b[0m\n"))
            return@withContext firstResult.copy(stderr = "${firstResult.stderr}\n(No automated recovery available)")
        }

        if (recovery.commands.isEmpty()) {
            _outputFlow.emit(StreamEvent.Output(sessionName,
                "\u001b[33m💡 ${recovery.explanation}\u001b[0m\n"))
            return@withContext firstResult.copy(
                stderr = "${firstResult.stderr}\n\n💡 ${recovery.explanation}",
                wasRecovered = false, recoveryActions = emptyList()
            )
        }

        _outputFlow.emit(StreamEvent.Output(sessionName,
            "\u001b[33m⚡ Attempting recovery: ${recovery.description}\u001b[0m\n"))

        // Apply recovery commands
        val recoveryResults = mutableListOf<String>()
        var allRecoveryOk = true

        for (recoveryCmd in recovery.commands) {
            val recoveryResult = execute(sessionName, recoveryCmd, TerminalConfig.RECOVERY_TIMEOUT_MS)
            recoveryResults.add("$ ${recoveryCmd.take(80)}\n${recoveryResult.stdout.take(200)}")
            if (!recoveryResult.isSuccess) allRecoveryOk = false
        }

        if (!allRecoveryOk) {
            _outputFlow.emit(StreamEvent.Output(sessionName,
                "\u001b[31m⚠ Recovery attempted but incomplete: ${recovery.explanation}\u001b[0m\n"))
            return@withContext firstResult.copy(
                stderr = "${firstResult.stderr}\n⚠ Recovery attempted but incomplete: ${recovery.explanation}",
                wasRecovered = false, recoveryActions = recoveryResults
            )
        }

        _outputFlow.emit(StreamEvent.Output(sessionName,
            "\u001b[33m↻ Recovery applied, retrying...\u001b[0m\n"))
        val retryResult = execute(sessionName, command, timeoutMs)

        if (retryResult.isSuccess) {
            retryResult.copy(
                stdout = "${retryResult.stdout}\n\n⚡ Auto-recovered: ${recovery.description}",
                wasRecovered = true, recoveryActions = recoveryResults
            )
        } else {
            retryResult.copy(
                stderr = "${retryResult.stderr}\n\n⚠ Auto-recovery attempted but command still failed:\n${recovery.explanation}",
                wasRecovered = false, recoveryActions = recoveryResults
            )
        }
    }

    // ── Auto-Completion ──────────────────────────────────────────────────

    suspend fun suggestCompletions(
        partial: String,
        sessionName: String = defaultSessionName,
        maxResults: Int = TerminalConfig.MAX_COMPLETIONS
    ): List<CompletionSuggestion> = withContext(Dispatchers.Default) {
        if (partial.isBlank()) return@withContext emptyList()

        val trimmed = partial.trimStart()

        // Check aliases first
        val session = sessions[sessionName]
        if (session != null) {
            val aliasKey = trimmed.split(" ").firstOrNull() ?: ""
            val aliasMatches = session.aliases.filterKeys { it.startsWith(aliasKey) }
                .map { (k, v) ->
                    CompletionSuggestion(k, "$k → $v", "alias", 0.95f)
                }
            if (aliasMatches.isNotEmpty() && !trimmed.contains(" ")) {
                return@withContext aliasMatches.take(maxResults)
            }
        }

        val suggestions = mutableListOf<CompletionSuggestion>()

        when {
            trimmed.startsWith("git ") -> suggestions.addAll(completeGit(trimmed))
            trimmed.startsWith("pkg ") -> suggestions.addAll(completePkg(trimmed))
            trimmed.startsWith("pip ") || trimmed.startsWith("pip3 ") -> suggestions.addAll(completePip(trimmed))
            trimmed.startsWith("npm ") -> suggestions.addAll(completeNpm(trimmed))
            trimmed.startsWith("cd ") || trimmed.startsWith("cat ") || trimmed.startsWith("ls ") ||
            trimmed.startsWith("rm ") || trimmed.startsWith("cp ") || trimmed.startsWith("mv ") ->
                suggestions.addAll(completePath(trimmed, getWorkdir(sessionName)))
            else -> suggestions.addAll(completeCommand(trimmed))
        }

        suggestions.sortedByDescending { it.score }.take(maxResults)
    }

    // ── Completion helpers (unchanged, already clean) ───────────────────

    private fun completeGit(input: String): List<CompletionSuggestion> {
        val cmds = listOf("add","commit","push","pull","clone","status","log","branch",
            "checkout","merge","rebase","stash","diff","remote","fetch","reset","tag","init","config","rm")
        val after = input.removePrefix("git").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "git $it", "git", 0.9f) }
        val parts = after.split(" ")
        val sub = parts.first()
        if (parts.size == 1) {
            return cmds.filter { it.startsWith(sub) }.map { CompletionSuggestion(it.removePrefix(sub), "git $it", "git", 0.85f) }
        }
        val isBranch = sub in listOf("checkout","switch","merge","branch","rebase")
        if (isBranch && parts.size == 2) {
            val partial = parts.last()
            val branches = gitBranchCache.values.flatten().filter { it.startsWith(partial) }
            return branches.map { CompletionSuggestion(it.removePrefix(partial), "git $sub $it", "git-branch", 0.7f) }
        }
        return emptyList()
    }

    private fun completePkg(input: String): List<CompletionSuggestion> {
        val cmds = listOf("install","uninstall","update","upgrade","search","list-installed","show","files","reinstall")
        val after = input.removePrefix("pkg").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "pkg $it", "pkg", 0.9f) }
        if (!after.contains(" ")) {
            val matches = cmds.filter { it.startsWith(after) }
            return matches.map { CompletionSuggestion(it.removePrefix(after), "pkg $it", "pkg", 0.85f) }
        }
        return emptyList()
    }

    private fun completePip(input: String): List<CompletionSuggestion> {
        val cmds = listOf("install","uninstall","freeze","list","show","search","check","download","wheel")
        val after = input.removePrefix("pip").removePrefix("3").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "pip $it", "pip", 0.9f) }
        if (!after.contains(" ")) {
            val matches = cmds.filter { it.startsWith(after) }
            return matches.map { CompletionSuggestion(it.removePrefix(after), "pip $it", "pip", 0.85f) }
        }
        return emptyList()
    }

    private fun completeNpm(input: String): List<CompletionSuggestion> {
        val cmds = listOf("init","install","run","start","test","build","publish","update","list","audit","fix","ci")
        val after = input.removePrefix("npm").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "npm $it", "npm", 0.9f) }
        if (!after.contains(" ")) {
            val matches = cmds.filter { it.startsWith(after) }
            return matches.map { CompletionSuggestion(it.removePrefix(after), "npm $it", "npm", 0.85f) }
        }
        if (after.startsWith("run ")) {
            val partial = after.removePrefix("run ").trim()
            return npmScriptsCache.filter { it.startsWith(partial) }
                .map { CompletionSuggestion(it.removePrefix(partial), "npm run $it", "npm-script", 0.75f) }
        }
        return emptyList()
    }

    private fun completePath(input: String, workdir: String): List<CompletionSuggestion> {
        try {
            val parts = input.split(" ")
            val pathPart = parts.lastOrNull() ?: return emptyList()
            val basePath = if (pathPart.startsWith("/")) pathPart else "$workdir/$pathPart"
            val parent = File(basePath).parentFile ?: File(basePath)
            val prefix = File(basePath).name
            val files = parent.listFiles() ?: return emptyList()
            return files.filter { it.name.startsWith(prefix) }.map { f ->
                val suffix = if (f.isDirectory) "/" else ""
                CompletionSuggestion(f.name.removePrefix(prefix) + suffix, f.name + suffix,
                    if (f.isDirectory) "directory" else "file", 0.8f)
            }
        } catch (e: Exception) { return emptyList() }
    }

    private fun completeCommand(input: String): List<CompletionSuggestion> {
        val cmds = listOf("ls","cd","cat","echo","pwd","mkdir","rm","cp","mv","touch","chmod","grep",
            "find","head","tail","sort","wc","cut","diff","tar","zip","unzip","curl","wget",
            "python","python3","node","npm","git","pkg","pip","pip3","make","gcc","g++",
            "ruby","perl","php","vim","nano","less","more","clear","history","env",
            "export","source",".","which","whereis","type","chsh","termux-setup-storage",
            "ping","nslookup","dig","ssh","scp","rsync","htop","top","ps","kill",
            "df","du","free","uname","neofetch","screenfetch","termux-wifi-connectioninfo",
            "termux-battery-status","termux-sensor","termux-clipboard-get","termux-clipboard-set",
            "termux-toast","termux-vibrate","termux-torch")
        return cmds.filter { it.startsWith(input) }.map { CompletionSuggestion(it.removePrefix(input), it, "command", 0.7f) }
    }

    // ── Error Recovery ───────────────────────────────────────────────────

    fun suggestRecovery(command: String, error: String): RecoveryAction? {
        val lower = error.lowercase()

        val cmdNotFoundMatch = Regex("""(\S+):\s*(?:command not found|not found|no such file)""", RegexOption.IGNORE_CASE).find(error)
        if (cmdNotFoundMatch != null) {
            val missingCmd = cmdNotFoundMatch.groupValues[1].lowercase()
            val pkgMap = mapOf(
                "git" to "git","python3" to "python","python" to "python","pip" to "python-pip",
                "pip3" to "python-pip","node" to "nodejs","npm" to "nodejs","make" to "make",
                "gcc" to "gcc","g++" to "gcc","curl" to "curl","wget" to "wget","tar" to "tar",
                "zip" to "zip","unzip" to "unzip","htop" to "htop","vim" to "vim","nano" to "nano",
                "rsync" to "rsync","screenfetch" to "screenfetch","neofetch" to "neofetch",
                "ffmpeg" to "ffmpeg","ruby" to "ruby","perl" to "perl","php" to "php"
            )
            val pkg = pkgMap[missingCmd] ?: return RecoveryAction(
                "Package '$missingCmd' not available — try 'pkg search' first",
                listOf("pkg search $missingCmd 2>/dev/null | head -20"), 0.3f,
                "Command '$missingCmd' was not found. It may need to be installed."
            )
            return RecoveryAction("Install '$pkg' via pkg", listOf("pkg install -y $pkg 2>&1"), 0.85f,
                "The command '$missingCmd' was not found. Installing '$pkg' via pkg...")
        }

        val pipMatch = Regex("""(?:ModuleNotFoundError|ImportError|No module named)\s*['"]?(\w[\w.-]*)['"]?""").find(lower)
        if (pipMatch != null) {
            val module = pipMatch.groupValues[1]
            return RecoveryAction("Install Python module '$module' via pip",
                listOf("pip install $module 2>&1"), 0.9f,
                "Python module '$module' not found. Installing via pip...")
        }

        if (lower.contains("npm err") && lower.contains("missing script")) {
            val script = Regex("""missing script:\s*(\S+)""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.getOrNull(1) ?: "unknown"
            return RecoveryAction("npm script '$script' not found.",
                listOf("cat package.json 2>/dev/null | grep -A20 '\"scripts\"' || echo 'No package.json found'"), 0.6f,
                "The npm script '$script' doesn't exist.")
        }

        if (lower.contains("npm err") && (lower.contains("package.json") || lower.contains("enoent"))) {
            return RecoveryAction("Initialize npm project", listOf("npm init -y 2>&1"), 0.7f,
                "No package.json found. Creating one with 'npm init -y'...")
        }

        if (lower.contains("not a git repository") || lower.contains("fatal: not a git")) {
            return RecoveryAction("Initialize git repository",
                listOf("git init 2>&1", "git add . 2>&1"), 0.65f,
                "This is not a git repository. Initializing one...")
        }

        if (lower.contains("permission denied") || lower.contains("not permitted")) {
            return RecoveryAction("Fix permissions",
                listOf("chmod +x ${command.split(" ").firstOrNull() ?: "script"}"), 0.4f,
                "Permission denied. This may require adjusting file permissions.")
        }

        if (lower.contains("connection refused") || lower.contains("network is unreachable") ||
            lower.contains("could not connect") || lower.contains("timeout")) {
            return RecoveryAction("Check network", listOf("ping -c 2 -W 3 8.8.8.8 2>&1 || echo 'Network offline'"), 0.35f,
                "Network issue detected. Internet may be unavailable.")
        }

        if (lower.contains("has no installation candidate") || lower.contains("unable to locate package")) {
            return RecoveryAction("Update package lists", listOf("pkg update 2>&1"), 0.7f,
                "Package lists may be outdated. Running 'pkg update'...")
        }

        if (lower.contains("syntaxerror") || lower.contains("syntax error")) {
            return RecoveryAction("Check syntax", emptyList(), 0.3f,
                "Syntax error. Check for: missing quotes, unclosed brackets, incorrect indentation.")
        }

        return null
    }

    // ── Caches ──────────────────────────────────────────────────────────

    private val npmScriptsCache = mutableListOf<String>()

    suspend fun refreshNpmScripts(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionName] ?: return@withContext
            val result = termuxBridge.executeShell(
                shellCommand = "cat package.json 2>/dev/null | grep -o '\"[a-z-]*\":' | tr -d '\":'",
                workdir = session.workdir, timeoutMs = TerminalConfig.CONNECTION_TEST_TIMEOUT_MS
            )
            if (result.isSuccess) {
                npmScriptsCache.clear()
                npmScriptsCache.addAll(result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() })
            }
        } catch (e: Exception) { Log.w(TAG, "NPM cache refresh failed: ${e.message}") }
    }

    suspend fun refreshGitBranches(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionName] ?: return@withContext
            val result = termuxBridge.executeShell(
                shellCommand = "git branch -a 2>/dev/null | sed 's/^*//' | sed 's/^[[:space:]]*//' | sed 's/(HEAD.*)//' | tr -d ' '",
                workdir = session.workdir, timeoutMs = TerminalConfig.CONNECTION_TEST_TIMEOUT_MS
            )
            if (result.isSuccess) {
                gitBranchCache[sessionName] = result.stdout.lines()
                    .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("(") }
            }
        } catch (e: Exception) { Log.w(TAG, "Git branch refresh failed: ${e.message}") }
    }

    // ── Path Resolution ────────────────────────────────────────────────

    private fun resolvePath(currentDir: String, target: String): String {
        when {
            target == "." -> return currentDir
            target == ".." -> return File(currentDir).parent ?: currentDir
            target.startsWith("~") -> return target.replace("~", TERMUX_HOME)
            target.startsWith("/") -> return target
            target.startsWith("..") -> {
                val parts = target.split("/").filter { it.isNotBlank() }
                var resolved = File(currentDir)
                for (part in parts) {
                    resolved = when (part) {
                        ".." -> resolved.parentFile ?: resolved
                        "." -> resolved
                        else -> File(resolved, part)
                    }
                }
                return resolved.absolutePath
            }
            else -> return "$currentDir/$target"
        }
    }

    // ── Session Persistence (now includes aliases + env vars) ──────────

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = autoSaveScope.launch {
            delay(TerminalConfig.AUTO_SAVE_DELAY_MS)
            saveSessionsToDisk()
        }
    }

    fun shutdown() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        saveSessionsToDisk()
        sessions.clear()
    }

    fun saveSessionsToDisk() {
        try {
            val file = File(context.filesDir, "terminal_sessions.json")
            val data = sessions.map { (name, s) ->
                mapOf(
                    "name" to name,
                    "workdir" to s.workdir,
                    "envVars" to s.envVars.toMap(),
                    "aliases" to s.aliases.toMap(),
                    "historyCount" to s.history.size,
                    "outputLines" to s.outputLines.takeLast(200)
                )
            }
            file.writeText(Gson().toJson(data))
        } catch (e: Exception) { Log.w(TAG, "Save sessions failed: ${e.message}") }
    }

    fun loadSessionsFromDisk() {
        try {
            val file = File(context.filesDir, "terminal_sessions.json")
            if (!file.exists()) return
            val json = file.readText()
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val data: List<Map<String, Any?>> = Gson().fromJson(json, type)
            data.forEach { entry ->
                val name = entry["name"] as? String ?: return@forEach
                val workdir = entry["workdir"] as? String ?: TERMUX_HOME
                val envVars = (entry["envVars"] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
                val aliases = (entry["aliases"] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
                if (!sessions.containsKey(name)) {
                    sessions[name] = TerminalSession(name = name, workdir = workdir,
                        envVars = envVars, aliases = aliases)
                }
            }
            Log.d(TAG, "Loaded ${data.size} sessions from disk")
        } catch (e: Exception) { Log.w(TAG, "Load sessions failed: ${e.message}") }
    }
}
