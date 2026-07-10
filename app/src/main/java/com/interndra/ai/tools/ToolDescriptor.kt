package com.interndra.ai.tools

import com.interndra.data.model.ShellCommand
import com.interndra.plugin.IPlugin

/**
 * OpenClaw-inspired ToolCategory — categorizes every tool in the unified registry.
 *
 * Mirrors OpenClaw's ToolDescriptor category concept where tools are sorted,
 * filtered, and planned based on their category.
 */
enum class ToolCategory(val label: String) {
    /** Shell/system commands (from CommandRegistry templates) */
    SHELL("Shell"),
    /** Plugin commands (from PluginManager built-in plugins) */
    PLUGIN("Plugin"),
    /** AI-powered commands routed through AiOrchestrator */
    AI_AGENT("AI Agent"),
    /** Navigation actions (home, back, volume, etc.) */
    NAVIGATION("Navigation"),
    /** System information commands (battery, storage, device info) */
    SYSTEM("System"),
    /** File operations (copy, move, delete, zip, etc.) */
    FILE("File"),
    /** Package management (pkg, pip, npm) */
    PACKAGE("Package"),
    /** Network operations (ping, curl, dns, wifi) */
    NETWORK("Network"),
    /** Git operations */
    GIT("Git"),
    /** Background job management */
    TASK("Task")
}

/**
 * ToolResult — the result of executing a [ToolDescriptor].
 *
 * Mirrors OpenClaw's AgentToolResult with content, details, and error semantics.
 */
data class ToolResult(
    val success: Boolean,
    val output: String = "",
    val commands: List<ShellCommand> = emptyList(),
    val error: String = ""
)

/**
 * ToolDescriptor — a unified tool definition inspired by OpenClaw's [ToolDescriptor].
 *
 * Each tool has a name, category, keywords for matching, an executor method,
 * and an availability check. Tools are registered in a [ToolRegistry] and can
 * be discovered by category, keyword, or name.
 *
 * OpenClaw reference:
 * ```typescript
 * interface ToolDescriptor {
 *   name: string;
 *   sortKey?: string;
 *   executor: ExecutorRef;
 *   availability: () => AvailabilityDiagnostic[];
 * }
 * ```
 */
interface ToolDescriptor {
    /** Unique name for this tool (e.g. "battery_info", "git:push") */
    val name: String

    /** Category for grouping and filtering */
    val category: ToolCategory

    /** Human-readable description */
    val description: String

    /** Keywords used for matching user input to this tool */
    val keywords: List<String>

    /** Optional sort key; defaults to name */
    val sortKey: String get() = name

    /**
     * Execute this tool with the given parameters.
     * @param params Key-value parameters captured from the matched input
     * @return ToolResult with output, commands, and success status
     */
    suspend fun execute(params: Map<String, String> = emptyMap()): ToolResult

    /**
     * Check if this tool is available in the current runtime environment.
     * A plugin tool might return false if Termux is not installed.
     */
    fun isAvailable(): Boolean = true

    /**
     * Optional action name for AiIntent backward compatibility.
     * Defaults to the tool name.
     */
    val actionName: String get() = name
}

/**
 * ShellToolDescriptor — wraps a CommandRegistry.CommandTemplate as a ToolDescriptor.
 *
 * These are the built-in shell command tools that match user input by keywords
 * and return lists of ShellCommand to execute.
 */
class ShellToolDescriptor(
    override val name: String,
    override val description: String,
    override val keywords: List<String>,
    val commands: List<ShellCommand>
) : ToolDescriptor {

    override val category: ToolCategory
        get() = when {
            name.startsWith("git_") -> ToolCategory.GIT
            name.startsWith("pkg_") || name.startsWith("pip_") ||
                name.startsWith("npm_") || name.startsWith("node_") -> ToolCategory.PACKAGE
            name in listOf("ping", "curl", "dns_lookup", "netstat", "download_file") -> ToolCategory.NETWORK
            name in listOf("mkdir", "touch", "copy_file", "move_file", "remove_file",
                "cat_file", "search_files", "grep_text", "chmod", "zip_files", "unzip") -> ToolCategory.FILE
            name in listOf("press_home", "press_back", "volume_up", "volume_down",
                "set_brightness", "screenshot") -> ToolCategory.NAVIGATION
            name in listOf("battery_info", "storage_info", "ram_info", "device_info",
                "uptime", "device_temp", "wifi_info", "whoami", "cpu_info",
                "list_processes") -> ToolCategory.SYSTEM
            else -> ToolCategory.SHELL
        }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Substitute any {placeholder} in commands with actual parameter values
        val resolvedCommands = if (params.isEmpty()) {
            commands
        } else {
            commands.map { cmd ->
                var resolved = cmd.command
                for ((key, value) in params) {
                    resolved = resolved.replace("{$key}", value)
                }
                cmd.copy(command = resolved)
            }
        }
        return ToolResult(success = true, commands = resolvedCommands)
    }
}

/**
 * PluginToolDescriptor — wraps a plugin command from [IPlugin] as a ToolDescriptor.
 *
 * Automatically discovers and adapts:
 * - Termux plugin commands ("termux:exec", "termux:install", etc.)
 * - Git plugin commands ("git:status", "git:push", etc.)
 * - Network plugin commands ("net:ping", "net:curl", etc.)
 * - Package manager commands ("pkg:install", "pkg:update", etc.)
 */
class PluginToolDescriptor(
    private val plugin: IPlugin,
    val commandName: String
) : ToolDescriptor {

    override val name: String = commandName
    override val description: String = plugin.description
    override val keywords: List<String>
        get() {
            // Derive keywords from the command name
            val base = commandName.substringAfter(":")
            return listOf(
                base.replace("_", " "),
                base,
                plugin.id + " " + base,
                base.replace("_", "")
            )
        }

    override val category: ToolCategory
        get() = when (plugin.id) {
            "termux" -> ToolCategory.SHELL
            "git" -> ToolCategory.GIT
            "network" -> ToolCategory.NETWORK
            "pkgmgr" -> ToolCategory.PACKAGE
            else -> ToolCategory.PLUGIN
        }

    override fun isAvailable(): Boolean = true

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val result = plugin.execute(commandName, params)
        return ToolResult(
            success = result.success,
            output = result.output,
            error = result.error
        )
    }
}
