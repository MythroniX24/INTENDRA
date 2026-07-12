package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.ShellExecutor

/**
 * ShellPlugin — wraps built-in ShellExecutor into the IPlugin system.
 */
class TermuxPlugin(context: Context) : IPlugin {

    companion object { private const val TAG = "TermuxPlugin"; private const val CMD_PREFIX = "shell:" }

    override val id = "shell"; override val name = "Shell Bridge"
    override val description = "Execute shell commands via built-in terminal"
    override val version = "2.1.0"; override val author = "INTERNDRA"

    override suspend fun initialize(context: Context) = true

    override fun getSupportedCommands() = listOf("${CMD_PREFIX}exec","${CMD_PREFIX}python","${CMD_PREFIX}pip_install","${CMD_PREFIX}pip_list","${CMD_PREFIX}git","${CMD_PREFIX}npm","${CMD_PREFIX}clipboard_get","${CMD_PREFIX}clipboard_set","${CMD_PREFIX}toast","${CMD_PREFIX}vibrate","${CMD_PREFIX}sensor")

    override suspend fun execute(command: String, args: Map<String, String>): PluginResult = try {
        when (command.removePrefix(CMD_PREFIX)) {
            "exec" -> exec(args); "python" -> runPython(args); "pip_install" -> pipInstall(args)
            "pip_list" -> pipList(); "git" -> runGit(args); "npm" -> runNpm(args)
            "clipboard_get" -> exec("termux-clipboard-get 2>&1")
            "clipboard_set" -> { val t = (args["text"] ?: "").replace("'","'\\''"); exec("termux-clipboard-set '$t' 2>&1") }
            "toast" -> { val m = (args["message"] ?: "").replace("'","'\\''"); exec("termux-toast '$m' 2>&1") }
            "vibrate" -> exec("termux-vibrate -d ${args["duration"] ?: "500"} 2>&1")
            "sensor" -> exec("termux-sensor -s '${args["sensor"] ?: "all"}' -n 1 2>&1")
            else -> PluginResult(false, "", error = "Unknown: $command")
        }
    } catch (e: Exception) { PluginResult(false, "", error = "Shell error: ${e.message}") }

    private suspend fun exec(cmd: String, timeoutMs: Long = 60_000L): PluginResult {
        val r = ShellExecutor.runAsync(cmd, timeoutMs)
        return PluginResult(r.isSuccess, if (r.stdout.isNotBlank()) r.stdout else "(done)", if (!r.isSuccess) r.stderr else "")
    }
    private suspend fun exec(args: Map<String, String>): PluginResult {
        val cmd = args["cmd"] ?: return PluginResult(false, "", error = "Missing 'cmd'")
        return exec(cmd, args["timeout"]?.toLongOrNull() ?: 60_000L)
    }
    private suspend fun runPython(args: Map<String, String>): PluginResult {
        val code = args["code"] ?: return PluginResult(false, "", error = "Missing 'code'")
        return exec("python3 -c '$code' 2>&1")
    }
    private suspend fun pipInstall(args: Map<String, String>): PluginResult {
        val pkg = args["package"] ?: return PluginResult(false, "", error = "Missing 'package'")
        return exec("pip install $pkg 2>&1 || pip3 install $pkg 2>&1", 120_000L)
    }
    private suspend fun pipList() = exec("pip list 2>&1 | head -40 || pip3 list 2>&1 | head -40")
    private suspend fun runGit(args: Map<String, String>): PluginResult {
        val a = args["args"] ?: return PluginResult(false, "", error = "Missing 'args'")
        return exec("git $a 2>&1")
    }
    private suspend fun runNpm(args: Map<String, String>): PluginResult {
        val a = args["args"] ?: return PluginResult(false, "", error = "Missing 'args'")
        return exec("npm $a 2>&1", 120_000L)
    }
    override fun teardown() {}
}
