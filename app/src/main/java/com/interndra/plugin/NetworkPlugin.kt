package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.ShellExecutor

class NetworkPlugin(context: Context) : IPlugin {
    companion object { private const val TAG = "NetworkPlugin"; private const val CMD_PREFIX = "net:" }
    override val id = "network"; override val name = "Network Toolkit"
    override val description = "Network diagnostics, HTTP, DNS via built-in shell"
    override val version = "2.1.0"; override val author = "INTERNDRA"

    override suspend fun initialize(context: Context) = true
    override fun getSupportedCommands() = listOf("${CMD_PREFIX}ping","${CMD_PREFIX}curl","${CMD_PREFIX}dns","${CMD_PREFIX}wifi","${CMD_PREFIX}connectivity","${CMD_PREFIX}traceroute","${CMD_PREFIX}port","${CMD_PREFIX}public_ip","${CMD_PREFIX}http_header")

    override suspend fun execute(command: String, args: Map<String, String>) = try {
        when (command.removePrefix(CMD_PREFIX)) {
            "ping" -> ping(args); "curl" -> curl(args); "dns" -> dns(args)
            "wifi" -> wifi(); "connectivity" -> connectivity(); "traceroute" -> traceroute(args)
            "port" -> portCheck(args); "public_ip" -> publicIp(); "http_header" -> httpHeader(args)
            else -> PluginResult(false, "", error = "Unknown: $command")
        }
    } catch (e: Exception) { PluginResult(false, "", error = "Network error: ${e.message}") }

    private fun result(r: com.interndra.service.ShellExecutionResult) = PluginResult(r.isSuccess, r.stdout, error = r.stderr)
    private suspend fun shell(cmd: String, timeoutMs: Long = 30_000L) = result(ShellExecutor.runAsync(cmd, timeoutMs))

    private suspend fun ping(args: Map<String, String>) = shell("ping -c ${args["count"] ?: "4"} -W 5 ${args["target"] ?: "8.8.8.8"} 2>&1 | tail -15")
    private suspend fun curl(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url'")
        val method = if (args["method"] != null && args["method"] != "GET") "-X ${args["method"]}" else ""
        val data = args["data"]?.let { "-d '$it'" } ?: ""
        return shell("curl -s -m ${args["timeout"] ?: "10"} $method $data -H 'User-Agent: INTERNDRA/2.0' '$url' 2>&1 | head -100")
    }
    private suspend fun dns(args: Map<String, String>): PluginResult {
        val r = shell("nslookup -type=${args["type"] ?: "A"} ${args["domain"] ?: "google.com"} 2>&1 | head -25")
        return if (r.success && r.output.isNotBlank()) r else shell("dig ${args["domain"] ?: "google.com"} ${args["type"] ?: "A"} 2>&1 | head -25")
    }
    private suspend fun wifi(): PluginResult {
        val r = shell("termux-wifi-connectioninfo 2>&1")
        return if (r.success && r.output.isNotBlank()) r else shell("dumpsys wifi 2>/dev/null | grep -E 'SSID|state|RSSI|ipAddress' | head -10")
    }
    private suspend fun connectivity(): PluginResult {
        val sb = StringBuilder("🔌 Connectivity Check\n${"-".repeat(25)}\n")
        sb.appendLine(if (shell("ping -c 1 -W 3 8.8.8.8 2>&1 | head -3").success) "✅ Internet: Connected" else "❌ Internet: Unreachable")
        sb.appendLine(if (shell("nslookup google.com 2>&1 | head -3").success) "✅ DNS: Resolving" else "❌ DNS: Not resolving")
        return PluginResult(true, sb.toString().trimEnd())
    }
    private suspend fun traceroute(args: Map<String, String>) = shell("traceroute -m 15 -w 2 ${args["target"] ?: "google.com"} 2>&1 | head -20")
    private suspend fun portCheck(args: Map<String, String>): PluginResult {
        val port = args["port"] ?: return PluginResult(false, "", error = "Missing 'port'")
        val target = args["target"] ?: "localhost"
        return shell("timeout 3 bash -c 'echo >/dev/tcp/$target/$port' 2>&1 && echo '✅ Port $port OPEN' || echo '❌ Port $port CLOSED'")
    }
    private suspend fun publicIp() = shell("curl -s -m 5 https://api.ipify.org 2>&1 || curl -s -m 5 https://icanhazip.com 2>&1 || echo 'Unavailable'")
    private suspend fun httpHeader(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url'")
        return shell("curl -sI -m 10 '$url' 2>&1 | head -30")
    }
    override fun teardown() {}
}
