package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.TermuxBridge

/**
 * TermuxPlugin — wraps TermuxBridge into the IPlugin system.
 * Provides 15+ commands for shell execution, package management,
 * Python, clipboard, sensors, and device control.
 */
class TermuxPlugin(context: Context) : IPlugin {

    companion object {
        private const val TAG = "TermuxPlugin"
        private const val CMD_PREFIX = "termux:"
    }

    private val bridge = TermuxBridge(context)
    private var initialized = false

    override val id: String = "termux"
    override val name: String = "Termux Bridge"
    override val description: String = "Execute shell commands, manage packages, run Python, control device via Termux"
    override val version: String = "2.0.0"
    override val author: String = "INTERNDRA"

    override suspend fun initialize(context: Context): Boolean {
        initialized = bridge.isTermuxInstalled()
        if (!initialized) {
            Log.w(TAG, "Termux not installed — plugin will report errors gracefully")
        }
        return true // plugin itself is always loadable; commands fail gracefully
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "${CMD_PREFIX}exec",
        "${CMD_PREFIX}install",
        "${CMD_PREFIX}update",
        "${CMD_PREFIX}search",
        "${CMD_PREFIX}list_installed",
        "${CMD_PREFIX}uninstall",
        "${CMD_PREFIX}python",
        "${CMD_PREFIX}pip_install",
        "${CMD_PREFIX}pip_list",
        "${CMD_PREFIX}git",
        "${CMD_PREFIX}npm",
        "${CMD_PREFIX}clipboard_get",
        "${CMD_PREFIX}clipboard_set",
        "${CMD_PREFIX}toast",
        "${CMD_PREFIX}vibrate",
        "${CMD_PREFIX}torch_on",
        "${CMD_PREFIX}torch_off",
        "${CMD_PREFIX}sensor",
        "${CMD_PREFIX}tts"
    )

