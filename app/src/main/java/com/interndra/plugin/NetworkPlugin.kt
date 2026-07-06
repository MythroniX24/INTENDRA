package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.TermuxBridge

/**
 * NetworkPlugin — network diagnostics and HTTP operations via Termux.
 *
 * Supports: ping, curl, dns lookup, WiFi info, connectivity check,
 * traceroute, port check, speed test hint.
 */
class NetworkPlugin(context: Context) : IPlugin {

    companion object {
        private const val TAG = "NetworkPlugin"
        private const val CMD_PREFIX = "net:"
    }

    private val bridge = TermuxBridge(context)
    private var initialized = false

    override val id: String = "network"
    override val name: String = "Network Toolkit"
    override val description: String = "Network diagnostics, HTTP requests, DNS, and connectivity checks"
    override val version: String = "2.0.0"
    override val author: String = "INTERNDRA"

    override suspend fun initialize(context: Context): Boolean {
        initialized = bridge.isTermuxInstalled()
        return true
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "${CMD_PREFIX}ping",
        "${CMD_PREFIX}curl",
        "${CMD_PREFIX}dns",
        "${CMD_PREFIX}wifi",
        "${CMD_PREFIX}connectivity",
        "${CMD_PREFIX}traceroute",
        "${CMD_PREFIX}port",
        "${CMD_PREFIX}public_ip",
        "${CMD_PREFIX}http_header"
    )

    override suspend fun execute(command: String, args: Map<String, String>): PluginResult {
        if (!initialized) {
            return PluginResult(false, "", error = "Termux not installed — network tools require Termux")
        }
        return try {
            when (command.removePrefix(CMD_PREFIX)) {
                "ping" -> ping(args)
                "curl" -> curl(args)
                "dns" -> dns(args)
                "wifi" -> wifi()
                "connectivity" -> connectivity()
                "traceroute" -> traceroute(args)
                "port" -> portCheck(args)
                "public_ip" -> publicIp()
                "http_header" -> httpHeader(args)
                else -> PluginResult(false, "", error = "Unknown net command: $command")
            }
        } catch (e: Exception) {
            PluginResult(false, "", error = "Network error: ${e.message}")
        }
    }

    private suspend fun ping(args: Map<String, String>): PluginResult {
        val target = args["target"] ?: "8.8.8.8"
        val count = args["count"] ?: "4"
        val result = bridge.executeShell("ping -c $count -W 5 $target 2>&1 | tail -15")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun curl(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url' argument")
        val method = args["method"] ?: "GET"
        val data = args["data"]
        val timeout = args["timeout"] ?: "10"

        val cmd = buildString {
            append("curl -s -m $timeout")
            if (method != "GET") {
                append(" -X $method")
                if (data != null) {
                    append(" -d '")
                    append(data.replace("'", "'\\''"))
                    append("'")
                }
            }
            append(" -H 'User-Agent: INTERNDRA/2.0'")
            append(" '")
            append(url.replace("'", "'\\''"))
            append("' 2>&1 | head -100")
        }
        val result = bridge.executeShell(cmd)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun dns(args: Map<String, String>): PluginResult {
        val domain = args["domain"] ?: "google.com"
        val type = args["type"] ?: "A"
        // Try nslookup first, fall back to dig
        val cmd = "nslookup -type=$type $domain 2>&1 | head -25"
        val result = bridge.executeShell(cmd)
        if (result.isSuccess && result.stdout.isNotBlank()) {
            return PluginResult(success = true, output = result.stdout)
        }
        val digResult = bridge.executeShell("dig $domain $type 2>&1 | head -25")
        return PluginResult(
            success = digResult.isSuccess,
            output = digResult.stdout,
            error = if (!digResult.isSuccess) digResult.stderr else ""
        )
    }

    private suspend fun wifi(): PluginResult {
        val result = bridge.executeShell("termux-wifi-connectioninfo 2>&1")
        if (result.isSuccess && result.stdout.isNotBlank()) {
            return PluginResult(success = true, output = result.stdout)
        }
        // Fallback to dumpsys
        val fallback = bridge.executeShell(
            "dumpsys wifi 2>/dev/null | grep -E 'SSID|state|RSSI|linkSpeed|frequency|ipAddress' | head -10"
        )
        return PluginResult(
            success = fallback.isSuccess,
            output = fallback.stdout.ifEmpty { "WiFi info not available" },
            error = fallback.stderr
        )
    }

    private suspend fun connectivity(): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("🔌 Connectivity Check")
        sb.appendLine("─".repeat(25))

        // Check internet by pinging
        val pingResult = bridge.executeShell("ping -c 1 -W 3 8.8.8.8 2>&1 | head -3")
        sb.appendLine(if (pingResult.isSuccess) "✅ Internet: Connected" else "❌ Internet: Unreachable")

        // Check DNS
        val dnsResult = bridge.executeShell("nslookup google.com 2>&1 | head -3")
        sb.appendLine(if (dnsResult.isSuccess) "✅ DNS: Resolving" else "❌ DNS: Not resolving")

        // Check WiFi
        val wifiResult = bridge.executeShell("termux-wifi-connectioninfo 2>&1 | grep '\"ssid\"' | head -1")
        if (wifiResult.isSuccess && wifiResult.stdout.isNotBlank()) {
            val ssid = wifiResult.stdout.replace("\"", "").replace("ssid:", "").trim()
            sb.appendLine("📶 WiFi: Connected to $ssid")
        } else {
            sb.appendLine("📶 WiFi: Not connected")
        }

        return PluginResult(success = true, output = sb.toString().trimEnd())
    }

    private suspend fun traceroute(args: Map<String, String>): PluginResult {
        val target = args["target"] ?: "google.com"
        val result = bridge.executeShell("traceroute -m 15 -w 2 $target 2>&1 | head -20")
        if (result.isSuccess && result.stdout.isNotBlank()) {
            return PluginResult(success = true, output = result.stdout)
        }
        // traceroute is often not installed; fall back to mtr or just explain
        return PluginResult(
            success = false,
            output = "",
            error = "traceroute not available. Install with: pkg install traceroute"
        )
    }

    private suspend fun portCheck(args: Map<String, String>): PluginResult {
        val target = args["target"] ?: "localhost"
        val port = args["port"] ?: return PluginResult(false, "", error = "Missing 'port' argument")
        val cmd = buildString {
            append("timeout 3 bash -c 'echo >/dev/tcp/$target/$port' 2>&1 && ")
            append("echo '✅ Port $port on $target is OPEN' || ")
            append("echo '❌ Port $port on $target is CLOSED or filtered'")
        }
        val result = bridge.executeShell(cmd)
        return PluginResult(success = true, output = result.stdout, error = result.stderr)
    }

    private suspend fun publicIp(): PluginResult {
        val result = bridge.executeShell(
            "curl -s -m 5 'https://api.ipify.org' 2>&1 || curl -s -m 5 'https://icanhazip.com' 2>&1 || echo 'Unavailable'"
        )
        val ip = result.stdout.trim()
        return PluginResult(
            success = ip.isNotBlank() && ip != "Unavailable",
            output = if (ip.isNotBlank() && ip != "Unavailable") "🌐 Public IP: $ip" else "Could not determine public IP",
            error = result.stderr
        )
    }

    private suspend fun httpHeader(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url' argument")
        val result = bridge.executeShell("curl -sI -m 10 '$url' 2>&1 | head -30")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    override fun teardown() {
        bridge.unregisterReceiver()
    }
}
