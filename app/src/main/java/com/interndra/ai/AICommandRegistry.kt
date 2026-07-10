package com.interndra.ai

/**
 * AICommandRegistry — command discovery and capability reporting for the AI.
 *
 * Inspired by openclaw's [InvokeCommandRegistry], this registry tells the AI
 * what commands are available, what each does, and whether they are available
 * in the current device state.
 *
 * The AI queries this to know:
 *  - What shell commands work (Shizuku / Termux / Sandboxed)
 *  - What Android intents can be launched
 *  - What automation triggers exist
 *  - File system access capabilities
 *
 * Usage:
 * ```kotlin
 * val registry = AICommandRegistry(context, shizukuShell, termuxBridge)
 * val capabilities = registry.getCapabilitiesReport()  // AI-readable string
 * ```
 */
object AICommandRegistry {

    /** Category of available commands. */
    enum class CommandCategory(val displayName: String, val description: String) {
        SHELL("Shell Commands", "Execute Linux shell commands (ls, cat, mkdir, etc.)"),
        PACKAGE_MANAGER("Package Manager", "Install/update packages via pkg, pip, npm"),
        FILE_OPS("File Operations", "Read, write, create, delete files"),
        SYSTEM_INFO("System Info", "Check device info, battery, network, processes"),
        ANDROID_INTENT("Android Intents", "Launch apps, send texts, open files, dial phone"),
        AUTOMATION("Automation", "Schedule triggers, set up notification reactions"),
        WORKFLOW("Workflows", "Multi-step automated sequences with conditions"),
        NETWORK("Network", "HTTP requests, ping, curl, wget")
    }

    /** Runtime capabilities that affect command availability. */
    data class RuntimeCapabilities(
        val hasShizuku: Boolean = false,
        val hasTermux: Boolean = false,
        val hasTermuxPermission: Boolean = false,
        val isShizukuAuthorized: Boolean = false,
        val shizukuPrivilegeLevel: String = "none",
        val hasAccessibilityService: Boolean = false,
        val environmentType: String = "sandboxed",
        val termuxHome: String = "/data/data/com.termux/files/home",
        val termuxUsrBin: String = "/data/data/com.termux/files/usr/bin"
    )

    /** A single registered command with metadata. */
    data class CommandEntry(
        val name: String,
        val category: CommandCategory,
        val description: String,
        val examples: List<String> = emptyList(),
        val requiresShizuku: Boolean = false,
        val requiresTermux: Boolean = false,
        val isDangerous: Boolean = false,
        val aliases: List<String> = emptyList()
    )

    /** All registered commands indexed by name. */
    private val commandsByName = mutableMapOf<String, CommandEntry>()

    /** Categories with their child commands. */
    private val commandsByCategory = mutableMapOf<CommandCategory, MutableList<CommandEntry>>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        // ── Shell Commands ────────────────────────────────────────────
        register(CommandEntry("ls", CommandCategory.SHELL, "List directory contents",
            examples = listOf("ls -la", "ls /storage/emulated/0/Download")))
        register(CommandEntry("cd", CommandCategory.SHELL, "Change working directory",
            examples = listOf("cd /storage/emulated/0", "cd ~/Download")))
        register(CommandEntry("cat", CommandCategory.SHELL, "Print file contents to terminal",
            examples = listOf("cat /storage/emulated/0/file.txt")))
        register(CommandEntry("pwd", CommandCategory.SHELL, "Print working directory"))
        register(CommandEntry("echo", CommandCategory.SHELL, "Print text to terminal",
            examples = listOf("echo Hello", "echo $HOME")))
        register(CommandEntry("mkdir", CommandCategory.FILE_OPS, "Create a directory",
            examples = listOf("mkdir myfolder", "mkdir -p a/b/c")))
        register(CommandEntry("rm", CommandCategory.FILE_OPS, "Remove files or directories (use with care)",
            examples = listOf("rm file.txt", "rm -rf folder"), isDangerous = true))
        register(CommandEntry("cp", CommandCategory.FILE_OPS, "Copy files or directories",
            examples = listOf("cp source.txt dest.txt", "cp -r folder1 folder2")))
        register(CommandEntry("mv", CommandCategory.FILE_OPS, "Move or rename files",
            examples = listOf("mv old.txt new.txt")))
        register(CommandEntry("chmod", CommandCategory.FILE_OPS, "Change file permissions",
            examples = listOf("chmod +x script.sh")))
        register(CommandEntry("grep", CommandCategory.SHELL, "Search text using patterns",
            examples = listOf("grep 'pattern' file.txt", "ls | grep '\.kt$'")))
        register(CommandEntry("find", CommandCategory.FILE_OPS, "Search for files",
            examples = listOf("find . -name '*.kt'", "find /sdcard -size +1M")))