    override suspend fun execute(command: String, args: Map<String, String>): PluginResult {
        if (!initialized) {
            return PluginResult(
                success = false,
                output = "",
                error = "Termux is not installed. Install from F-Droid: " +
                        "https://f-droid.org/packages/com.termux/"
            )
        }

        return try {
            when (command.removePrefix(CMD_PREFIX)) {
                "exec" -> exec(args)
                "install" -> installPkg(args)
                "update" -> updatePkgs()
                "search" -> searchPkg(args)
                "list_installed" -> listInstalled()
                "uninstall" -> uninstallPkg(args)
                "python" -> runPython(args)
                "pip_install" -> pipInstall(args)
                "pip_list" -> pipList()
                "git" -> runGit(args)
                "npm" -> runNpm(args)
                "clipboard_get" -> clipboardGet()
                "clipboard_set" -> clipboardSet(args)
                "toast" -> toast(args)
                "vibrate" -> vibrate(args)
                "torch_on" -> torch(true)
                "torch_off" -> torch(false)
                "sensor" -> sensor(args)
                "tts" -> tts(args)
                else -> PluginResult(false, "", error = "Unknown termux command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${command}: ${e.message}")
            PluginResult(false, "", error = e.message ?: "Termux command failed")
        }
    }

    private suspend fun exec(args: Map<String, String>): PluginResult {
        val cmd = args["cmd"] ?: return PluginResult(false, "", error = "Missing 'cmd' argument")
        val workdir = args["workdir"]
        val timeout = (args["timeout"]?.toLongOrNull() ?: 60_000L)
        val result = bridge.executeShell(cmd, workdir = workdir, timeoutMs = timeout)
        return PluginResult(
            success = result.isSuccess,
            output = if (result.stdout.isNotBlank()) result.stdout else "(done)",
            error = if (!result.isSuccess) result.stderr else ""
        )
    }

    private suspend fun installPkg(args: Map<String, String>): PluginResult {
        val pkg = args["package"] ?: return PluginResult(false, "", error = "Missing 'package' argument")
        val result = bridge.installPackage(pkg)
        return PluginResult(
            success = result.isSuccess,
            output = result.stdout,
            error = result.stderr
        )
    }

    private suspend fun updatePkgs(): PluginResult {
        val result = bridge.updatePackages()
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun searchPkg(args: Map<String, String>): PluginResult {
        val query = args["query"] ?: return PluginResult(false, "", error = "Missing 'query' argument")
        val result = bridge.executeShell("pkg search $query 2>&1 | head -30")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun listInstalled(): PluginResult {
        val result = bridge.executeShell("pkg list-installed 2>&1 | head -50")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun uninstallPkg(args: Map<String, String>): PluginResult {
        val pkg = args["package"] ?: return PluginResult(false, "", error = "Missing 'package' argument")
        val result = bridge.executeShell("pkg uninstall $pkg 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun runPython(args: Map<String, String>): PluginResult {
        val code = args["code"] ?: return PluginResult(false, "", error = "Missing 'code' argument")
        val result = bridge.runPython(code)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun pipInstall(args: Map<String, String>): PluginResult {
        val pkg = args["package"] ?: return PluginResult(false, "", error = "Missing 'package' argument")
        val result = bridge.pipInstall(listOf(pkg))
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun pipList(): PluginResult {
        val result = bridge.executeShell("pip list 2>&1 || pip3 list 2>&1 | head -40")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun runGit(args: Map<String, String>): PluginResult {
        val gitArgs = args["args"] ?: return PluginResult(false, "", error = "Missing 'args' argument")
        val workdir = args["workdir"]
        val parts = gitArgs.split(" ")
        val result = bridge.git(*parts.toTypedArray(), workdir = workdir)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun runNpm(args: Map<String, String>): PluginResult {
        val npmArgs = args["args"] ?: return PluginResult(false, "", error = "Missing 'args' argument")
        val workdir = args["workdir"]
        val parts = npmArgs.split(" ")
        val result = bridge.npm(*parts.toTypedArray(), workdir = workdir)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun clipboardGet(): PluginResult {
        val result = bridge.executeShell("termux-clipboard-get 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun clipboardSet(args: Map<String, String>): PluginResult {
        val text = args["text"] ?: return PluginResult(false, "", error = "Missing 'text' argument")
        val escaped = text.replace("'", "'\\''")
        val result = bridge.executeShell("termux-clipboard-set '$escaped' 2>&1")
        return PluginResult(success = result.isSuccess, output = "Clipboard set", error = result.stderr)
    }

    private suspend fun toast(args: Map<String, String>): PluginResult {
        val msg = args["message"] ?: return PluginResult(false, "", error = "Missing 'message' argument")
        val escaped = msg.replace("'", "'\\''")
        val result = bridge.executeShell("termux-toast -b '#00E5FF' '$escaped' 2>&1")
        return PluginResult(success = result.isSuccess, output = "Toast shown", error = result.stderr)
    }

    private suspend fun vibrate(args: Map<String, String>): PluginResult {
        val duration = args["duration"] ?: "500"
        val result = bridge.executeShell("termux-vibrate -d $duration 2>&1")
        return PluginResult(success = result.isSuccess, output = "Vibrated for ${duration}ms", error = result.stderr)
    }

    private suspend fun torch(on: Boolean): PluginResult {
        val result = bridge.executeShell("termux-torch ${if (on) "on" else "off"} 2>&1")
        return PluginResult(success = result.isSuccess,
            output = if (on) "Torch turned on" else "Torch turned off",
            error = result.stderr)
    }

    private suspend fun sensor(args: Map<String, String>): PluginResult {
        val sensor = args["sensor"] ?: "all"
        val result = bridge.executeShell("termux-sensor -s '$sensor' -n 1 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun tts(args: Map<String, String>): PluginResult {
        val text = args["text"] ?: return PluginResult(false, "", error = "Missing 'text' argument")
        val escaped = text.replace("'", "'\\''")
        val result = bridge.executeShell("termux-tts-speak '$escaped' 2>&1")
        return PluginResult(success = result.isSuccess, output = "TTS spoken", error = result.stderr)
    }

    override fun teardown() {
        bridge.unregisterReceiver()
    }
}
