package com.interndra.plugin

import android.content.Context
import com.interndra.service.ShellExecutor

class TermuxPlugin(context: Context) : IPlugin {
    companion object { private const val CMD_PREFIX = "shell:" }
    override val id = "shell"; override val name = "Shell Bridge"
    override val description = "Execute shell commands via built-in terminal"
    override val version = "2.1.0"; override val author = "INTERNDRA"

    override suspend fun initialize(context: Context) = true
    override fun getSupportedCommands() = listOf("${CMD_PREFIX}exec","${CMD_PREFIX}python","${CMD_PREFIX}pip_install","${CMD_PREFIX}pip_list","${CMD_PREFIX}git","${CMD_PREFIX}npm","${CMD_PREFIX}clipboard_get","${CMD_PREFIX}clipboard_set","${CMD_PREFIX}toast","${CMD_PREFIX}vibrate","${CMD_PREFIX}sensor")

    override suspend fun execute(command: String, args: Map<String, String>) = try {
        when (command.removePrefix(CMD_PREFIX)) {
            "exec" -> execCmd(args); "python" -> runPython(args); "pip_install" -> pipInstall(args)
            "pip_list" -> pipList(); "git" -> runGit(args); "npm" -> runNpm(args)
            "clipboard_get" -> shell("termux-clipboard-get 2>&1")
            "clipboard_set" -> { val t = (args["text"] ?: "").replace("'","'\\''"); shell("termux-clipboard-set '$t' 2>&1") }
            "toast" -> { val m = (args["message"] ?: "").replace("'","'\\''"); shell("termux-toast '$m' 2>&1") }
            "vibrate" -> shell("termux-vibrate -d ${args["duration"] ?: "500"} 2>&1")
            "sensor" -> shell("termux-sensor -s '${args["sensor"] ?: "all"}' -n 1 2>&1")
            else -> PluginResult(false, "", error = "Unknown: $command")
        }
    } catch (e: Exception) { PluginResult(false, "", error = "Shell error: ${e.message}") }

    private fun result(r: com.interndra.service.ShellExecutionResult) = PluginResult(r.isSuccess, if (r.stdout.isNotBlank()) r.stdout else "(done)", error = if (!r.isSuccess) r.stderr else "")
    private suspend fun shell(cmd: String, timeoutMs: Long = 60_000L) = result(ShellExecutor.runAsync(cmd, timeoutMs))
    private suspend fun execCmd(args: Map<String, String>): PluginResult {
        val cmd = args["cmd"] ?: return PluginResult(false, "", error = "Missing 'cmd'")
        return shell(cmd, args["timeout"]?.toLongOrNull() ?: 60_000L)
    }
    private suspend fun runPython(args: Map<String, String>): PluginResult {
        val code = args["code"] ?: return PluginResult(false, "", error = "Missing 'code'")
        return shell("python3 -c '$code' 2>&1")
    }
    private suspend fun pipInstall(args: Map<String, String>): PluginResult {
        val pkg = args["package"] ?: return PluginResult(false, "", error = "Missing 'package'")
        return shell("pip install $pkg 2>&1 || pip3 install $pkg 2>&1", 120_000L)
    }
    private suspend fun pipList() = shell("pip list 2>&1 | head -40 || pip3 list 2>&1 | head -40")
    private suspend fun runGit(args: Map<String, String>): PluginResult {
        val a = args["args"] ?: return PluginResult(false, "", error = "Missing 'args'")
        return shell("git $a 2>&1")
    }
    private suspend fun runNpm(args: Map<String, String>): PluginResult {
        val a = args["args"] ?: return PluginResult(false, "", error = "Missing 'args'")
        return shell("npm $a 2>&1", 120_000L)
    }
    override fun teardown() {}
}
