package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.AiIntent
import com.interndra.data.model.CommandType
import org.junit.Test

/**
 * Comprehensive test suite for CommandRegistry.
 *
 * Tests:
 * - findBestMatch for all command categories (system, git, npm, packages, files, etc.)
 * - findAllMatches for multi-intent queries
 * - Edge cases (empty input, gibberish, Hindi mixed input)
 * - getAvailableCommands listing
 * - Keyword matching accuracy
 */
class CommandRegistryTest {

    // ── findBestMatch — System Commands ─────────────────────────────────

    @Test
    fun `findBestMatch battery query returns battery_info`() {
        val intent = CommandRegistry.findBestMatch("battery status")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("battery_info")
    }

    @Test
    fun `findBestMatch battery power level query returns battery_info`() {
        val intent = CommandRegistry.findBestMatch("power level batao")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("battery_info")
    }

    @Test
    fun `findBestMatch screenshot query returns screenshot`() {
        val intent = CommandRegistry.findBestMatch("take screenshot")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("screenshot")
    }

    @Test
    fun `findBestMatch storage query returns storage_info`() {
        val intent = CommandRegistry.findBestMatch("storage info dikhao")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("storage_info")
    }

    @Test
    fun `findBestMatch disk space query returns storage_info`() {
        val intent = CommandRegistry.findBestMatch("check disk space")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("storage_info")
    }

    @Test
    fun `findBestMatch list files query returns list_files`() {
        val intent = CommandRegistry.findBestMatch("list files in downloads")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("list_files")
    }

    @Test
    fun `findBestMatch ls query returns list_files`() {
        val intent = CommandRegistry.findBestMatch("ls")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("list_files")
    }

    @Test
    fun `findBestMatch ram query returns ram_info`() {
        val intent = CommandRegistry.findBestMatch("ram usage")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("ram_info")
    }

    @Test
    fun `findBestMatch device info query returns device_info`() {
        val intent = CommandRegistry.findBestMatch("device info")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("device_info")
    }

    @Test
    fun `findBestMatch uptime query returns uptime`() {
        val intent = CommandRegistry.findBestMatch("how long since boot")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("uptime")
    }

    @Test
    fun `findBestMatch temperature query returns device_temp`() {
        val intent = CommandRegistry.findBestMatch("cpu temperature")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("device_temp")
    }

    @Test
    fun `findBestMatch wifi query returns wifi_info`() {
        val intent = CommandRegistry.findBestMatch("wifi status")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("wifi_info")
    }

    @Test
    fun `findBestMatch home button query returns press_home`() {
        val intent = CommandRegistry.findBestMatch("go home")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("press_home")
    }

    @Test
    fun `findBestMatch back button query returns press_back`() {
        val intent = CommandRegistry.findBestMatch("go back")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("press_back")
    }

    // ── findBestMatch — Git Commands ───────────────────────────────────

    @Test
    fun `findBestMatch git status query returns git_status`() {
        val intent = CommandRegistry.findBestMatch("git status")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_status")
    }

    @Test
    fun `findBestMatch git push query returns git_push`() {
        val intent = CommandRegistry.findBestMatch("git push origin main")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_push")
    }

    @Test
    fun `findBestMatch git pull query returns git_pull`() {
        val intent = CommandRegistry.findBestMatch("git pull upstream")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_pull")
    }

    @Test
    fun `findBestMatch git log query returns git_log`() {
        val intent = CommandRegistry.findBestMatch("git history")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_log")
    }

    @Test
    fun `findBestMatch git branch query returns git_branch`() {
        val intent = CommandRegistry.findBestMatch("list branches")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_branch")
    }

    @Test
    fun `findBestMatch git diff query returns git_diff`() {
        val intent = CommandRegistry.findBestMatch("git changes")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("git_diff")
    }

    // ── findBestMatch — Package Management ─────────────────────────────

    @Test
    fun `findBestMatch install package query returns pkg_install`() {
        val intent = CommandRegistry.findBestMatch("install python package")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("pkg_install")
    }

    @Test
    fun `findBestMatch update packages query returns pkg_update`() {
        val intent = CommandRegistry.findBestMatch("update all packages")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("pkg_update")
    }

    @Test
    fun `findBestMatch pip list query returns pip_list`() {
        val intent = CommandRegistry.findBestMatch("list python packages")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("pip_list")
    }

    // ── findBestMatch — File Operations ────────────────────────────────

    @Test
    fun `findBestMatch create directory query returns mkdir`() {
        val intent = CommandRegistry.findBestMatch("create directory")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("mkdir")
    }