        // ── Package Manager ───────────────────────────────────────────
        register(CommandEntry("pkg", CommandCategory.PACKAGE_MANAGER, "Termux package manager (install/remove packages)",
            examples = listOf("pkg install python", "pkg update", "pkg upgrade"),
            requiresTermux = true))
        register(CommandEntry("pip", CommandCategory.PACKAGE_MANAGER, "Python package installer",
            examples = listOf("pip install requests", "pip list"),
            requiresTermux = true))
        register(CommandEntry("npm", CommandCategory.PACKAGE_MANAGER, "Node.js package manager",
            examples = listOf("npm install express", "npm run start"),
            requiresTermux = true))

        // ── System Info ───────────────────────────────────────────────
        register(CommandEntry("df", CommandCategory.SYSTEM_INFO, "Show disk space usage",
            examples = listOf("df -h", "df /storage/emulated/0")))
        register(CommandEntry("du", CommandCategory.SYSTEM_INFO, "Show directory/file size",
            examples = listOf("du -sh folder", "du -h --max-depth=1")))
        register(CommandEntry("ps", CommandCategory.SYSTEM_INFO, "List running processes",
            examples = listOf("ps aux", "ps -ef")))
        register(CommandEntry("top", CommandCategory.SYSTEM_INFO, "Show running processes (real-time)",
            examples = listOf("top -n 1")))
        register(CommandEntry("uname", CommandCategory.SYSTEM_INFO, "Print system information",
            examples = listOf("uname -a")))
        register(CommandEntry("free", CommandCategory.SYSTEM_INFO, "Show memory usage",
            examples = listOf("free -h", "free -m")))
        register(CommandEntry("ping", CommandCategory.NETWORK, "Test network connectivity",
            examples = listOf("ping -c 4 google.com")))
        register(CommandEntry("curl", CommandCategory.NETWORK, "Make HTTP requests",
            examples = listOf("curl https://api.example.com", "curl -O https://example.com/file.zip")))
        register(CommandEntry("wget", CommandCategory.NETWORK, "Download files from the internet",
            examples = listOf("wget https://example.com/file.zip")))

        // ── Git ───────────────────────────────────────────────────────
        register(CommandEntry("git", CommandCategory.SHELL, "Version control (clone, commit, push, pull)",
            examples = listOf("git clone https://...", "git status", "git add .", "git commit -m 'msg'"),
            requiresTermux = true))

        // ── Python ────────────────────────────────────────────────────
        register(CommandEntry("python3", CommandCategory.SHELL, "Run Python scripts",
            examples = listOf("python3 script.py", "python3 -c 'print(\"hello\")'"),
            requiresTermux = true))

