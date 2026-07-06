package com.interndra.agent

import android.content.Context
import android.util.Log
import com.interndra.service.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TerminalAgent — persistent shell session manager with auto-completion
 * and error recovery for INTERNDRA's AI terminal.
 *
 * ## Architecture
 * ```
 * TerminalAgent
 *   ├── SessionManager    — multiple named sessions with PWD/env/history
 *   ├── CompletionEngine  — git/pkg/pip/npm/path smart completions
 *   └── RecoveryEngine    — error pattern detection + auto-fix commands
 * ```
 *
 * ## Usage
 * ```kotlin
 * val agent = TerminalAgent(context, termuxBridge)
 * agent.createSession("project-a", "/data/data/com.termux/files/home/project")
 * val result = agent.execute("project-a", "npm install")
 * val suggestions = agent.suggestCompletions("git com")
 * val recovery = agent.suggestRecovery("git: command not found")
 * ```
 */
class TerminalAgent(
    private val context: Context,
    private val termuxBridge: TermuxBridge
) {
    companion object {
        private const val TAG = "TerminalAgent"

        // Recovery timeouts (milliseconds)
        private const val RECOVERY_TIMEOUT_MS = 15_000L
        private const val DEF_INSTALL_TIMEOUT_MS = 120_000L

        // Default Termux home
        private val TERMUX_HOME = "/data/data/com.termux/files/home"
        private val TERMUX_USR_BIN = "/data/data/com.termux/files/usr/bin"
    }

    // ── Session ────────────────────────────────────────────────────────────

    data class TerminalSession(
        val name: String,
        var workdir: String,
        val envVars: MutableMap<String, String> = mutableMapOf(),
        val history: MutableList<HistoryEntry> = mutableListOf(),
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
        val wasRecovered: Boolean = false
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
        val recoveryActions: List<String> = emptyList()
    )

    // ── Auto-completion ────────────────────────────────────────────────────

    data class CompletionSuggestion(
        val text: String,
        val displayText: String,
        val category: String,  // "git", "pkg", "pip", "npm", "path", "command"
        val score: Float       // 0.0 to 1.0
    )

    // ── Error recovery ────────────────────────────────────────────────────

    data class RecoveryAction(
        val description: String,
        val commands: List<String>,
        val confidence: Float,  // 0.0 to 1.0
        val explanation: String
    )

    // ── State ────────────────────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val defaultSessionName = "default"

    // Known git branch cache (refreshed per session)
    private val gitBranchCache = mutableMapOf<String, List<String>>()

    // ── Session Management ────────────────────────────────────────────────

    /**
     * Create a new terminal session with the given name and initial workdir.
     * If the session already exists, it is returned unchanged.
     */
    fun createSession(name: String, workdir: String = TERMUX_HOME): TerminalSession {
        return sessions.getOrPut(name) {
            TerminalSession(
                name = name,
                workdir = workdir,
                envVars = mutableMapOf(
                    "HOME" to TERMUX_HOME,
                    "PATH" to "$TERMUX_USR_BIN:/usr/bin:/bin",
                    "PWD" to workdir,
                    "TERM" to "xterm-256color"
                )
            ).also { Log.d(TAG, "Created session '$name' at $workdir") }
        }
    }

    /**
     * Get or create the default session.
     */
    fun getDefaultSession(): TerminalSession = createSession(defaultSessionName)

    /**
     * Get a session by name, creating it if it doesn't exist.
     */
    fun getOrCreateSession(name: String, workdir: String = TERMUX_HOME): TerminalSession =
        sessions[name] ?: createSession(name, workdir)

    /**
     * Get all active sessions.
     */
    fun getAllSessions(): List<TerminalSession> = sessions.values.toList()

    /**
     * Get session names for UI display.
     */
    fun getSessionNames(): List<String> = sessions.keys.toList()

    /**
     * Remove a session.
     */
    fun removeSession(name: String) {
        sessions.remove(name)
        Log.d(TAG, "Removed session '$name'")
    }

    /**
     * Rename a session.
     */
    fun renameSession(oldName: String, newName: String): Boolean {
        val session = sessions.remove(oldName) ?: return false
        sessions[newName] = session.copy(name = newName)
        return true
    }

    /**
     * Get the working directory for a session.
     */
    fun getWorkdir(sessionName: String): String =
        sessions[sessionName]?.workdir ?: TERMUX_HOME

    /**
     * Change the working directory for a session (in-memory tracking).
     * Returns the new resolved path.
     */
    fun changeWorkdir(sessionName: String, target: String): String {
        val session = sessions[sessionName] ?: return target
        synchronized(session) {
            val resolved = resolvePath(session.workdir, target)
            session.workdir = resolved
            session.envVars["PWD"] = resolved
            session.lastActiveAt = System.currentTimeMillis()
        }
        return session.workdir
    }

    /**
     * Get history for a session.
     */
    fun getHistory(sessionName: String, limit: Int = 50): List<HistoryEntry> =
        sessions[sessionName]?.history?.takeLast(limit)?.reversed() ?: emptyList()

    /**
     * Clear history for a session.
     */
    fun clearHistory(sessionName: String) {
        sessions[sessionName]?.let { session ->
            synchronized(session) {
                session.history.clear()
            }
        }
    }

    // ── Command Execution ─────────────────────────────────────────────────

    /**
     * Execute a command in the context of a terminal session.
     * Handles `cd` natively (updates session PWD without running shell command).
     * Supports command chaining (&&, ||) and session-aware path resolution.
     */
    suspend fun execute(
        sessionName: String,
        command: String,
        timeoutMs: Long = 60_000L
    ): SessionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val session = getOrCreateSession(sessionName)
        val trimmed = command.trim()

        // Handle `cd` natively — update session state without shell call
        if (trimmed.startsWith("cd ")) {
            val target = trimmed.removePrefix("cd ").trim().trim('"').trim('\'')
            if (target.isNotBlank()) {
                val newDir: String
                synchronized(session) {
                    newDir = resolvePath(session.workdir, target)
                    session.workdir = newDir
                    session.envVars["PWD"] = newDir
                }
                Log.d(TAG, "[$sessionName] cd → $newDir")
                return@withContext SessionResult(
                    stdout = "",
                    stderr = "",
                    exitCode = 0,
                    isSuccess = true,
                    sessionName = sessionName,
                    workdir = session.workdir, // after synchronized update
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
        }

        // Run command with workdir awareness
        val workdir = session.workdir
        val result = termuxBridge.executeShell(command, workdir = workdir, timeoutMs = timeoutMs)

        // Track the session state (thread-safe)
        val duration = System.currentTimeMillis() - startMs
        synchronized(session) {
            session.lastActiveAt = System.currentTimeMillis()
            session.history.add(HistoryEntry(
                command = trimmed,
                workdir = workdir,
                exitCode = result.exitCode,
                stdout = result.stdout.take(2000),
                stderr = result.stderr.take(1000),
                durationMs = duration
            ))
            // Trim history to last 200 entries
            while (session.history.size > 200) {
                session.history.removeAt(0)
            }
        }

        SessionResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            isSuccess = result.isSuccess,
            sessionName = sessionName,
            workdir = workdir,
            durationMs = duration
        )
    }

    /**
     * Execute a command with automatic error recovery.
     * If the command fails, the recovery engine suggests a fix,
     * applies it, and retries the original command.
     */
    suspend fun executeWithRecovery(
        sessionName: String,
        command: String,
        timeoutMs: Long = 120_000L
    ): SessionResult = withContext(Dispatchers.IO) {
        val firstResult = execute(sessionName, command, timeoutMs)

        if (firstResult.isSuccess) {
            return@withContext firstResult
        }

        // Try to recover
        val errorText = "${firstResult.stderr}\n${firstResult.stdout}"
        val recovery = suggestRecovery(command, errorText)

        if (recovery == null) {
            return@withContext firstResult.copy(
                stderr = firstResult.stderr + "\n(No automated recovery available)"
            )
        }

        // Recovery with hint but no auto-fix commands — provide explanation
        if (recovery.commands.isEmpty()) {
            return@withContext firstResult.copy(
                stderr = firstResult.stderr + "\n\n💡 ${recovery.explanation}",
                wasRecovered = false,
                recoveryActions = emptyList()
            )
        }

        Log.d(TAG, "[$sessionName] Attempting recovery: ${recovery.description}")

        // Apply recovery commands
        val recoveryResults = mutableListOf<String>()
        var allRecoveryOk = true

        for (recoveryCmd in recovery.commands) {
            val recoveryResult = execute(sessionName, recoveryCmd, RECOVERY_TIMEOUT_MS)
            recoveryResults.add("$ ${recoveryCmd.take(80)}\n${recoveryResult.stdout.take(200)}")
            if (!recoveryResult.isSuccess) {
                allRecoveryOk = false
                Log.w(TAG, "[$sessionName] Recovery step failed: $recoveryCmd")
            }
        }

        if (!allRecoveryOk) {
            return@withContext firstResult.copy(
                stderr = firstResult.stderr + "\n⚠ Recovery attempted but incomplete: ${recovery.explanation}",
                wasRecovered = false,
                recoveryActions = recoveryResults
            )
        }

        // Retry the original command
        Log.d(TAG, "[$sessionName] Recovery applied, retrying command...")
        val retryResult = execute(sessionName, command, timeoutMs)

        if (retryResult.isSuccess) {
            retryResult.copy(
                stdout = "${retryResult.stdout}\n\n⚡ Auto-recovered: ${recovery.description}",
                wasRecovered = true,
                recoveryActions = recoveryResults
            )
        } else {
            // Recovery was attempted but original command still fails — include recovery info
            retryResult.copy(
                stderr = retryResult.stderr + "\n\n⚠ Auto-recovery attempted but command still failed:\n${recovery.explanation}",
                wasRecovered = false,
                recoveryActions = recoveryResults
            )
        }
    }

    // ── Auto-Completion ──────────────────────────────────────────────────

    /**
     * Suggest command completions for a partial input in a session context.
     */
    suspend fun suggestCompletions(
        partial: String,
        sessionName: String = defaultSessionName,
        maxResults: Int = 10
    ): List<CompletionSuggestion> = withContext(Dispatchers.Default) {
        if (partial.isBlank()) return@withContext emptyList()

        val trimmed = partial.trimStart()
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

    private fun completeGit(input: String): List<CompletionSuggestion> {
        val gitCommands = listOf(
            "add", "commit", "push", "pull", "clone", "status", "log",
            "branch", "checkout", "merge", "rebase", "stash", "diff",
            "remote", "fetch", "reset", "tag", "init", "config", "rm"
        )
        val afterGit = input.removePrefix("git").trim()
        if (afterGit.isBlank()) {
            return gitCommands.map { cmd ->
                CompletionSuggestion(
                    text = cmd, displayText = "git $cmd",
                    category = "git", score = 0.9f
                )
            }
        }

        val parts = afterGit.split(" ")
        val subCmd = parts.first()

        // Complete git subcommands
        if (parts.size == 1) {
            val matches = gitCommands.filter { it.startsWith(subCmd) }
            return matches.map { cmd ->
                CompletionSuggestion(
                    text = cmd.removePrefix(subCmd), displayText = "git $cmd",
                    category = "git", score = 0.85f
                )
            }
        }

        // Complete git branch names for checkout/merge
        val isBranchCmd = subCmd in listOf("checkout", "switch", "merge", "branch", "rebase")
        if (isBranchCmd && parts.size == 2) {
            val partialBranch = parts.last()
            val branches = gitBranchCache.values.flatten()
            val matches = branches.filter { it.startsWith(partialBranch) }
            return matches.map { branch ->
                CompletionSuggestion(
                    text = branch.removePrefix(partialBranch),
                    displayText = "git $subCmd $branch",
                    category = "git-branch", score = 0.7f
                )
            }
        }

        return emptyList()
    }

    private fun completePkg(input: String): List<CompletionSuggestion> {
        val pkgCommands = listOf(
            "install", "uninstall", "update", "upgrade", "search",
            "list-installed", "show", "files", "reinstall"
        )
        val afterPkg = input.removePrefix("pkg").trim()
        if (afterPkg.isBlank()) {
            return pkgCommands.map { cmd ->
                CompletionSuggestion(
                    text = cmd, displayText = "pkg $cmd",
                    category = "pkg", score = 0.9f
                )
            }
        }
        if (!afterPkg.contains(" ")) {
            val matches = pkgCommands.filter { it.startsWith(afterPkg) }
            return matches.map { cmd ->
                CompletionSuggestion(
                    text = cmd.removePrefix(afterPkg),
                    displayText = "pkg $cmd",
                    category = "pkg", score = 0.85f
                )
            }
        }
        return emptyList()
    }

    private fun completePip(input: String): List<CompletionSuggestion> {
        val pipCommands = listOf(
            "install", "uninstall", "freeze", "list", "show",
            "search", "check", "download", "wheel"
        )
        val afterPip = input.removePrefix("pip").removePrefix("3").trim()
        if (afterPip.isBlank()) {
            return pipCommands.map { cmd ->
                CompletionSuggestion(
                    text = cmd, displayText = "pip $cmd",
                    category = "pip", score = 0.9f
                )
            }
        }
        if (!afterPip.contains(" ")) {
            val matches = pipCommands.filter { it.startsWith(afterPip) }
            return matches.map { cmd ->
                CompletionSuggestion(
                    text = cmd.removePrefix(afterPip),
                    displayText = "pip $cmd",
                    category = "pip", score = 0.85f
                )
            }
        }
        return emptyList()
    }

    private fun completeNpm(input: String): List<CompletionSuggestion> {
        val npmCommands = listOf(
            "init", "install", "run", "start", "test", "build",
            "publish", "update", "list", "audit", "fix", "ci"
        )
        val afterNpm = input.removePrefix("npm").trim()
        if (afterNpm.isBlank()) {
            return npmCommands.map { cmd ->
                CompletionSuggestion(
                    text = cmd, displayText = "npm $cmd",
                    category = "npm", score = 0.9f
                )
            }
        }
        if (!afterNpm.contains(" ")) {
            val matches = npmCommands.filter { it.startsWith(afterNpm) }
            return matches.map { cmd ->
                CompletionSuggestion(
                    text = cmd.removePrefix(afterNpm),
                    displayText = "npm $cmd",
                    category = "npm", score = 0.85f
                )
            }
        }
        // Complete npm run scripts from package.json
        if (afterNpm.startsWith("run ")) {
            val partialScript = afterNpm.removePrefix("run ").trim()
            val scripts = npmScriptsCache
            val matches = scripts.filter { it.startsWith(partialScript) }
            return matches.map { script ->
                CompletionSuggestion(
                    text = script.removePrefix(partialScript),
                    displayText = "npm run $script",
                    category = "npm-script", score = 0.75f
                )
            }
        }
        return emptyList()
    }

    private fun completePath(input: String, workdir: String): List<CompletionSuggestion> {
        try {
            val parts = input.split(" ")
            val pathPart = parts.lastOrNull() ?: return emptyList()
            val isDir = parts.firstOrNull() == "cd"

            val basePath = if (pathPart.startsWith("/")) pathPart
            else "$workdir/$pathPart"

            val parentDir = File(basePath).parentFile ?: File(basePath)
            val prefix = File(basePath).name

            // Note: called from suggestCompletions which runs on Dispatchers.Default.
            // File operations are fast for typical directories; explicit IO dispatcher
            // would add overhead. Acceptable for completion use case.
            val files = parentDir.listFiles() ?: return emptyList()
            val matches = files.filter { f ->
                f.name.startsWith(prefix) &&
                (isDir || !isDir) // always show files/dirs
            }

            return matches.map { f ->
                val suffix = if (f.isDirectory) "/" else ""
                CompletionSuggestion(
                    text = f.name.removePrefix(prefix) + suffix,
                    displayText = f.name + suffix,
                    category = if (f.isDirectory) "directory" else "file",
                    score = 0.8f
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun completeCommand(input: String): List<CompletionSuggestion> {
        // Common Linux/Android shell commands
        val commonCommands = listOf(
            "ls", "cd", "cat", "echo", "pwd", "mkdir", "rm", "cp", "mv",
            "touch", "chmod", "grep", "find", "head", "tail", "sort",
            "wc", "cut", "diff", "tar", "zip", "unzip", "curl", "wget",
            "python", "python3", "node", "npm", "git", "pkg", "pip",
            "pip3", "make", "gcc", "g++", "ruby", "perl", "php",
            "vim", "nano", "less", "more", "clear", "history", "env",
            "export", "source", ".", "which", "whereis", "type",
            "chsh", "termux-setup-storage", "ping", "nslookup", "dig",
            "ssh", "scp", "rsync", "htop", "top", "ps", "kill",
            "df", "du", "free", "uname", "neofetch", "screenfetch",
            "termux-wifi-connectioninfo", "termux-battery-status",
            "termux-sensor", "termux-clipboard-get", "termux-clipboard-set",
            "termux-toast", "termux-vibrate", "termux-torch"
        )
        return commonCommands.filter { it.startsWith(input) }.map { cmd ->
            CompletionSuggestion(
                text = cmd.removePrefix(input),
                displayText = cmd,
                category = "command",
                score = 0.7f
            )
        }
    }

    // ── Error Recovery ───────────────────────────────────────────────────

    /**
     * Analyze a failed command and suggest recovery actions.
     */
    fun suggestRecovery(command: String, error: String): RecoveryAction? {
        val lower = error.lowercase()

        // command not found → install package
        val cmdNotFoundMatch = Regex("""(\S+):\s*(?:command not found|not found|no such file)""", RegexOption.IGNORE_CASE).find(error)
        if (cmdNotFoundMatch != null) {
            val missingCmd = cmdNotFoundMatch.groupValues[1].lowercase()
            val packageMap = mapOf(
                "git" to "git", "python3" to "python", "python" to "python",
                "pip" to "python-pip", "pip3" to "python-pip",
                "node" to "nodejs", "npm" to "nodejs",
                "make" to "make", "gcc" to "gcc", "g++" to "gcc",
                "curl" to "curl", "wget" to "wget",
                "tar" to "tar", "zip" to "zip", "unzip" to "unzip",
                "htop" to "htop", "vim" to "vim", "nano" to "nano",
                "rsync" to "rsync", "screenfetch" to "screenfetch",
                "neofetch" to "neofetch", "ffmpeg" to "ffmpeg",
                "ruby" to "ruby", "perl" to "perl", "php" to "php"
            )
            val pkg = packageMap[missingCmd] ?: return RecoveryAction(
                description = "Package '$missingCmd' not available — try 'pkg search' first",
                commands = listOf("pkg search $missingCmd 2>/dev/null | head -20"),
                confidence = 0.3f,
                explanation = "Command '$missingCmd' was not found. It may need to be installed."
            )
            return RecoveryAction(
                description = "Install '$pkg' package via pkg",
                commands = listOf("pkg install -y $pkg 2>&1"),
                confidence = 0.85f,
                explanation = "The command '$missingCmd' was not found. Installing '$pkg' via pkg..."
            )
        }

        // pip install failed → module not found
        val pipModuleMatch = Regex("""(?:ModuleNotFoundError|ImportError|No module named)\s*['\"]?(\w[\w.-]*)['\"]?""").find(lower)
        if (pipModuleMatch != null) {
            val module = pipModuleMatch.groupValues[1]
            return RecoveryAction(
                description = "Install Python module '$module' via pip",
                commands = listOf("pip install $module 2>&1"),
                confidence = 0.9f,
                explanation = "Python module '$module' not found. Installing via pip..."
            )
        }

        // npm ERR! missing script
        if (lower.contains("npm err") && lower.contains("missing script")) {
            val scriptMatch = Regex("""missing script:\s*(\S+)""", RegexOption.IGNORE_CASE).find(lower)
            val script = scriptMatch?.groupValues?.getOrNull(1) ?: "unknown"
            return RecoveryAction(
                description = "npm script '$script' not found. Check package.json scripts.",
                commands = listOf("cat package.json 2>/dev/null | grep -A20 '\"scripts\"' || echo 'No package.json found'"),
                confidence = 0.6f,
                explanation = "The npm script '$script' doesn't exist in package.json. Checking available scripts..."
            )
        }

        // npm ERR! missing package.json
        if (lower.contains("npm err") && (lower.contains("package.json") || lower.contains("enoent"))) {
            return RecoveryAction(
                description = "Initialize npm project with package.json",
                commands = listOf("npm init -y 2>&1"),
                confidence = 0.7f,
                explanation = "No package.json found. Creating one with 'npm init -y'..."
            )
        }

        // npm ERR! missing dependencies
        if (lower.contains("npm err") && lower.contains("not found") && lower.contains("module")) {
            return RecoveryAction(
                description = "Install npm dependencies",
                commands = listOf("npm install 2>&1"),
                confidence = 0.75f,
                explanation = "npm dependencies are missing. Running 'npm install'..."
            )
        }

        // git: not a git repository
        if (lower.contains("not a git repository") || lower.contains("fatal: not a git")) {
            return RecoveryAction(
                description = "Initialize git repository",
                commands = listOf("git init 2>&1", "git add . 2>&1"),
                confidence = 0.65f,
                explanation = "This is not a git repository. Initializing one..."
            )
        }

        // git: remote not found
        if (lower.contains("remote") && (lower.contains("not found") || lower.contains("does not appear"))) {
            return RecoveryAction(
                description = "Check git remote configuration",
                commands = listOf("git remote -v 2>&1"),
                confidence = 0.5f,
                explanation = "Git remote is misconfigured. Checking remotes..."
            )
        }

        // Permission denied
        if (lower.contains("permission denied") || lower.contains("not permitted")) {
            return RecoveryAction(
                description = "Fix file permissions with chmod",
                commands = listOf("chmod +x ${command.split(" ").firstOrNull() ?: "script"}"),
                confidence = 0.4f,
                explanation = "Permission denied. This may require adjusting file permissions or using 'allow-external-apps=true' in Termux properties."
            )
        }

        // Network unreachable / connection refused
        if (lower.contains("connection refused") || lower.contains("network is unreachable") ||
            lower.contains("could not connect") || lower.contains("timeout")) {
            return RecoveryAction(
                description = "Check network connectivity",
                commands = listOf("ping -c 2 -W 3 8.8.8.8 2>&1 || echo 'Network offline'"),
                confidence = 0.35f,
                explanation = "Network issue detected. Internet may be unavailable."
            )
        }

        // pkg update needed
        if (lower.contains("has no installation candidate") || lower.contains("unable to locate package") ||
            lower.contains("package.*not found")) {
            return RecoveryAction(
                description = "Update package lists with pkg update",
                commands = listOf("pkg update 2>&1"),
                confidence = 0.7f,
                explanation = "Package lists may be outdated. Running 'pkg update'..."
            )
        }

        // Python syntax error
        if (lower.contains("syntaxerror") || lower.contains("syntax error")) {
            return RecoveryAction(
                description = "Check Python command syntax",
                commands = emptyList(),
                confidence = 0.3f,
                explanation = "Python syntax error detected. Check for: missing quotes on strings, " +
                        "unclosed brackets/parentheses, incorrect indentation, or missing colons after if/for/def."
            )
        }

        // No match found — return null so caller knows we can't recover
        return null
    }

    // ── Caches ──────────────────────────────────────────────────────────

    private val npmScriptsCache = mutableListOf<String>()

    /**
     * Refresh the npm scripts cache from a session's workdir.
     */
    suspend fun refreshNpmScripts(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionName] ?: return@withContext
            val result = termuxBridge.executeShell(
                "cat package.json 2>/dev/null | grep -o '\"[a-z-]*\":' | tr -d '\":'",
                workdir = session.workdir,
                timeoutMs = 5000
            )
            if (result.isSuccess) {
                npmScriptsCache.clear()
                npmScriptsCache.addAll(result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh npm scripts: ${e.message}")
        }
    }

    // ── Git branch cache ────────────────────────────────────────────────

    /**
     * Refresh git branch names for a session's workdir.
     */
    suspend fun refreshGitBranches(sessionName: String) = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionName] ?: return@withContext
            val result = termuxBridge.executeShell(
                "git branch -a 2>/dev/null | sed 's/^*//' | sed 's/^[[:space:]]*//' | sed 's/(HEAD.*)//' | tr -d ' '",
                workdir = session.workdir,
                timeoutMs = 5000
            )
            if (result.isSuccess) {
                val branches = result.stdout.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("(") }
                gitBranchCache[sessionName] = branches
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh git branches: ${e.message}")
        }
    }

    // ── Path Resolution ────────────────────────────────────────────────

    /**
     * Resolve a target path relative to a current working directory.
     * Handles: ., .., ~, absolute paths
     */
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

    // ── Disk-backed session persistence ─────────────────────────────────

    /**
     * Save session state to a file for persistence across app restarts.
     * This is optional — sessions are recreated on demand.
     */
    fun saveSessionsToDisk() {
        try {
            val file = File(context.filesDir, "terminal_sessions.json")
            val data = sessions.map { (name, session) ->
                mapOf(
                    "name" to name,
                    "workdir" to session.workdir,
                    "envVars" to session.envVars.toMap(),
                    "historyCount" to session.history.size
                )
            }
            file.writeText(com.google.gson.Gson().toJson(data))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save sessions: ${e.message}")
        }
    }
}
