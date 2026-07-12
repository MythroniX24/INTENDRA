package com.interndra.plugin

import android.content.Context
import com.interndra.service.ShellExecutor

class PackageManagerPlugin(context: Context) : IPlugin {
    companion object { private const val CMD_PREFIX = "pkg:" }
    override val id = "pkgmgr"; override val name = "Package Manager"
    override val description = "Package management via built-in shell"
    override val version = "2.1.0"; override val author = "INTERNDRA"

    override suspend fun initialize(context: Context) = true
    override fun getSupportedCommands() = listOf("${CMD_PREFIX}install","${CMD_PREFIX}update","${CMD_PREFIX}upgrade","${CMD_PREFIX}list","${CMD_PREFIX}search","${CMD_PREFIX}uninstall","${CMD_PREFIX}info","${CMD_PREFIX}detect")

    override suspend fun execute(command: String, args: Map<String, String>) = try {
        when (command.removePrefix(CMD_PREFIX)) {
            "install" -> install(args); "update" -> update(); "upgrade" -> upgrade(args)
            "list" -> list(args); "search" -> search(args); "uninstall" -> uninstall(args)
            "info" -> info(args); "detect" -> detect()
            else -> PluginResult(false, "", error = "Unknown: $command")
        }
    } catch (e: Exception) { PluginResult(false, "", error = "Error: ${e.message}") }

    private fun result(r: com.interndra.service.ShellExecutionResult) = PluginResult(r.isSuccess, r.stdout, error = r.stderr)
    private suspend fun shell(cmd: String, timeoutMs: Long = 60_000L) = result(ShellExecutor.runAsync(cmd, timeoutMs))

    private suspend fun detect(): PluginResult {
        val checks = listOf("pkg" to "pkg --version 2>&1","pip" to "pip --version 2>&1","pip3" to "pip3 --version 2>&1","npm" to "npm --version 2>&1","python3" to "python3 --version 2>&1","git" to "git --version 2>&1")
        val sb = StringBuilder("📦 Available Package Managers\n${"-".repeat(30)}\n")
        for ((name, cmd) in checks) {
            val r = shell("which $name 2>/dev/null | head -1")
            if (r.success && r.output.isNotBlank()) {
                val ver = shell("$name --version 2>&1 | head -1")
                sb.appendLine("✅ $name  ${if (ver.success) ver.output.trim() else "(unknown)"}")
            } else sb.appendLine("❌ $name  (not installed)")
        }
        return PluginResult(true, sb.toString().trimEnd())
    }
    private suspend fun install(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        return shell(when (args["type"] ?: "pkg") { "pip" -> "pip install $name 2>&1 || pip3 install $name 2>&1"; "npm" -> "npm install $name 2>&1"; else -> "pkg install -y $name 2>&1" }, 120_000L)
    }
    private suspend fun update() = shell("pkg update -y 2>&1", 120_000L)
    private suspend fun upgrade(args: Map<String, String>) = shell(if (args["name"] == "all") "pkg upgrade -y 2>&1" else "pkg install -y ${args["name"] ?: "all"} 2>&1", 180_000L)
    private suspend fun list(args: Map<String, String>): PluginResult {
        val sb = StringBuilder()
        if ((args["type"] ?: "all") in listOf("all","pkg")) { val r = shell("pkg list-installed 2>&1 | head -40"); if (r.success) sb.appendLine("📦 pkg:\n${r.output.take(500)}\n") }
        if ((args["type"] ?: "all") in listOf("all","pip")) { val r = shell("pip list 2>&1 | head -30 || pip3 list 2>&1 | head -30"); if (r.success) sb.appendLine("🐍 pip:\n${r.output.take(500)}\n") }
        return PluginResult(true, sb.toString().ifEmpty { "No packages found" })
    }
    private suspend fun search(args: Map<String, String>): PluginResult {
        val query = args["query"] ?: return PluginResult(false, "", error = "Missing 'query'")
        return shell("pkg search $query 2>&1 | head -30")
    }
    private suspend fun uninstall(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        return shell(when (args["type"] ?: "pkg") { "pip" -> "pip uninstall -y $name 2>&1 || pip3 uninstall -y $name 2>&1"; "npm" -> "npm uninstall $name 2>&1"; else -> "pkg uninstall $name 2>&1" })
    }
    private suspend fun info(args: Map<String, String>): PluginResult {
        val name = args["name"] ?: return PluginResult(false, "", error = "Missing 'name'")
        val r = shell("pkg show $name 2>&1 | head -30")
        return if (r.success && !r.error.contains("not found")) r else shell("pip show $name 2>&1 || pip3 show $name 2>&1")
    }
    override fun teardown() {}
}
