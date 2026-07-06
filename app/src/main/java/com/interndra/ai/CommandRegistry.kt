package com.interndra.ai

import com.interndra.data.model.AiIntent
import com.interndra.data.model.CommandType
import com.interndra.data.model.ShellCommand

/**
 * CommandRegistry — a lookup table of common one-liner commands.
 * Used as a local fallback when the AI returns an unknown/empty action.
 *
 * FIX: original returned emptyList() and null everywhere — completely non-functional.
 */
object CommandRegistry {

    data class CommandTemplate(
        val keywords: List<String>,
        val action: String,
        val commands: List<ShellCommand>
    )

    private val templates = listOf(
        CommandTemplate(
            keywords = listOf("battery", "charge", "power level"),
            action   = "battery_info",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "dumpsys battery | grep -E 'level|status|health|temperature'",
                "Battery status"))
        ),
        CommandTemplate(
            keywords = listOf("screenshot", "capture screen"),
            action   = "screenshot",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "screencap -p /sdcard/INTERNDRA/screenshots/shot_${System.currentTimeMillis()}.png",
                "Take screenshot"))
        ),
        CommandTemplate(
            keywords = listOf("storage", "disk space", "free space", "storage info"),
            action   = "storage_info",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "df -h /sdcard && df -h /data",
                "Storage usage"))
        ),
        CommandTemplate(
            keywords = listOf("list files", "ls", "show files", "what's in"),
            action   = "list_files",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "ls -la /sdcard/Download/",
                "List files"))
        ),
        CommandTemplate(
            keywords = listOf("ram", "memory usage", "free ram"),
            action   = "ram_info",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "cat /proc/meminfo | grep -E 'MemTotal|MemFree|MemAvailable'",
                "RAM info"))
        ),
        CommandTemplate(
            keywords = listOf("git status"),
            action   = "git_status",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "cd /sdcard/INTERNDRA && git status",
                "Git status"))
        ),
        CommandTemplate(
            keywords = listOf("git push"),
            action   = "git_push",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "cd /sdcard/INTERNDRA && git add . && git commit -m 'update' && git push origin main",
                "Git push"))
        ),
        CommandTemplate(
            keywords = listOf("volume up", "increase volume"),
            action   = "volume_up",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL, "input keyevent 24", "Volume up"))
        ),
        CommandTemplate(
            keywords = listOf("volume down", "decrease volume"),
            action   = "volume_down",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL, "input keyevent 25", "Volume down"))
        ),
        CommandTemplate(
            keywords = listOf("home button", "go home", "press home"),
            action   = "press_home",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL, "input keyevent 3", "Home button"))
        ),
        CommandTemplate(
            keywords = listOf("back button", "go back", "press back"),
            action   = "press_back",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL, "input keyevent 4", "Back button"))
        ),
        CommandTemplate(
            keywords = listOf("brightness", "screen brightness"),
            action   = "set_brightness",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "settings put system screen_brightness {level}",
                "Set brightness"))
        ),
        CommandTemplate(
            keywords = listOf("wifi", "wi-fi info", "network info"),
            action   = "wifi_info",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "dumpsys wifi | grep -E 'mWifiInfo|SSID|BSSID|ipAddress'",
                "WiFi info"))
        ),
        CommandTemplate(
            keywords = listOf("running apps", "processes", "running processes"),
            action   = "list_processes",
            commands = listOf(ShellCommand(CommandType.ADB_SHELL,
                "ps -A | head -30",
                "Running processes"))
        )
    )

    fun getAvailableCommands(): List<String> =
        templates.map { "${it.action}: ${it.keywords.first()}" }

    fun findBestMatch(input: String): AiIntent? {
        val lower = input.lowercase()
        val match = templates.firstOrNull { tmpl ->
            tmpl.keywords.any { kw -> lower.contains(kw) }
        } ?: return null

        return AiIntent(
            action   = match.action,
            reply    = null,
            commands = match.commands
        )
    }

    /**
     * Multi-intent fallback — scans ALL templates and merges commands from
     * EVERY template whose keyword appears in the input.
     *
     * Fixes: "battery status and storage status batao" previously matched
     * ONLY battery_info (first match wins) and silently dropped storage.
     * Now both battery_info AND storage_info commands are returned together.
     */
    fun findAllMatches(input: String): List<ShellCommand> {
        val lower = input.lowercase()
        return templates
            .filter { tmpl -> tmpl.keywords.any { kw -> lower.contains(kw) } }
            .flatMap { it.commands }
            .distinctBy { it.command }
    }
}
