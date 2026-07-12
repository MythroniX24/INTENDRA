package com.interndra.agent

import android.content.Context
import android.util.Log
import com.interndra.service.ExecutionBackend
import com.interndra.service.PersistentShell
import com.interndra.service.ShellExecutionResult
import com.interndra.service.ShellExecutor
import com.interndra.service.ShizukuShell
import com.interndra.service.TerminalConfig
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
 * TerminalAgent — persistent shell session manager with real-time streaming.
 *
 * ## A+++ UPGRADE: Real Persistent Shell (PersistentShell)
 * Instead of spawning `sh -c "command"` for every command (which means `cd`
 * and env vars never persist), we now use ONE persistent shell process that
 * stays alive between commands. This means:
 *  - `cd` actually changes the shell's working directory
 *  - Environment variables set with `export` persist
 *  - Aliases defined with `alias` persist
 *  - The shell has real history and state
 *  - Interactive programs (vim, python REPL) can work via PTY in future
 *
 * ## Execution Priority
 * 1. **PersistentShell with Shizuku** — elevated shell (root/ADB UID), lives at /
 * 2. **PersistentShell sandboxed** — app's own UID, limited permissions
 * 3. **ShellExecutor fallback** — one-shot commands (legacy)
 *
 * ## No Termux Required
 * TermuxBridge has been removed. The terminal works independently with its
 * own built-in shell. No external Termux app needed.
 */