        // ── Android Intents ───────────────────────────────────────────
        register(CommandEntry("open", CommandCategory.ANDROID_INTENT, "Launch an Android app by package name",
            examples = listOf("open:com.whatsapp", "open:com.google.android.youtube"),
            aliases = listOf("open_app", "launch")))
        register(CommandEntry("sendtext", CommandCategory.ANDROID_INTENT, "Send text via an app (share sheet)",
            examples = listOf("sendtext:com.whatsapp:Hello!", "sendtext:com.whatsapp:911234567890:Hello there")))
        register(CommandEntry("openfile", CommandCategory.ANDROID_INTENT, "Open a file with the default app",
            examples = listOf("openfile:/storage/emulated/0/Download/report.pdf")))
        register(CommandEntry("sharefile", CommandCategory.ANDROID_INTENT, "Share a file via the system share sheet",
            examples = listOf("sharefile:/storage/emulated/0/Pictures/photo.jpg")))
        register(CommandEntry("dial", CommandCategory.ANDROID_INTENT, "Open dialer with a phone number (user confirms call)",
            examples = listOf("dial:+1234567890")))
        register(CommandEntry("call", CommandCategory.ANDROID_INTENT, "Directly call a phone number (needs CALL_PHONE permission)",
            examples = listOf("call:+1234567890")))
        register(CommandEntry("sms", CommandCategory.ANDROID_INTENT, "Open SMS app with pre-filled number and body",
            examples = listOf("sms:+1234567890:Hello there")))
    }

    /** Register a single command entry. */
    fun register(entry: CommandEntry) {
        commandsByName[entry.name] = entry
        commandsByCategory.getOrPut(entry.category) { mutableListOf() }.add(entry)
        entry.aliases.forEach { alias -> commandsByName[alias] = entry }
    }

    /** Find a command by name or alias. */
    fun find(name: String): CommandEntry? = commandsByName[name.lowercase().trim()]

    /** Get all commands in a category. */
    fun getByCategory(category: CommandCategory): List<CommandEntry> =
        commandsByCategory[category]?.toList() ?: emptyList()

    /** Get all registered commands. */
    fun getAllCommands(): List<CommandEntry> = commandsByName.values.distinct().toList()

    /**
     * Generate a human-readable capabilities report for the AI.
     * This tells the AI exactly what it can do in the current environment.
     */
    fun getCapabilitiesReport(caps: RuntimeCapabilities): String {
        val sb = StringBuilder()
        sb.appendLine("📋 **Terminal & Command Capabilities Report**")
        sb.appendLine()
        sb.appendLine("### Environment")
        sb.appendLine("- Execution level: **${caps.environmentType}**")
        if (caps.hasShizuku) sb.appendLine("- Shizuku: **${caps.shizukuPrivilegeLevel}** (${if (caps.isShizukuAuthorized) "✅ Authorized" else "❌ Not authorized"})")
        else sb.appendLine("- Shizuku: ❌ Not available")
        sb.appendLine("- Termux: ${if (caps.hasTermux) "✅ Installed" else "❌ Not installed"}")
        if (caps.hasTermux) sb.appendLine("  - Permission: ${if (caps.hasTermuxPermission) "✅ Granted" else "❌ Denied"}")
        sb.appendLine("- Accessibility Service: ${if (caps.hasAccessibilityService) "✅ Enabled" else "❌ Not enabled"}")
        sb.appendLine()

        sb.appendLine("### Available Command Categories")
        for (category in CommandCategory.values()) {
            val cmds = getByCategory(category)
            if (cmds.isEmpty()) continue

            // Check if category is available
            val available = when (category) {
                CommandCategory.PACKAGE_MANAGER -> caps.hasTermux
                CommandCategory.SHELL -> true
                CommandCategory.FILE_OPS -> true
                CommandCategory.SYSTEM_INFO -> true
                CommandCategory.ANDROID_INTENT -> true
                CommandCategory.AUTOMATION -> true
                CommandCategory.WORKFLOW -> true
                CommandCategory.NETWORK -> true
            }
            if (!available) continue

            sb.appendLine("**${category.displayName}:** ${category.description}")
            for (cmd in cmds) {
                val flags = buildList {
                    if (cmd.isDangerous) add("⚠️")
                    if (cmd.requiresShizuku && !caps.isShizukuAuthorized) add("🔒 (needs Shizuku)")
                    if (cmd.requiresTermux && !caps.hasTermux) add("📦 (needs Termux)")
                }
                val flagStr = if (flags.isNotEmpty()) " — ${flags.joinToString(", ")}" else ""
                sb.appendLine("  - `${cmd.name}` — ${cmd.description}$flagStr")
                if (cmd.examples.isNotEmpty()) {
                    sb.appendLine("    Examples: ${cmd.examples.joinToString(", ") { "`$it`" }}")
                }
            }
            sb.appendLine()
        }

        sb.appendLine("### Usage Guidelines")
        sb.appendLine("1. **Always check environment first**: Run `pwd`, `ls`, `echo \$PATH` to verify your context")
        sb.appendLine("2. **Use Shizuku for system-level commands** (if available)")
        sb.appendLine("3. **Use Termux for package management, git, python** (if installed)")
        sb.appendLine("4. **For Android intents**: Use `open:package.name` to launch apps")
        sb.appendLine("5. **For automations**: Schedule with triggers or delay")
        sb.appendLine("6. **Never use dangerous commands** (`rm -rf /`, `dd`, `format`)")

        return sb.toString()
    }

    /**
     * Get a quick summary of what the AI can do (short version for prompts).
     */
    fun getQuickSummary(caps: RuntimeCapabilities): String = buildString {
        appendLine("## Available Commands")
        appendLine("You can use these commands in the terminal:")
        appendLine()
        appendLine("**Shell**: `ls`, `cd`, `cat`, `echo`, `pwd`, `grep`, `find`, `mkdir`, `rm`, `cp`, `mv`")
        if (caps.hasTermux) {
            appendLine("**Package**: `pkg install`, `pip install`, `npm install`")
            appendLine("**Dev**: `python3`, `git`, `node`")
        }
        appendLine("**Android**: `open:package`, `sendtext:pkg:msg`, `dial:+phone`, `sms:+phone:msg`")
        appendLine("**File**: `openfile:/path`, `sharefile:/path`")
        appendLine()
        if (caps.isShizukuAuthorized) {
            appendLine("🔑 **Shizuku ${caps.shizukuPrivilegeLevel}** available for system commands")
        }
        appendLine("Run `ls` first to see the current directory, then navigate with `cd`.")
    }
}
