package com.interndra.ai

import com.interndra.ai.tools.ToolRegistry
import com.interndra.ai.tools.ShellToolDescriptor
import com.interndra.data.model.AiIntent
import com.interndra.data.model.CommandType
import com.interndra.data.model.ShellCommand

/**
 * CommandRegistry — a lookup table of common one-liner commands.
 * Used as a local fallback when the AI returns an unknown/empty action.
 *
 * Now delegates its template list to an OpenClaw-inspired [ToolRegistry]
 * via [toToolRegistry], so the rest of the app can use a unified tool
 * discovery surface that also includes plugins and AI agents.
 */
object CommandRegistry {

    data class CommandTemplate(
        val keywords: List<String>,
        val action: String,
        val commands: List<ShellCommand>
    )

    private val templates = listOf(
        // ── System / Device ────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("battery","charge","power level"),action="battery_info",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-battery-status 2>/dev/null || dumpsys battery | grep -E 'level|status|health|temperature'","Battery status"))),
        CommandTemplate(keywords = listOf("screenshot","capture screen"),action="screenshot",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"screencap -p /sdcard/INTERNDRA/screenshots/shot_${System.currentTimeMillis()}.png","Take screenshot"))),
        CommandTemplate(keywords = listOf("storage","disk space","free space","storage info"),action="storage_info",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"df -h /sdcard && df -h /data","Storage usage"))),
        CommandTemplate(keywords = listOf("list files","ls","show files","what's in"),action="list_files",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"ls -la /sdcard/Download/","List files"))),
        CommandTemplate(keywords = listOf("ram","memory usage","free ram"),action="ram_info",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"cat /proc/meminfo | grep -E 'MemTotal|MemFree|MemAvailable'","RAM info"))),
        CommandTemplate(keywords = listOf("device info","system info","uname"),action="device_info",
            commands=listOf(ShellCommand(CommandType.TERMUX,"uname -a && echo '---' && cat /system/build.prop | grep -E 'ro.build.display.id|ro.product.model|ro.build.version.release' && echo '---' && getprop ro.product.manufacturer getprop ro.product.model","Device info"))),
        CommandTemplate(keywords = listOf("uptime","how long since boot"),action="uptime",
            commands=listOf(ShellCommand(CommandType.TERMUX,"uptime && echo '---' && cat /proc/uptime | awk '{print \"Uptime: \" \$1/86400 \" days\"}'","System uptime"))),
        CommandTemplate(keywords = listOf("temperature","cpu temp","device temp"),action="device_temp",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | tail -5 || echo 'Not available'","Device temperature"))),
        CommandTemplate(keywords = listOf("wifi","wi-fi","network info","ip address"),action="wifi_info",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-wifi-connectioninfo 2>/dev/null || (dumpsys wifi | grep -E 'SSID|state|RSSI|ipAddress' 2>/dev/null; echo '---'; ip addr show wlan0 2>/dev/null | grep inet)","WiFi info"))),
        CommandTemplate(keywords = listOf("whoami","current user"),action="whoami",
            commands=listOf(ShellCommand(CommandType.TERMUX,"whoami 2>/dev/null || echo 'u0_a$(id -u)'","Current user"))),
        CommandTemplate(keywords = listOf("set volume","volume up"),action="volume_up",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"input keyevent 24","Volume up"))),
        CommandTemplate(keywords = listOf("set volume","volume down"),action="volume_down",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"input keyevent 25","Volume down"))),
        CommandTemplate(keywords = listOf("home button","go home","press home"),action="press_home",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"input keyevent 3","Home button"))),
        CommandTemplate(keywords = listOf("back button","go back","press back"),action="press_back",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"input keyevent 4","Back button"))),
        CommandTemplate(keywords = listOf("brightness","screen brightness"),action="set_brightness",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"settings put system screen_brightness {level}","Set brightness"))),
        CommandTemplate(keywords = listOf("running apps","processes","running processes"),action="list_processes",
            commands=listOf(ShellCommand(CommandType.ADB_SHELL,"ps -A | head -30","Running processes"))),
        CommandTemplate(keywords = listOf("cpu info","processor"),action="cpu_info",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cat /proc/cpuinfo | grep -E 'processor|model name|Features' | head -20","CPU info"))),

        // ── Termux Package Management ──────────────────────────────────────
        CommandTemplate(keywords = listOf("install package","pkg install","install pkg"),action="pkg_install",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pkg install -y python 2>&1; echo 'Usage: pkg install -y <package>'","Install package via pkg"))),
        CommandTemplate(keywords = listOf("update packages","update all","pkg update","upgrade packages"),action="pkg_update",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pkg update -y 2>&1 && pkg upgrade -y 2>&1","Update all packages"))),
        CommandTemplate(keywords = listOf("search packages","pkg search","find package"),action="pkg_search",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pkg search python 2>&1 | head -20","Search packages"))),
        CommandTemplate(keywords = listOf("list installed packages","pkg list","installed packages"),action="pkg_list",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pkg list-installed 2>&1 | head -30","List installed packages"))),
        CommandTemplate(keywords = listOf("uninstall package","pkg uninstall","remove package"),action="pkg_uninstall",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pkg list-installed 2>&1 | head -10; echo 'Usage: pkg uninstall <package>'","Uninstall package"))),

        // ── Termux Python ──────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("python version","python3"),action="python_version",
            commands=listOf(ShellCommand(CommandType.TERMUX,"python3 --version 2>&1 || python --version 2>&1 || echo 'Python not installed'","Python version"))),
        CommandTemplate(keywords = listOf("pip list","python packages","pip freeze"),action="pip_list",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pip list 2>&1 || pip3 list 2>&1 | head -30","List Python packages"))),
        CommandTemplate(keywords = listOf("pip install","install python package"),action="pip_install",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pip install requests 2>&1 || pip3 install requests 2>&1; echo 'Usage: pip install <package>'","Install Python package"))),
        CommandTemplate(keywords = listOf("run python","python script","python3 -c"),action="run_python",
            commands=listOf(ShellCommand(CommandType.TERMUX,"python3 -c 'print(\"Hello from INTERNDRA AI!\")' 2>&1","Run Python code"))),

        // ── Git Operations ─────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("git status"),action="git_status",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git status 2>&1 || echo 'Not a git repository'","Git status"))),
        CommandTemplate(keywords = listOf("git push"),action="git_push",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git add -A && git commit --allow-empty -m 'update' && git push origin HEAD 2>&1","Git push"))),
        CommandTemplate(keywords = listOf("git pull"),action="git_pull",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git pull origin HEAD 2>&1","Git pull"))),
        CommandTemplate(keywords = listOf("git clone","clone repository"),action="git_clone",
            commands=listOf(ShellCommand(CommandType.TERMUX,"git clone https://github.com/user/repo.git 2>&1; echo 'Usage: git clone <url>'","Clone git repo"))),
        CommandTemplate(keywords = listOf("git commit"),action="git_commit",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git add -A && git commit -m 'update' 2>&1","Git commit"))),
        CommandTemplate(keywords = listOf("git log","git history"),action="git_log",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git log --oneline -20 2>&1","Git log"))),
        CommandTemplate(keywords = listOf("git branch","list branches"),action="git_branch",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git branch -a 2>&1","Git branches"))),
        CommandTemplate(keywords = listOf("git checkout","switch branch"),action="git_checkout",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git branch -a 2>&1; echo 'Usage: git checkout <branch>'","Git checkout"))),
        CommandTemplate(keywords = listOf("git diff","git changes"),action="git_diff",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git diff 2>&1","Git diff"))),
        CommandTemplate(keywords = listOf("git init","initialize repo"),action="git_init",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && mkdir -p myproject && cd myproject && git init && git add . && git commit -m 'initial' 2>&1","Git init"))),
        CommandTemplate(keywords = listOf("git stash"),action="git_stash",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && git stash 2>&1","Git stash"))),

        // ── npm / Node.js ──────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("npm install","install npm"),action="npm_install",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && npm init -y 2>&1 && npm install 2>&1 || npm install 2>&1","npm install"))),
        CommandTemplate(keywords = listOf("npm init","init node","create package.json"),action="npm_init",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && npm init -y 2>&1","npm init"))),
        CommandTemplate(keywords = listOf("npm run","npm start","npm build","npm test"),action="npm_run",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /data/data/com.termux/files/home && npm run start 2>&1 || echo 'No start script. Usage: npm run <script>'","npm run script"))),
        CommandTemplate(keywords = listOf("node version","nodejs"),action="node_version",
            commands=listOf(ShellCommand(CommandType.TERMUX,"node --version 2>&1 || nodejs --version 2>&1 || echo 'Node.js not installed'","Node.js version"))),

        // ── File Operations ────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("create directory","mkdir"),action="mkdir",
            commands=listOf(ShellCommand(CommandType.TERMUX,"mkdir -p /storage/emulated/0/INTERNDRA/newfolder 2>&1; echo 'Usage: mkdir -p <path>'","Create directory"))),
        CommandTemplate(keywords = listOf("create file","touch file"),action="touch",
            commands=listOf(ShellCommand(CommandType.TERMUX,"touch /storage/emulated/0/INTERNDRA/newfile.txt 2>&1; echo 'Usage: touch <file>'","Create file"))),
        CommandTemplate(keywords = listOf("copy file","cp","duplicate file"),action="copy_file",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cp -r /storage/emulated/0/INTERNDRA /storage/emulated/0/INTERNDRA_BACKUP 2>&1; echo 'Usage: cp -r <source> <dest>'","Copy file/dir"))),
        CommandTemplate(keywords = listOf("move file","mv","rename file"),action="move_file",
            commands=listOf(ShellCommand(CommandType.TERMUX,"mv /storage/emulated/0/INTERNDRA/oldname.txt /storage/emulated/0/INTERNDRA/newname.txt 2>&1; echo 'Usage: mv <source> <dest>'","Move/rename file"))),
        CommandTemplate(keywords = listOf("delete file","rm","remove file"),action="remove_file",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ls -la /storage/emulated/0/Download/; echo 'Usage: rm -rf <file_or_dir>'","Remove file/dir"))),
        CommandTemplate(keywords = listOf("cat file","show file","read file"),action="cat_file",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cat /storage/emulated/0/Download/sample.txt 2>&1 | head -100 || echo 'Usage: cat <filepath>'","Read file"))),
        CommandTemplate(keywords = listOf("search files","find file","grep"),action="search_files",
            commands=listOf(ShellCommand(CommandType.TERMUX,"find /storage/emulated/0 -type f -iname '*.pdf' 2>/dev/null | head -30","Search files"))),
        CommandTemplate(keywords = listOf("grep text","search text","find in files"),action="grep_text",
            commands=listOf(ShellCommand(CommandType.TERMUX,"grep -rn --include='*.kt' 'TODO' /data/data/com.termux/files/home 2>/dev/null | head -30 || echo 'No matches found'","Grep text"))),
        CommandTemplate(keywords = listOf("chmod","change permissions","make executable"),action="chmod",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ls -la /data/data/com.termux/files/home/; echo 'Usage: chmod +x <file>'","Make executable"))),
        CommandTemplate(keywords = listOf("zip","compress","archive"),action="zip_files",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /storage/emulated/0 && zip -r INTERNDRA_backup.zip INTERNDRA/ 2>&1; echo 'Usage: zip -r <archive>.zip <source>'","Create zip archive"))),
        CommandTemplate(keywords = listOf("unzip","extract zip","decompress"),action="unzip",
            commands=listOf(ShellCommand(CommandType.TERMUX,"cd /storage/emulated/0 && unzip -o INTERNDRA_backup.zip -d /storage/emulated/0/INTERNDRA_RESTORED 2>&1; echo 'Usage: unzip -o <file>.zip -d <dir>'","Extract zip archive"))),
        CommandTemplate(keywords = listOf("download file","wget","curl download"),action="download_file",
            commands=listOf(ShellCommand(CommandType.TERMUX,"curl -L -o /storage/emulated/0/Download/downloaded_file.txt 'https://example.com/file.txt' 2>&1 || wget -O /storage/emulated/0/Download/downloaded_file.txt 'https://example.com/file.txt' 2>&1","Download file"))),

        // ── Termux-Specific ────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("termux clipboard","clipboard get","copy from clipboard"),action="clipboard_get",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-clipboard-get 2>&1","Get clipboard content"))),
        CommandTemplate(keywords = listOf("termux clipboard set","clipboard set","copy to clipboard"),action="clipboard_set",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-clipboard-set 'Hello from INTERNDRA' 2>&1; echo 'Usage: termux-clipboard-set <text>'","Set clipboard content"))),
        CommandTemplate(keywords = listOf("termux toast","show notification","toast"),action="termux_toast",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-toast -b '#00E5FF' 'Hello from INTERNDRA' 2>&1","Show toast notification"))),
        CommandTemplate(keywords = listOf("termux vibrate","vibrate phone"),action="termux_vibrate",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-vibrate -d 500 2>&1; echo 'Usage: termux-vibrate -d <duration_ms>'","Vibrate device"))),
        CommandTemplate(keywords = listOf("termux torch","flashlight","turn on flashlight"),action="termux_torch_on",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-torch on 2>&1","Turn on flashlight"))),
        CommandTemplate(keywords = listOf("termux torch off","flashlight off"),action="termux_torch_off",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-torch off 2>&1","Turn off flashlight"))),
        CommandTemplate(keywords = listOf("termux sensor","sensor data","read sensor"),action="termux_sensor",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-sensor -s 'all' -n 1 2>&1","Read sensor data"))),
        CommandTemplate(keywords = listOf("termux tts","text to speech","speak"),action="termux_tts",
            commands=listOf(ShellCommand(CommandType.TERMUX,"termux-tts-speak 'Hello from INTERNDRA' 2>&1; echo 'Usage: termux-tts-speak <text>'","Text to speech"))),

        // ── Network ────────────────────────────────────────────────────────
        CommandTemplate(keywords = listOf("ping","check connectivity"),action="ping",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ping -c 4 -W 5 8.8.8.8 2>&1 | tail -10","Ping test"))),
        CommandTemplate(keywords = listOf("curl","http request","api call"),action="curl",
            commands=listOf(ShellCommand(CommandType.TERMUX,"curl -s -m 10 'https://api.github.com' 2>&1 | head -30","HTTP request via curl"))),
        CommandTemplate(keywords = listOf("dns lookup","nslookup","dig"),action="dns_lookup",
            commands=listOf(ShellCommand(CommandType.TERMUX,"nslookup google.com 2>&1 | head -20","DNS lookup"))),
        CommandTemplate(keywords = listOf("network stats","netstat","open ports"),action="netstat",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null || echo 'Not available'","Network connections"))),

        // ── Process Management ─────────────────────────────────────────────
        CommandTemplate(keywords = listOf("show processes","ps aux","top"),action="ps_list",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ps aux 2>/dev/null | head -30 || ps -A 2>/dev/null | head -30","List processes"))),
        CommandTemplate(keywords = listOf("kill process","kill pid","stop process"),action="kill",
            commands=listOf(ShellCommand(CommandType.TERMUX,"ps aux 2>/dev/null | head -10; echo 'Usage: kill <pid>'","Kill process"))),
        CommandTemplate(keywords = listOf("find process","pgrep","ps grep"),action="pgrep",
            commands=listOf(ShellCommand(CommandType.TERMUX,"pgrep -af 'python' 2>&1 | head -10 || echo 'Usage: pgrep -af <name>'","Find process")))
    )

    fun getAvailableCommands(): List<String> =
        templates.map { "${it.action}: ${it.keywords.first()}" }

    /**
     * Convert the legacy template list into an [ToolRegistry] that
     * ToolRegistry-aware consumers can use alongside plugin and AI tools.
     */
    fun toToolRegistry(): ToolRegistry {
        val registry = ToolRegistry()
        for (tmpl in templates) {
            registry.register(
                ShellToolDescriptor(
                    name = tmpl.action,
                    description = tmpl.commands.firstOrNull()?.description ?: tmpl.action,
                    keywords = tmpl.keywords,
                    commands = tmpl.commands
                )
            )
        }
        return registry
    }

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
     */
    fun findAllMatches(input: String): List<ShellCommand> {
        val lower = input.lowercase()
        return templates
            .filter { tmpl -> tmpl.keywords.any { kw -> lower.contains(kw) } }
            .flatMap { it.commands }
            .distinctBy { it.command }
    }
}