class TerminalAgent(
    private val context: Context,
    private val shizukuShell: ShizukuShell,
    private val scope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "TerminalAgent"

        /** Default working directory — ROOT (/) for Shizuku, /sdcard for sandbox. */
        val DEFAULT_WORKDIR = "/"

        /** Termux home (kept for backward compatibility with saved sessions). */
        val TERMUX_HOME = "/data/data/com.termux/files/home"
        val TERMUX_USR_BIN = "/data/data/com.termux/files/usr/bin"
    }

    // ── Persistent Shell ───────────────────────────────────────────────
    @Volatile private var persistentShell: PersistentShell? = null
    private val shellMutex = Mutex()

    /** Get or create the persistent shell instance. */
    private suspend fun getShell(): PersistentShell? {
        var shell = persistentShell
        if (shell?.isAlive == true) return shell

        return shellMutex.withLock {
            shell = persistentShell
            if (shell?.isAlive == true) return@withLock shell

            Log.i(TAG, "Creating persistent shell...")
            val newShell = if (shizukuShell.isElevatedAvailable) {
                // Shizuku-elevated shell with root privileges
                Log.i(TAG, "Using Shizuku-elevated persistent shell (UID ${shizukuShell.manager.shizukuUid})")
                PersistentShell(
                    shellPath = PersistentShell.DEFAULT_SHELL,
                    initialWorkdir = DEFAULT_WORKDIR,
                    shizukuProvider = {
                        try {
                            shizukuShell.manager.executeShell("echo shizuku_ready 2>&1")
                            // Use ShizukuProcessCreator to spawn a persistent shell
                            createShizukuShellProcess()
                        } catch (e: Exception) {
                            Log.w(TAG, "Shizuku shell spawn failed: ${e.message}")
                            null
                        }
                    }
                )
            } else {
                // Sandboxed persistent shell
                Log.i(TAG, "Using sandboxed persistent shell")
                PersistentShell(
                    shellPath = PersistentShell.DEFAULT_SHELL,
                    initialWorkdir = DEFAULT_WORKDIR
                )
            }

            val started = newShell.start()
            if (started) {
                persistentShell = newShell
                Log.i(TAG, "✅ Persistent shell started (${newShell.backendDescription})")
                _outputFlow.tryEmit(StreamEvent.Output("default",
                    "\u001b[32m✓ Terminal ready — ${newShell.backendDescription}\u001b[0m\n"))
            } else {
                Log.e(TAG, "Failed to start persistent shell")
                _outputFlow.tryEmit(StreamEvent.Output("default",
                    "\u001b[31m✗ Failed to start terminal shell\u001b[0m\n"))
            }
            persistentShell
        }
    }

    /** Create a Shizuku-elevated persistent shell process via ShizukuManager. */
    private fun createShizukuShellProcess(): Process? {
        return try {
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-i"), null, DEFAULT_WORKDIR) as? Process
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku.newProcess failed: ${e.message}")
            null
        }
    }

    // ── Command Queue ──────────────────────────────────────────────────
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val executingCommands = ConcurrentHashMap<String, Int>()
    private fun sessionMutex(name: String): Mutex = sessionMutexes.getOrPut(name) { Mutex() }

    // ── Background Jobs ─────────────────────────────────────────────────
    private val backgroundJobs = ConcurrentHashMap<Int, SessionJob>()
    private val jobIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ── Session ────────────────────────────────────────────────────────────
    data class TerminalSession(
        val name: String,
        var workdir: String = DEFAULT_WORKDIR,
        val envVars: MutableMap<String, String> = mutableMapOf(
            "HOME" to DEFAULT_WORKDIR,
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/tmp:/su/bin:/su/xbin:/data/data/com.termux/files/usr/bin:/usr/bin:/bin",
            "PWD" to DEFAULT_WORKDIR,
            "TERM" to "xterm-256color"
        ),
        val aliases: MutableMap<String, String> = mutableMapOf(),
        val history: MutableList<HistoryEntry> = mutableListOf(),
        val outputLines: MutableList<String> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastActiveAt: Long = System.currentTimeMillis()
    )

    data class HistoryEntry(
        val command: String, val workdir: String, val exitCode: Int,
        val stdout: String, val stderr: String, val timestamp: Long = System.currentTimeMillis(),
        val durationMs: Long = 0L, val wasRecovered: Boolean = false,
        val backend: String = ExecutionBackend.UNKNOWN.name
    )

    data class SessionResult(
        val stdout: String, val stderr: String, val exitCode: Int, val isSuccess: Boolean,
        val sessionName: String, val workdir: String, val durationMs: Long,
        val wasRecovered: Boolean = false, val recoveryActions: List<String> = emptyList(),
        val backend: ExecutionBackend = ExecutionBackend.UNKNOWN
    )

    data class CompletionSuggestion(val text: String, val displayText: String, val category: String, val score: Float)
    data class RecoveryAction(val description: String, val commands: List<String>, val confidence: Float, val explanation: String)

    // ── Background Job Models ─────────────────────────────────────────
    enum class BackgroundJobStatus { RUNNING, COMPLETED, CANCELLED, FAILED }

    data class SessionJob(
        val id: Int, val command: String, val sessionName: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var status: BackgroundJobStatus = BackgroundJobStatus.RUNNING,
        @Volatile var exitCode: Int? = null,
        @Volatile var outputLines: java.util.concurrent.CopyOnWriteArrayList<String> = java.util.concurrent.CopyOnWriteArrayList(),
        internal var bgProcess: ShellExecutor.BackgroundProcess? = null
    ) {
        val isActive: Boolean get() = status == BackgroundJobStatus.RUNNING
        val output: String get() = outputLines.joinToString("")
    }

    // ── Streaming ─────────────────────────────────────────────────────────
    private val _outputFlow = MutableSharedFlow<StreamEvent>(replay = 0, extraBufferCapacity = 256)
    val outputFlow: SharedFlow<StreamEvent> = _outputFlow.asSharedFlow()

    sealed class StreamEvent {
        data class Output(val sessionName: String, val text: String) : StreamEvent()
        data class CommandStart(val sessionName: String, val command: String) : StreamEvent()
        data class CommandEnd(val sessionName: String, val exitCode: Int, val backend: ExecutionBackend = ExecutionBackend.UNKNOWN) : StreamEvent()
        data class Error(val sessionName: String, val message: String) : StreamEvent()
        data class JobCreated(val job: SessionJob) : StreamEvent()
        data class JobOutput(val jobId: Int, val sessionName: String, val text: String) : StreamEvent()
        data class JobEnded(val jobId: Int, val sessionName: String, val exitCode: Int) : StreamEvent()
    }

    // ── State ────────────────────────────────────────────────────────────
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val defaultSessionName = "default"
    private val gitBranchCache = mutableMapOf<String, List<String>>()
    private var autoSaveJob: Job? = null
    private val autoSaveScope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    val executionBackendDescription: String
        get() = persistentShell?.backendDescription ?: "⚙️ ShellExecutor"

    val isElevated: Boolean get() = shizukuShell.isElevatedAvailable

    // ── Session Management ────────────────────────────────────────────────
    fun createSession(name: String, workdir: String = DEFAULT_WORKDIR): TerminalSession =
        sessions.getOrPut(name) {
            TerminalSession(name = name, workdir = workdir).also {
                _outputFlow.tryEmit(StreamEvent.Output(name, "\u001b[32m✓ Session '$name' created\u001b[0m\n"))
            }
        }
    fun getDefaultSession(): TerminalSession = createSession(defaultSessionName)
    fun getOrCreateSession(name: String, workdir: String = DEFAULT_WORKDIR): TerminalSession =
        sessions[name] ?: createSession(name, workdir)
    fun getAllSessions(): List<TerminalSession> = sessions.values.toList()
    fun getSessionNames(): List<String> = sessions.keys.toList()
    fun getOutputLines(sessionName: String): List<String> =
        getOrCreateSession(sessionName).outputLines.toList()
    fun removeSession(name: String) {
        sessions.remove(name); sessionMutexes.remove(name); scheduleAutoSave()
    }
    fun renameSession(oldName: String, newName: String): Boolean {
        val s = sessions.remove(oldName) ?: return false
        sessions[newName] = s.copy(name = newName); scheduleAutoSave(); return true
    }
    fun getWorkdir(sessionName: String): String = getOrCreateSession(sessionName).workdir

    /** Change working directory — writes `cd` to the persistent shell. */
    suspend fun changeWorkdir(sessionName: String, target: String): String {
        val session = getOrCreateSession(sessionName)
        val shell = getShell()
        if (shell != null) {
            val newDir = shell.changeWorkdir(target)
            session.workdir = newDir
            session.envVars["PWD"] = newDir
            session.lastActiveAt = System.currentTimeMillis()
            _outputFlow.tryEmit(StreamEvent.Output(sessionName, "\u001b[36m$newDir\u001b[0m\n"))
            return newDir
        }
        // Fallback: resolve locally
        val resolved = resolvePath(session.workdir, target)
        session.workdir = resolved; session.envVars["PWD"] = resolved
        session.lastActiveAt = System.currentTimeMillis()
        return resolved
    }

    // ── Aliases + Env ──────────────────────────────────────────────────
    suspend fun setAlias(sessionName: String, name: String, expansion: String) {
        sessionMutex(sessionName).withLock { getOrCreateSession(sessionName).aliases[name] = expansion }
        scheduleAutoSave()
    }
    suspend fun removeAlias(sessionName: String, name: String) {
        sessionMutex(sessionName).withLock { sessions[sessionName]?.aliases?.remove(name) }
        scheduleAutoSave()
    }
    fun getAliases(sessionName: String): Map<String, String> = getOrCreateSession(sessionName).aliases.toMap()
    suspend fun setEnv(sessionName: String, key: String, value: String) {
        sessionMutex(sessionName).withLock { getOrCreateSession(sessionName).envVars[key] = value }
        scheduleAutoSave()
    }
    fun getEnv(sessionName: String, key: String): String? = getOrCreateSession(sessionName).envVars[key]
    private fun expandAliases(sessionName: String, command: String): String {
        val trimmed = command.trim()
        val firstWord = trimmed.split(" ").firstOrNull() ?: return trimmed
        val expansion = sessions[sessionName]?.aliases?.get(firstWord) ?: return trimmed
        val rest = trimmed.removePrefix(firstWord).trimStart()
        return if (rest.isNotEmpty()) "$expansion $rest" else expansion
    }
    fun getHistory(sessionName: String, limit: Int = 50): List<HistoryEntry> =
        getOrCreateSession(sessionName).history.takeLast(limit).reversed()
    fun clearHistory(sessionName: String) {
        sessions[sessionName]?.let { s ->
            synchronized(s) { s.history.clear(); s.outputLines.clear() }
            _outputFlow.tryEmit(StreamEvent.Output(sessionName, "\u001b[33m⌛ History cleared\u001b[0m\n"))
        }
    }

    // ── Background Job Execution ───────────────────────────────────────
    fun executeBackground(sessionName: String, command: String): SessionJob {
        val jobId = jobIdCounter.incrementAndGet()
        val expanded = expandAliases(sessionName, command)
        val session = getOrCreateSession(sessionName)
        val job = SessionJob(id = jobId, command = expanded, sessionName = sessionName)
        backgroundJobs[jobId] = job

        _outputFlow.tryEmit(StreamEvent.JobCreated(job))
        _outputFlow.tryEmit(StreamEvent.Output(sessionName,
            "\u001b[33m⚡ Background job #$jobId: $expanded\u001b[0m\n"))
        session.outputLines.add("\u001b[33m⚡ Background job #$jobId: $expanded\u001b[0m\n")

        Thread {
            try {
                val bgProcess = ShellExecutor.spawnBackground(expanded) { line ->
                    job.outputLines.add(line)
                    _outputFlow.tryEmit(StreamEvent.JobOutput(jobId, sessionName, line))
                }
                job.bgProcess = bgProcess
                val done = bgProcess.waitFor(0)
                if (done || bgProcess.hasExited()) {
                    job.exitCode = bgProcess.exitCode ?: 0
                    job.status = if (job.exitCode == 0) BackgroundJobStatus.COMPLETED
                                 else BackgroundJobStatus.FAILED
                    val statusIcon = if (job.exitCode == 0) "\u001b[32m✓\u001b[0m" else "\u001b[31m✗\u001b[0m"
                    val msg = "$statusIcon Background job #$jobId finished (exit ${job.exitCode})\n"
                    _outputFlow.tryEmit(StreamEvent.Output(sessionName, msg))
                    _outputFlow.tryEmit(StreamEvent.JobEnded(jobId, sessionName, job.exitCode ?: -1))
                    GlobalScope.launch { delay(5000); backgroundJobs.remove(jobId) }
                }
            } catch (e: Exception) {
                job.status = BackgroundJobStatus.FAILED; job.exitCode = -1
                _outputFlow.tryEmit(StreamEvent.Output(sessionName,
                    "\u001b[31m✗ Background job #$jobId failed: ${e.message}\u001b[0m\n"))
                _outputFlow.tryEmit(StreamEvent.JobEnded(jobId, sessionName, -1))
            }
        }.apply { isDaemon = true; name = "bg-job-$jobId" }.start()

        return job
    }

    fun cancelJob(jobId: Int): Boolean {
        val job = backgroundJobs[jobId] ?: return false
        if (job.status == BackgroundJobStatus.RUNNING) {
            job.bgProcess?.cancel(); job.status = BackgroundJobStatus.CANCELLED; job.exitCode = -1
            val msg = "\u001b[33m⏹ Job #$jobId cancelled: ${job.command.take(50)}\u001b[0m\n"
            _outputFlow.tryEmit(StreamEvent.Output(job.sessionName, msg))
            _outputFlow.tryEmit(StreamEvent.JobEnded(jobId, job.sessionName, -1))
        }
        backgroundJobs.remove(jobId); return true
    }

    fun cancelAllJobs(sessionName: String) {
        backgroundJobs.values.filter { it.sessionName == sessionName && it.isActive }
            .forEach { cancelJob(it.id) }
    }

    fun listJobs(): List<SessionJob> = backgroundJobs.values.toList()
    fun listJobsForSession(sessionName: String): List<SessionJob> =
        backgroundJobs.values.filter { it.sessionName == sessionName }
    fun getJob(jobId: Int): SessionJob? = backgroundJobs[jobId]
    fun getJobOutput(jobId: Int): String = backgroundJobs[jobId]?.output ?: ""
    fun activeJobCount(): Int = backgroundJobs.values.count { it.isActive }

    // ── Command Execution ──────────────────────────────────────────────
    suspend fun execute(
        sessionName: String, command: String,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS
    ): SessionResult = sessionMutex(sessionName).withLock {
        val startMs = System.currentTimeMillis()
        val trimmed = command.trim()
        val dedupKey = "$sessionName::$trimmed"
        val currentCount = executingCommands.getOrDefault(dedupKey, 0)
        if (currentCount > 0) {
            _outputFlow.tryEmit(StreamEvent.Output(sessionName,
                "\u001b[33m⚠ Command '$trimmed' is already running\u001b[0m\n"))
            return@withLock SessionResult("", "Duplicate skipped", 0, true, sessionName, getWorkdir(sessionName), 0L)
        }
        executingCommands[dedupKey] = currentCount + 1
        try { runExecution(sessionName, command, timeoutMs, startMs) }
        finally { executingCommands.remove(dedupKey) }
    }

    private suspend fun runExecution(
        sessionName: String, command: String, timeoutMs: Long, startMs: Long
    ): SessionResult = withContext(Dispatchers.IO) {
        val session = getOrCreateSession(sessionName)
        val expanded = expandAliases(sessionName, command)
        val trimmed = expanded.trim()

        val prompt = if (command != expanded)
            "\u001b[90m($command → $trimmed)\u001b[0m\n\u001b[32m\\$\u001b[0m $trimmed\n"
        else "\u001b[32m\\$\u001b[0m $trimmed\n"
        session.outputLines.add(prompt)
        _outputFlow.emit(StreamEvent.CommandStart(sessionName, trimmed))
        _outputFlow.emit(StreamEvent.Output(sessionName, prompt))

        // ── Try persistent shell first ──
        val shell = getShell()
        val result: ShellExecutionResult = if (shell != null && shell.isAlive) {
            Log.d(TAG, "Executing via persistent shell: $trimmed")
            shell.execute(trimmed, timeoutMs) { line ->
                session.outputLines.add(line)
                _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
            }
        } else {
            // Fallback to one-shot ShizukuShell / ShellExecutor
            Log.w(TAG, "Persistent shell not available, using one-shot execution")
            if (shizukuShell.isElevatedAvailable) {
                shizukuShell.execute(trimmed, timeoutMs) { line ->
                    session.outputLines.add(line); _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
                }
            } else {
                ShellExecutor.runStreaming(trimmed, timeoutMs) { line ->
                    session.outputLines.add(line); _outputFlow.tryEmit(StreamEvent.Output(sessionName, line))
                }
            }
        }

        // ── Backend indicator ──
        val indicator = when (result.backend) {
            ExecutionBackend.SHIZUKU_ROOT -> "\u001b[90m[🛡️ Shizuku Root]\u001b[0m\n"
            ExecutionBackend.SHIZUKU_ADB -> "\u001b[90m[🔑 Shizuku ADB]\u001b[0m\n"
            else -> "\u001b[90m[⚙️ Shell]\u001b[0m\n"
        }
        session.outputLines.add(indicator)
        _outputFlow.tryEmit(StreamEvent.Output(sessionName, indicator))

        // Update workdir after command (shell may have changed it via cd)
        if (shell != null && shell.isAlive && result.isSuccess) {
            try {
                val newWorkdir = shell.getWorkdir()
                session.workdir = newWorkdir
                session.envVars["PWD"] = newWorkdir
            } catch (_: Exception) {}
        }

        val duration = System.currentTimeMillis() - startMs
        synchronized(session) {
            session.lastActiveAt = System.currentTimeMillis()
            session.history.add(HistoryEntry(trimmed, session.workdir, result.exitCode,
                result.stdout.take(TerminalConfig.MAX_STDOUT_SNIPPET_CHARS),
                result.stderr.take(TerminalConfig.MAX_STDERR_SNIPPET_CHARS), durationMs = duration,
                backend = result.backend.name))
            while (session.history.size > TerminalConfig.MAX_HISTORY_ENTRIES) session.history.removeAt(0)
            while (session.outputLines.size > TerminalConfig.MAX_OUTPUT_LINES) session.outputLines.removeAt(0)
        }

        val exitColor = if (result.isSuccess) "32" else "31"
        val exitMsg = "\u001b[${exitColor}m❯ Exit ${result.exitCode} (${duration}ms)\u001b[0m\n"
        session.outputLines.add(exitMsg)
        _outputFlow.emit(StreamEvent.Output(sessionName, exitMsg))
        _outputFlow.emit(StreamEvent.CommandEnd(sessionName, result.exitCode, result.backend))
        scheduleAutoSave()
        SessionResult(result.stdout, result.stderr, result.exitCode, result.isSuccess, sessionName, session.workdir, duration, backend = result.backend)
    }

    // ── Error Recovery ───────────────────────────────────────────────────
    suspend fun executeWithRecovery(sessionName: String, command: String, timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS
    ): SessionResult = withContext(Dispatchers.IO) {
        val firstResult = execute(sessionName, command, timeoutMs)
        if (firstResult.isSuccess) return@withContext firstResult
        val recovery = suggestRecovery(command, "${firstResult.stderr}\n${firstResult.stdout}")
        if (recovery == null) {
            _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[33m⚠ No automated recovery available\u001b[0m\n"))
            return@withContext firstResult.copy(stderr = "${firstResult.stderr}\n(No automated recovery available)")
        }
        if (recovery.commands.isEmpty()) {
            _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[33m💡 ${recovery.explanation}\u001b[0m\n"))
            return@withContext firstResult.copy(stderr = "${firstResult.stderr}\n\n💡 ${recovery.explanation}", wasRecovered = false)
        }
        _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[33m⚡ Attempting recovery: ${recovery.description}\u001b[0m\n"))
        val results = mutableListOf<String>(); var allOk = true
        for (rc in recovery.commands) {
            val rr = execute(sessionName, rc, TerminalConfig.RECOVERY_TIMEOUT_MS)
            results.add("$ ${rc.take(80)}\n${rr.stdout.take(200)}")
            if (!rr.isSuccess) allOk = false
        }
        if (!allOk) {
            _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[31m⚠ Recovery incomplete: ${recovery.explanation}\u001b[0m\n"))
            return@withContext firstResult.copy(stderr = "${firstResult.stderr}\n⚠ Recovery incomplete", wasRecovered = false, recoveryActions = results)
        }
        _outputFlow.emit(StreamEvent.Output(sessionName, "\u001b[33m↻ Recovery applied, retrying...\u001b[0m\n"))
        val rr = execute(sessionName, command, timeoutMs)
        if (rr.isSuccess) rr.copy(stdout = "${rr.stdout}\n\n⚡ Auto-recovered: ${recovery.description}", wasRecovered = true, recoveryActions = results)
        else rr.copy(stderr = "${rr.stderr}\n\n⚠ Recovery attempted but still failed", wasRecovered = false, recoveryActions = results)
    }

    // ── Auto-Completion ──────────────────────────────────────────────────
    suspend fun suggestCompletions(partial: String, sessionName: String = defaultSessionName, maxResults: Int = TerminalConfig.MAX_COMPLETIONS
    ): List<CompletionSuggestion> = withContext(Dispatchers.Default) {
        if (partial.isBlank()) return@withContext emptyList()
        val trimmed = partial.trimStart()
        val session = sessions[sessionName]
        if (session != null) {
            val aliasMatches = session.aliases.filterKeys { it.startsWith(trimmed.split(" ").firstOrNull() ?: "") }
                .map { (k, v) -> CompletionSuggestion(k, "$k → $v", "alias", 0.95f) }
            if (aliasMatches.isNotEmpty() && !trimmed.contains(" ")) return@withContext aliasMatches.take(maxResults)
        }
        val s = mutableListOf<CompletionSuggestion>()
        when {
            trimmed.startsWith("git ") -> s.addAll(completeGit(trimmed))
            trimmed.startsWith("pkg ") -> s.addAll(completePkg(trimmed))
            trimmed.startsWith("pip ") || trimmed.startsWith("pip3 ") -> s.addAll(completePip(trimmed))
            trimmed.startsWith("npm ") -> s.addAll(completeNpm(trimmed))
            trimmed.startsWith("cd ") || trimmed.startsWith("cat ") || trimmed.startsWith("ls ") ||
            trimmed.startsWith("rm ") || trimmed.startsWith("cp ") || trimmed.startsWith("mv ") ->
                s.addAll(completePath(trimmed, getWorkdir(sessionName)))
            else -> s.addAll(completeCommand(trimmed))
        }
        s.sortedByDescending { it.score }.take(maxResults)
    }

    private fun completeGit(input: String): List<CompletionSuggestion> {
        val cmds = listOf("add","commit","push","pull","clone","status","log","branch","checkout","merge","rebase","stash","diff","remote","fetch","reset","tag","init","config","rm")
        val after = input.removePrefix("git").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "git $it", "git", 0.9f) }
        val parts = after.split(" "); val sub = parts.first()
        if (parts.size == 1) return cmds.filter { it.startsWith(sub) }.map { CompletionSuggestion(it.removePrefix(sub), "git $it", "git", 0.85f) }
        if (sub in listOf("checkout","switch","merge","branch","rebase") && parts.size == 2) {
            val partial = parts.last()
            return gitBranchCache.values.flatten().filter { it.startsWith(partial) }
                .map { CompletionSuggestion(it.removePrefix(partial), "git $sub $it", "git-branch", 0.7f) }
        }
        return emptyList()
    }
    private fun completePkg(input: String): List<CompletionSuggestion> {
        val cmds = listOf("install","uninstall","update","upgrade","search","list-installed","show","files","reinstall")
        val after = input.removePrefix("pkg").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "pkg $it", "pkg", 0.9f) }
        if (!after.contains(" ")) return cmds.filter { it.startsWith(after) }.map { CompletionSuggestion(it.removePrefix(after), "pkg $it", "pkg", 0.85f) }
        return emptyList()
    }
    private fun completePip(input: String): List<CompletionSuggestion> {
        val cmds = listOf("install","uninstall","freeze","list","show","search","check","download","wheel")
        val after = input.removePrefix("pip").removePrefix("3").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "pip $it", "pip", 0.9f) }
        if (!after.contains(" ")) return cmds.filter { it.startsWith(after) }.map { CompletionSuggestion(it.removePrefix(after), "pip $it", "pip", 0.85f) }
        return emptyList()
    }
    private fun completeNpm(input: String): List<CompletionSuggestion> {
        val cmds = listOf("init","install","run","start","test","build","publish","update","list","audit","fix","ci")
        val after = input.removePrefix("npm").trim()
        if (after.isBlank()) return cmds.map { CompletionSuggestion(it, "npm $it", "npm", 0.9f) }
        if (!after.contains(" ")) return cmds.filter { it.startsWith(after) }.map { CompletionSuggestion(it.removePrefix(after), "npm $it", "npm", 0.85f) }
        if (after.startsWith("run ")) {
            val partial = after.removePrefix("run ").trim()
            return npmScriptsCache.filter { it.startsWith(partial) }
                .map { CompletionSuggestion(it.removePrefix(partial), "npm run $it", "npm-script", 0.75f) }
        }
        return emptyList()
    }
    private fun completePath(input: String, workdir: String): List<CompletionSuggestion> {
        try {
            val parts = input.split(" "); val pathPart = parts.lastOrNull() ?: return emptyList()
            val basePath = if (pathPart.startsWith("/")) pathPart else "$workdir/$pathPart"
            val parent = File(basePath).parentFile ?: File(basePath); val prefix = File(basePath).name
            return (parent.listFiles() ?: emptyArray()).filter { it.name.startsWith(prefix) }.map { f ->
                CompletionSuggestion(f.name.removePrefix(prefix) + if (f.isDirectory) "/" else "", f.name + if (f.isDirectory) "/" else "", if (f.isDirectory) "directory" else "file", 0.8f)
            }
        } catch (_: Exception) { return emptyList() }
    }
    private fun completeCommand(input: String): List<CompletionSuggestion> {
        val cmds = listOf("ls","cd","cat","echo","pwd","mkdir","rm","cp","mv","touch","chmod","grep","find","head","tail","sort","wc","cut","diff","tar","zip","unzip","curl","wget","python","python3","node","npm","git","pkg","pip","pip3","make","gcc","g++","ruby","perl","php","vim","nano","less","more","clear","history","env","export","source",".","which","whereis","type","ping","nslookup","dig","ssh","scp","rsync","htop","top","ps","kill","df","du","free","uname","neofetch","screenfetch")
        return cmds.filter { it.startsWith(input) }.map { CompletionSuggestion(it.removePrefix(input), it, "command", 0.7f) }
    }

    fun suggestRecovery(command: String, error: String): RecoveryAction? {
        val lower = error.lowercase()
        val cmdMatch = Regex("""(\S+):\s*(?:command not found|not found|no such file)""", RegexOption.IGNORE_CASE).find(error)
        if (cmdMatch != null) {
            val missing = cmdMatch.groupValues[1].lowercase()
            val pkgMap = mapOf("git" to "git","python3" to "python","python" to "python","pip" to "python-pip","pip3" to "python-pip","node" to "nodejs","npm" to "nodejs","make" to "make","gcc" to "gcc","g++" to "gcc","curl" to "curl","wget" to "wget","tar" to "tar","zip" to "zip","unzip" to "unzip","htop" to "htop","vim" to "vim","nano" to "nano","rsync" to "rsync","screenfetch" to "screenfetch","neofetch" to "neofetch","ffmpeg" to "ffmpeg","ruby" to "ruby","perl" to "perl","php" to "php")
            val pkg = pkgMap[missing] ?: return RecoveryAction("Package '$missing' not available", listOf("apt-get install $missing 2>/dev/null || echo 'Not available'"), 0.3f, "Command '$missing' was not found.")
            return RecoveryAction("Install '$pkg'", listOf("apt-get install -y $pkg 2>&1 || pkg install -y $pkg 2>&1"), 0.85f, "Installing '$pkg'...")
        }
        val pipMatch = Regex("""(?:ModuleNotFoundError|ImportError|No module named)\s*['"]?(\w[\w.-]*)['"]?""").find(lower)
        if (pipMatch != null) return RecoveryAction("Install Python module", listOf("pip install ${pipMatch.groupValues[1]} 2>&1"), 0.9f, "Python module not found. Installing via pip...")
        if (lower.contains("npm err") && lower.contains("missing script")) return RecoveryAction("npm script not found", listOf("cat package.json 2>/dev/null | grep -A20 '\"scripts\"'"), 0.6f, "Script doesn't exist in package.json.")
        if (lower.contains("npm err") && (lower.contains("package.json") || lower.contains("enoent"))) return RecoveryAction("Init npm project", listOf("npm init -y 2>&1"), 0.7f, "No package.json found.")
        if (lower.contains("not a git repository") || lower.contains("fatal: not a git")) return RecoveryAction("Init git repo", listOf("git init 2>&1", "git add . 2>&1"), 0.65f, "Initializing git repository...")
        if (lower.contains("permission denied") || lower.contains("not permitted")) return RecoveryAction("Fix permissions", listOf("chmod +x ${command.split(" ").firstOrNull() ?: "script"}"), 0.4f, "Permission denied.")
        if (lower.contains("connection refused") || lower.contains("network is unreachable") || lower.contains("could not connect") || lower.contains("timeout")) return RecoveryAction("Check network", listOf("ping -c 2 -W 3 8.8.8.8 2>&1 || echo 'Network offline'"), 0.35f, "Network issue detected.")
        if (lower.contains("has no installation candidate") || lower.contains("unable to locate package")) return RecoveryAction("Update pkg lists", listOf("apt-get update 2>&1 || echo 'Update failed'"), 0.7f, "Package lists may be outdated.")
        if (lower.contains("syntaxerror") || lower.contains("syntax error")) return RecoveryAction("Check syntax", emptyList(), 0.3f, "Syntax error. Check quotes, brackets, indentation.")
        return null
    }

    // ── Caches ──────────────────────────────────────────────────────────
    private val npmScriptsCache = mutableListOf<String>()
    suspend fun refreshNpmScripts(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val shell = getShell() ?: return@withContext
            val r = shell.execute("cat package.json 2>/dev/null | grep -o '\"[a-z-]*\":' | tr -d '\":'", timeoutMs = TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
            if (r.isSuccess) { npmScriptsCache.clear(); npmScriptsCache.addAll(r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }) }
        } catch (e: Exception) { Log.w(TAG, "NPM cache: ${e.message}") }
    }
    suspend fun refreshGitBranches(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val shell = getShell() ?: return@withContext
            val r = shell.execute("git branch -a 2>/dev/null | sed 's/^*//' | sed 's/^[[:space:]]*//' | sed 's/(HEAD.*)//' | tr -d ' '", timeoutMs = TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
            if (r.isSuccess) gitBranchCache[sessionName] = r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("(") }
        } catch (e: Exception) { Log.w(TAG, "Git cache: ${e.message}") }
    }

    // ── Path Resolution ────────────────────────────────────────────────
    private fun resolvePath(currentDir: String, target: String): String = when {
        target == "." -> currentDir
        target == ".." -> File(currentDir).parent ?: currentDir
        target.startsWith("/") -> target
        target.startsWith("..") -> {
            val parts = target.split("/").filter { it.isNotBlank() }
            var resolved = File(currentDir)
            for (p in parts) resolved = when (p) { ".." -> resolved.parentFile ?: resolved; "." -> resolved; else -> File(resolved, p) }
            resolved.absolutePath
        }
        else -> "$currentDir/$target"
    }

    // ── Session Persistence ────────────────────────────────────────────
    private fun scheduleAutoSave() { autoSaveJob?.cancel(); autoSaveJob = autoSaveScope.launch { delay(TerminalConfig.AUTO_SAVE_DELAY_MS); saveSessionsToDisk() } }
    fun shutdown() {
        autoSaveJob?.cancel(); autoSaveJob = null
        persistentShell?.destroy(); persistentShell = null
        backgroundJobs.values.filter { it.isActive }.forEach { cancelJob(it.id) }
        saveSessionsToDisk()
        sessions.clear()
    }
    fun saveSessionsToDisk() {
        try { File(context.filesDir, "terminal_sessions.json").writeText(Gson().toJson(sessions.map { (n, s) -> mapOf("name" to n, "workdir" to s.workdir, "envVars" to s.envVars.toMap(), "aliases" to s.aliases.toMap(), "historyCount" to s.history.size, "outputLines" to s.outputLines.takeLast(200)) })) }
        catch (e: Exception) { Log.w(TAG, "Save failed: ${e.message}") }
    }
    fun loadSessionsFromDisk() {
        try {
            val file = File(context.filesDir, "terminal_sessions.json"); if (!file.exists()) return
            val data: List<Map<String, Any?>> = Gson().fromJson(file.readText(), object : TypeToken<List<Map<String, Any?>>>() {}.type)
            data.forEach { entry ->
                val name = entry["name"] as? String ?: return@forEach
                if (!sessions.containsKey(name)) sessions[name] = TerminalSession(name = name, workdir = entry["workdir"] as? String ?: DEFAULT_WORKDIR, envVars = (entry["envVars"] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf(), aliases = (entry["aliases"] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf())
            }
            Log.d(TAG, "Loaded ${data.size} sessions")
        } catch (e: Exception) { Log.w(TAG, "Load failed: ${e.message}") }
    }
}
