package com.interndra.agent

import com.interndra.ai.tools.ToolCategory
import com.interndra.ai.tools.ToolDescriptor
import com.interndra.ai.tools.ToolResult
import com.interndra.service.TerminalBackend
import com.interndra.service.TerminalConfig

/**
 * TerminalTool — the Embedded Linux Terminal exposed as a first-class,
 * structured Agent tool (spec §1, §2).
 *
 * The Agent NEVER touches Android Process APIs directly: every operation
 * goes through the [TerminalBackend] abstraction, and every invocation is
 * tracked with a unique tool-call ID (observability, §32).
 *
 * Tool surface (mirrors the spec):
 * - terminal.execute        → run a command, get structured result
 * - terminal.create_session → start/reuse a persistent session
 * - terminal.close_session  → release a session
 * - terminal.send_input     → write stdin to an interactive process
 * - terminal.stop           → stop the running process / background job
 * - terminal.start_background → start a long-running process, get process_id
 * - terminal.get_process    → query a background process
 * - terminal.list_processes → list background processes
 *
 * Background processes are backed by [TerminalAgent]'s job manager when
 * available; otherwise [startBackground] is unsupported and reports it.
 */
class TerminalTool(
    private val backend: TerminalBackend,
    private val terminalAgent: TerminalAgent? = null,
    private val tracker: ToolCallTracker = ToolCallTracker(),
    private val planner: CommandPlanner = CommandPlanner
) {

    /** Permission level for a tool invocation (drives safety gating). */
    enum class Permission { SAFE, LOW_RISK, HIGH_RISK, DESTRUCTIVE }

    /** Metadata for one tool in the surface. */
    data class ToolMeta(
        val name: String,
        val description: String,
        val inputSchema: Map<String, String>,
        val outputSchema: Map<String, String>,
        val permission: Permission,
        val defaultTimeoutMs: Long,
        val cancellable: Boolean
    )

    /** Structured result of terminal.execute. */
    data class ExecuteResult(
        val toolCallId: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val workingDirectory: String,
        val success: Boolean
    )

    /** Snapshot of a background process. */
    data class ProcessInfo(
        val processId: Int,
        val sessionId: String,
        val command: String,
        val status: String,
        val exitCode: Int?,
        val outputPreview: String
    )

    // ── Tool metadata (input/output schema per tool) ─────────────────────

    val tools: List<ToolMeta> = listOf(
        ToolMeta(
            name = "terminal.execute",
            description = "Execute a command inside the embedded Linux environment and return its structured result.",
            inputSchema = mapOf("command" to "string", "working_directory" to "string?", "timeout_ms" to "long?"),
            outputSchema = mapOf("exit_code" to "int", "stdout" to "string", "stderr" to "string", "duration_ms" to "long", "working_directory" to "string"),
            permission = Permission.LOW_RISK,
            defaultTimeoutMs = TerminalConfig.AGENT_TIMEOUT_MS,
            cancellable = true
        ),
        ToolMeta(
            name = "terminal.create_session",
            description = "Start (or reuse) a persistent terminal session with a working directory.",
            inputSchema = mapOf("session_id" to "string", "working_directory" to "string?"),
            outputSchema = mapOf("session_id" to "string", "working_directory" to "string"),
            permission = Permission.SAFE,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = false
        ),
        ToolMeta(
            name = "terminal.close_session",
            description = "Close a terminal session and release its resources.",
            inputSchema = mapOf("session_id" to "string"),
            outputSchema = mapOf("closed" to "bool"),
            permission = Permission.SAFE,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = false
        ),
        ToolMeta(
            name = "terminal.send_input",
            description = "Send raw input (stdin) to an interactive process in a session.",
            inputSchema = mapOf("session_id" to "string", "input" to "string"),
            outputSchema = mapOf("accepted" to "bool"),
            permission = Permission.LOW_RISK,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = true
        ),
        ToolMeta(
            name = "terminal.stop",
            description = "Stop the running process in a session, or a specific background process by id.",
            inputSchema = mapOf("session_id" to "string", "process_id" to "int?"),
            outputSchema = mapOf("stopped" to "bool"),
            permission = Permission.LOW_RISK,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = false
        ),
        ToolMeta(
            name = "terminal.start_background",
            description = "Start a long-running process (server, dev watcher) in the background and return its process id.",
            inputSchema = mapOf("session_id" to "string", "command" to "string"),
            outputSchema = mapOf("process_id" to "int", "status" to "string"),
            permission = Permission.LOW_RISK,
            defaultTimeoutMs = TerminalConfig.AGENT_TIMEOUT_MS,
            cancellable = true
        ),
        ToolMeta(
            name = "terminal.get_process",
            description = "Query the status and recent output of a background process.",
            inputSchema = mapOf("process_id" to "int"),
            outputSchema = mapOf("process_id" to "int", "status" to "string", "exit_code" to "int?", "output" to "string"),
            permission = Permission.SAFE,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = false
        ),
        ToolMeta(
            name = "terminal.list_processes",
            description = "List all background processes (optionally for one session).",
            inputSchema = mapOf("session_id" to "string?"),
            outputSchema = mapOf("processes" to "list"),
            permission = Permission.SAFE,
            defaultTimeoutMs = TerminalConfig.RECOVERY_TIMEOUT_MS,
            cancellable = false
        )
    )

    /** Look up metadata by tool name. */
    fun meta(name: String): ToolMeta? = tools.firstOrNull { it.name == name }

    // ── Session tools ────────────────────────────────────────────────────

    /** Start (or reuse) a persistent session. */
    suspend fun createSession(sessionId: String, workingDirectory: String = "/workspace"): String {
        val handle = backend.startSession(sessionId, workingDirectory)
        return handle.workdir
    }

    /** Close a session. */
    suspend fun closeSession(sessionId: String) {
        backend.closeSession(sessionId)
    }

    // ── Execution ────────────────────────────────────────────────────────

    /**
     * Execute a command and return its structured result.
     * [timeoutMs] defaults to [CommandPlanner.inferTimeout] when not provided.
     */
    suspend fun execute(
        command: String,
        sessionId: String = "agent",
        workingDirectory: String? = null,
        timeoutMs: Long? = null
    ): ExecuteResult {
        val effectiveTimeout = timeoutMs ?: planner.inferTimeout(command)
        val callId = tracker.start("terminal.execute", command, sessionId)
        val startedAt = System.currentTimeMillis()

        // Honor the requested working directory (persists for later commands).
        if (workingDirectory != null) {
            runCatching { backend.changeWorkdir(sessionId, workingDirectory) }
        }

        val result = backend.execute(sessionId, command, effectiveTimeout)

        val durationMs = System.currentTimeMillis() - startedAt
        tracker.finish(callId, result.exitCode, result.success)
        return ExecuteResult(
            toolCallId = callId,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = durationMs,
            workingDirectory = workingDirectory ?: "",
            success = result.success
        )
    }

    // ── Interactive input ────────────────────────────────────────────────

    /** Send raw stdin to an interactive process. */
    suspend fun sendInput(sessionId: String, input: String): Boolean =
        backend.writeInput(sessionId, input)

    /** Stop the running process in a session (or a specific background job). */
    suspend fun stop(sessionId: String, processId: Int? = null): Boolean {
        if (processId != null) return terminalAgent?.cancelJob(processId) ?: false
        return backend.stopProcess(sessionId)
    }

    // ── Background processes (long-running) ──────────────────────────────

    /** Start a background process. Returns its process id, or -1 if unsupported. */
    fun startBackground(sessionId: String, command: String): Int {
        val agent = terminalAgent ?: return -1
        return agent.executeBackground(sessionId, command).id
    }

    /** Query a background process. */
    fun getProcess(processId: Int): ProcessInfo? {
        val agent = terminalAgent ?: return null
        val job = agent.getJob(processId) ?: return null
        return ProcessInfo(
            processId = job.id,
            sessionId = job.sessionName,
            command = job.command,
            status = job.status.name,
            exitCode = job.exitCode,
            outputPreview = job.output.take(400)
        )
    }

    /** List background processes, optionally filtered by session. */
    fun listProcesses(sessionId: String? = null): List<ProcessInfo> {
        val agent = terminalAgent ?: return emptyList()
        val jobs = if (sessionId != null) agent.listJobsForSession(sessionId) else agent.listJobs()
        return jobs.map {
            ProcessInfo(
                processId = it.id,
                sessionId = it.sessionName,
                command = it.command,
                status = it.status.name,
                exitCode = it.exitCode,
                outputPreview = it.output.take(400)
            )
        }
    }

    /** Recent tool-call diagnostics (newest first). */
    val recentCalls: List<ToolCallTracker.ToolCall> get() = tracker.recentCalls

    // ── ToolRegistry integration ─────────────────────────────────────────

    /**
     * Convert the terminal tool surface into [ToolDescriptor]s so it can be
     * registered in the existing Agent Tool Registry.
     */
    fun toToolDescriptors(): List<ToolDescriptor> = tools.map { meta ->
        object : ToolDescriptor {
            override val name: String = meta.name
            override val category: ToolCategory = ToolCategory.SHELL
            override val description: String = meta.description
            override val keywords: List<String> = listOf(meta.name)
            override val sortKey: String = meta.name

            override suspend fun execute(params: Map<String, String>): ToolResult {
                return when (meta.name) {
                    "terminal.execute" -> {
                        val command = params["command"].orEmpty()
                        if (command.isBlank()) return ToolResult(false, error = "No command provided")
                        val res = execute(
                            command = command,
                            sessionId = params["session_id"] ?: "agent",
                            workingDirectory = params["working_directory"]
                        )
                        ToolResult(
                            success = res.success,
                            output = if (res.success) res.stdout.take(2000)
                                     else "exit ${res.exitCode}: ${res.stderr.take(500)}",
                            error = if (res.success) "" else res.stderr.take(500)
                        )
                    }
                    "terminal.create_session" -> {
                        val id = params["session_id"] ?: "agent"
                        val wd = params["working_directory"] ?: "/workspace"
                        createSession(id, wd)
                        ToolResult(true, output = "session $id ready at $wd")
                    }
                    "terminal.close_session" -> {
                        closeSession(params["session_id"] ?: "agent")
                        ToolResult(true, output = "session closed")
                    }
                    "terminal.send_input" -> {
                        val ok = sendInput(params["session_id"] ?: "agent", params["input"].orEmpty())
                        ToolResult(ok, output = if (ok) "input accepted" else "input rejected")
                    }
                    "terminal.stop" -> {
                        val pid = params["process_id"]?.toIntOrNull()
                        val ok = stop(params["session_id"] ?: "agent", pid)
                        ToolResult(ok, output = if (ok) "stopped" else "nothing to stop")
                    }
                    "terminal.start_background" -> {
                        val command = params["command"].orEmpty()
                        val pid = startBackground(params["session_id"] ?: "agent", command)
                        ToolResult(pid >= 0, output = if (pid >= 0) "process_id=$pid status=running" else "background processes unsupported")
                    }
                    "terminal.get_process" -> {
                        val pid = params["process_id"]?.toIntOrNull()
                        val info = pid?.let { getProcess(it) }
                        ToolResult(info != null, output = info?.let {
                            "process_id=${it.processId} status=${it.status} exit=${it.exitCode ?: "-"} output=${it.outputPreview}"
                        } ?: "process not found")
                    }
                    "terminal.list_processes" -> {
                        val list = listProcesses(params["session_id"])
                        ToolResult(true, output = list.joinToString("\n") {
                            "#${it.processId} ${it.status} ${it.command.take(80)}"
                        }.ifBlank { "(no background processes)" })
                    }
                    else -> ToolResult(false, error = "unknown terminal tool")
                }
            }
        }
    }
}