    @Test
    fun `findBestMatch create file query returns touch`() {
        val intent = CommandRegistry.findBestMatch("create file")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("touch")
    }

    @Test
    fun `findBestMatch copy file query returns copy_file`() {
        val intent = CommandRegistry.findBestMatch("copy file")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("copy_file")
    }

    @Test
    fun `findBestMatch download file query returns download_file`() {
        val intent = CommandRegistry.findBestMatch("download file from url")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("download_file")
    }

    // ── findBestMatch — Termux-Specific ────────────────────────────────

    @Test
    fun `findBestMatch clipboard get query returns clipboard_get`() {
        val intent = CommandRegistry.findBestMatch("get clipboard content")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("clipboard_get")
    }

    @Test
    fun `findBestMatch toast query returns termux_toast`() {
        val intent = CommandRegistry.findBestMatch("show notification")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("termux_toast")
    }

    @Test
    fun `findBestMatch torch on query returns termux_torch_on`() {
        val intent = CommandRegistry.findBestMatch("turn on flashlight")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("termux_torch_on")
    }

    @Test
    fun `findBestMatch tts query returns termux_tts`() {
        val intent = CommandRegistry.findBestMatch("text to speech")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("termux_tts")
    }

    // ── findAllMatches — Multi-Intent ──────────────────────────────────

    @Test
    fun `findAllMatches returns multiple results for compound queries`() {
        val commands = CommandRegistry.findAllMatches("battery status and storage info")
        assertThat(commands).isNotEmpty()
        // Should match both battery and storage
        assertThat(commands.any { it.description.contains("Battery") }).isTrue()
        assertThat(commands.any { it.description.contains("Storage") }).isTrue()
    }

    @Test
    fun `findAllMatches returns unique commands only`() {
        val commands = CommandRegistry.findAllMatches("battery and storage and wifi")
        // Should have no duplicate commands
        assertThat(commands).hasSize(commands.distinctBy { it.command }.size)
    }

    @Test
    fun `findAllMatches returns all matching commands`() {
        val commands = CommandRegistry.findAllMatches("battery uptime temperature")
        assertThat(commands).isNotEmpty()
        assertThat(commands.size).isAtLeast(3)
    }

    @Test
    fun `findAllMatches returns empty for gibberish`() {
        val commands = CommandRegistry.findAllMatches("asdfghjkl qwerty")
        assertThat(commands).isEmpty()
    }

    @Test
    fun `findAllMatches matches keywords across categories`() {
        val commands = CommandRegistry.findAllMatches("battery and git status")
        assertThat(commands).isNotEmpty()
        assertThat(commands.any { it.command.contains("battery") }).isTrue()
        assertThat(commands.any { it.command.contains("git") }).isTrue()
    }

    // ── Edge Cases ─────────────────────────────────────────────────────

    @Test
    fun `findBestMatch returns null for empty input`() {
        assertThat(CommandRegistry.findBestMatch("")).isNull()
    }

    @Test
    fun `findBestMatch returns null for gibberish`() {
        assertThat(CommandRegistry.findBestMatch("xylophone giraffe quantum")).isNull()
    }

    @Test
    fun `findBestMatch is case insensitive`() {
        val intent = CommandRegistry.findBestMatch("BATTERY STATUS")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("battery_info")
    }

    @Test
    fun `findBestMatch matches partial keywords`() {
        val intent = CommandRegistry.findBestMatch("show battery level")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("battery_info")
    }

    @Test
    fun `findBestMatch volume up returns correct action`() {
        val intent = CommandRegistry.findBestMatch("volume up")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("volume_up")
    }

    @Test
    fun `findBestMatch volume down returns correct action`() {
        val intent = CommandRegistry.findBestMatch("volume down")
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo("volume_down")
    }

    @Test
    fun `findBestMatch returns commands with correct types`() {
        val intent = CommandRegistry.findBestMatch("git status")
        assertThat(intent).isNotNull()
        assertThat(intent!!.commands).isNotEmpty()
        // Git commands should be TERMUX type
        assertThat(intent.commands.first().type).isEqualTo(CommandType.TERMUX)
    }

    // ── getAvailableCommands ───────────────────────────────────────────

    @Test
    fun `getAvailableCommands returns non-empty list`() {
        val commands = CommandRegistry.getAvailableCommands()
        assertThat(commands).isNotEmpty()
    }

    @Test
    fun `getAvailableCommands contains expected entries`() {
        val commands = CommandRegistry.getAvailableCommands()
        assertThat(commands).contains("battery_info: battery")
    }
}
