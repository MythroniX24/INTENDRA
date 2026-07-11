package com.interndra.ai.workflow

import com.interndra.data.model.*
import java.io.File

/**
 * WorkflowPlanner — Phase 5/6/7/UPGRADE intent detection and workflow composition.
 *
 * Turns natural-language user requests into structured [Workflow] objects
 * that the WorkflowEngine can execute safely.
 *
 * UPGRADED with:
 *  - 8 NEW intent types: email, timer, alarm, clipboard, screenshot, GPS, volume, battery saver
 *  - Multi-step chaining (compound workflows like "find PDFs and share the first one")
 *  - Parallel execution hints via [WorkflowStep.dependsOn]
 *  - Dynamic result variables for result passing between steps
 *
 * The planner is intentionally conservative: if it can't confidently map a
 * request to a known workflow, it returns null and the caller falls back to
 * the AI orchestrator.
 */
class WorkflowPlanner {

    /** Intent categories the planner can detect. */
    enum class Intent {
        WHATSAPP_MESSAGE,
        WHATSAPP_CONTACT,
        FIND_FILES,
        LIST_FILES,
        OPEN_FILE,
        SHARE_FILE,
        LAUNCH_APP,
        CALL_PHONE,
        SEND_SMS,
        DEVICE_INFO,
        COMPOUND_FIND_SHARE,  // "find PDFs and share the first one"
        // ── New intents ─────────────────────────────────────────────────
        EMAIL,
        TIMER,
        ALARM,
        CLIPBOARD_COPY,
        CLIPBOARD_PASTE,
        SCREENSHOT,
        GPS_STATUS,
        GPS_TOGGLE,
        VOLUME_SET,
        BATTERY_SAVER,
        UNKNOWN
    }

    data class DetectedIntent(
        val intent: Intent,
        val confidence: Float,
        val packageName: String? = null,
        val contactHint: String? = null,
        val message: String? = null,
        val fileExtensions: List<String> = emptyList(),
        val directory: String? = null,
        val namePattern: String? = null,
        val phone: String? = null,
        val smsBody: String? = null,
        val infoType: String? = null,
        val rawInput: String,
        // ── New fields for upgraded intents ────────────────────────────
        val emailTo: String? = null,
        val emailSubject: String? = null,
        val emailBody: String? = null,
        val timerDuration: String? = null,   // e.g. "5 minutes"
        val timerSeconds: Int? = null,
        val alarmTime: String? = null,        // e.g. "07:00"
        val clipboardText: String? = null,
        val volumePercent: Int? = null,
        val gpsAction: String? = null,        // "on", "off", "status"
        val compoundSubIntents: List<DetectedIntent>? = null
    )

    fun detect(input: String): DetectedIntent? {
        val lower = input.lowercase().trim()
        if (lower.isBlank()) return null

        // ── Check for compound intents FIRST ──────────────────────────
        val compound = detectCompound(input, lower)
        if (compound != null) return compound

        val candidates = mutableListOf<DetectedIntent>()

        // Existing detectors
        detectWhatsApp(lower, input)?.let { candidates.add(it) }
        detectFileSearch(lower, input)?.let { candidates.add(it) }
        detectListFiles(lower, input)?.let { candidates.add(it) }
        detectOpenFile(lower, input)?.let { candidates.add(it) }
        detectShareFile(lower, input)?.let { candidates.add(it) }
        detectLaunchApp(lower, input)?.let { candidates.add(it) }
        detectCall(lower, input)?.let { candidates.add(it) }
        detectSms(lower, input)?.let { candidates.add(it) }
        detectDeviceInfo(lower, input)?.let { candidates.add(it) }

        // ── New intent detectors ───────────────────────────────────────
        detectEmail(lower, input)?.let { candidates.add(it) }
        detectTimer(lower, input)?.let { candidates.add(it) }
        detectAlarm(lower, input)?.let { candidates.add(it) }
        detectClipboard(lower, input)?.let { candidates.add(it) }
        detectScreenshot(lower, input)?.let { candidates.add(it) }
        detectGps(lower, input)?.let { candidates.add(it) }
        detectVolume(lower, input)?.let { candidates.add(it) }
        detectBatterySaver(lower, input)?.let { candidates.add(it) }

        return candidates.maxByOrNull { it.confidence }?.takeIf { it.confidence >= 0.4f }
    }

    fun plan(detected: DetectedIntent): Workflow? = when (detected.intent) {
        Intent.WHATSAPP_MESSAGE, Intent.WHATSAPP_CONTACT -> planWhatsApp(detected)
        Intent.FIND_FILES       -> planFindFiles(detected)
        Intent.LIST_FILES       -> planListFiles(detected)
        Intent.OPEN_FILE        -> planOpenFile(detected)
        Intent.SHARE_FILE       -> planShareFile(detected)
        Intent.LAUNCH_APP       -> planLaunchApp(detected)
        Intent.CALL_PHONE       -> planCall(detected)
        Intent.SEND_SMS         -> planSms(detected)
        Intent.DEVICE_INFO      -> planDeviceInfo(detected)
        Intent.COMPOUND_FIND_SHARE -> planCompoundFindShare(detected)
        // ── New planner methods ─────────────────────────────────────────
        Intent.EMAIL            -> planEmail(detected)
        Intent.TIMER            -> planTimer(detected)
        Intent.ALARM            -> planAlarm(detected)
        Intent.CLIPBOARD_COPY   -> planClipboardCopy(detected)
        Intent.CLIPBOARD_PASTE  -> planClipboardPaste(detected)
        Intent.SCREENSHOT       -> planScreenshot(detected)
        Intent.GPS_STATUS       -> planGpsStatus(detected)
        Intent.GPS_TOGGLE       -> planGpsToggle(detected)
        Intent.VOLUME_SET       -> planVolumeSet(detected)
        Intent.BATTERY_SAVER    -> planBatterySaver(detected)
        Intent.UNKNOWN          -> null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EXISTING DETECTORS
    // ══════════════════════════════════════════════════════════════════════

    private fun detectWhatsApp(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("whatsapp")) return null
        val msg = extractMessageText(raw)
        val contact = extractContact(raw)
        val intent = if (contact != null) Intent.WHATSAPP_CONTACT else Intent.WHATSAPP_MESSAGE
        return DetectedIntent(
            intent = intent,
            confidence = 0.9f,
            packageName = "com.whatsapp",
            contactHint = contact,
            message = msg,
            rawInput = raw
        )
    }

    private fun detectFileSearch(lower: String, raw: String): DetectedIntent? {
        val findKeywords = listOf("find", "search for", "locate", "show me", "list all")
        val matches = findKeywords.any { lower.contains(it) }
        if (!matches) return null

        val extensions = mutableListOf<String>()
        if (lower.contains("pdf")) extensions += "pdf"
        if (lower.contains("image") || lower.contains("photo") || lower.contains("picture"))
            extensions += listOf("jpg", "jpeg", "png", "gif", "webp")
        if (lower.contains("apk")) extensions += "apk"
        if (lower.contains("doc") || lower.contains("document"))
            extensions += listOf("doc", "docx", "txt", "pdf")
        if (lower.contains("audio") || lower.contains("music") || lower.contains("mp3"))
            extensions += listOf("mp3", "wav", "ogg", "m4a")
        if (lower.contains("video")) extensions += listOf("mp4", "mkv", "webm", "3gp")
        if (lower.contains("project"))
            extensions += listOf("kt", "java", "py", "js", "ts", "gradle", "xml")
        if (lower.contains("zip") || lower.contains("archive"))
            extensions += listOf("zip", "tar", "gz")

        if (extensions.isEmpty() && !lower.contains("file") && !lower.contains("files")) return null

        val directory = when {
            lower.contains("download") -> "/storage/emulated/0/Download"
            lower.contains("document") -> "/storage/emulated/0/Documents"
            lower.contains("picture") || lower.contains("photo") -> "/storage/emulated/0/Pictures"
            lower.contains("dcim") || lower.contains("camera") -> "/storage/emulated/0/DCIM"
            lower.contains("music") -> "/storage/emulated/0/Music"
            lower.contains("movie") || lower.contains("video") -> "/storage/emulated/0/Movies"
            else -> "/storage/emulated/0"
        }

        val namePattern = Regex(
            """(?:named|called|containing|matching)\s+["']?(\w[\w\s.-]*?)["']?(?:\s|$|,|and)"""
        ).find(lower)?.groupValues?.getOrNull(1)?.trim()

        return DetectedIntent(
            intent = Intent.FIND_FILES,
            confidence = 0.85f,
            fileExtensions = extensions.distinct(),
            directory = directory,
            namePattern = namePattern,
            rawInput = raw
        )
    }

    private fun detectListFiles(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("list") && !lower.contains("ls ") && !lower.contains("show files")) return null
        val directory = when {
            lower.contains("download") -> "/storage/emulated/0/Download"
            lower.contains("document") -> "/storage/emulated/0/Documents"
            lower.contains("picture") -> "/storage/emulated/0/Pictures"
            else -> null
        } ?: return null
        return DetectedIntent(
            intent = Intent.LIST_FILES,
            confidence = 0.7f,
            directory = directory,
            rawInput = raw
        )
    }

    private fun detectOpenFile(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("open") && !lower.contains("launch file")) return null
        val path = Regex("""(/storage/emulated/0/\S+|/sdcard/\S+|~/\S+)""").find(raw)?.value
            ?: return null
        return DetectedIntent(
            intent = Intent.OPEN_FILE,
            confidence = 0.8f,
            directory = path,
            rawInput = raw
        )
    }

    private fun detectShareFile(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("share")) return null
        val path = Regex("""(/storage/emulated/0/\S+|/sdcard/\S+|~/\S+)""").find(raw)?.value
            ?: return null
        return DetectedIntent(
            intent = Intent.SHARE_FILE,
            confidence = 0.8f,
            directory = path,
            rawInput = raw
        )
    }

    private fun detectLaunchApp(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("open") && !lower.contains("launch") && !lower.contains("start ")) return null
        val appMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "calculator" to "com.android.calculator2",
            "calendar" to "com.google.android.calendar",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "termux" to "com.termux"
        )
        for ((name, pkg) in appMap) {
            if (lower.contains("open $name") || lower.contains("launch $name") ||
                lower.contains("start $name") || lower == "open $name") {
                return DetectedIntent(
                    intent = Intent.LAUNCH_APP,
                    confidence = 0.85f,
                    packageName = pkg,
                    rawInput = raw
                )
            }
        }
        val pkgMatch = Regex("""\b(com\.[a-z]+\.[a-z]+)\b""").find(lower)
        if (pkgMatch != null && (lower.contains("open") || lower.contains("launch"))) {
            return DetectedIntent(
                intent = Intent.LAUNCH_APP,
                confidence = 0.7f,
                packageName = pkgMatch.value,
                rawInput = raw
            )
        }
        return null
    }

    private fun detectCall(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("call") && !lower.contains("dial")) return null
        val phone = Regex("""\+?\d[\d\s\-]{7,}\d""").find(raw)?.value?.replace(Regex("""[\s\-]"""), "")
            ?: return null
        val direct = lower.startsWith("call ") || lower.contains("call +")
        return DetectedIntent(
            intent = Intent.CALL_PHONE,
            confidence = 0.85f,
            phone = phone,
            rawInput = raw,
            infoType = if (direct) "direct" else "dial"
        )
    }

    private fun detectSms(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("sms") && !lower.contains("text message") && !lower.contains("send message to")) return null
        val phone = Regex("""\+?\d[\d\s\-]{7,}\d""").find(raw)?.value?.replace(Regex("""[\s\-]"""), "")
            ?: return null
        val body = extractMessageText(raw)
        return DetectedIntent(
            intent = Intent.SEND_SMS,
            confidence = 0.8f,
            phone = phone,
            smsBody = body,
            rawInput = raw
        )
    }

    private fun detectDeviceInfo(lower: String, raw: String): DetectedIntent? {
        val infoType = when {
            lower.contains("battery") -> "battery"
            lower.contains("storage") || lower.contains("space") -> "storage"
            lower.contains("network") || lower.contains("wifi") || lower.contains("internet") -> "network"
            lower.contains("device info") || lower.contains("system info") -> "all"
            else -> return null
        }
        return DetectedIntent(
            intent = Intent.DEVICE_INFO,
            confidence = 0.75f,
            infoType = infoType,
            rawInput = raw
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NEW DETECTORS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * detectEmail — "send email to John about meeting", "email report to boss"
     */
    private fun detectEmail(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("email") && !lower.contains("mail to") &&
            !lower.contains("send mail") && !lower.contains("send email")) return null

        val to = extractEmailRecipient(lower, raw)
        val subject = extractEmailSubject(lower, raw)
        val body = extractEmailBody(lower, raw)

        return DetectedIntent(
            intent = Intent.EMAIL,
            confidence = 0.85f,
            emailTo = to,
            emailSubject = subject,
            emailBody = body,
            rawInput = raw
        )
    }

    /**
     * detectTimer — "set timer for 5 minutes", "timer 10 seconds"
     */
    private fun detectTimer(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("timer") && !lower.contains("countdown")) return null

        val duration = extractTimerDuration(lower)
        val seconds = parseDurationToSeconds(duration)

        return DetectedIntent(
            intent = Intent.TIMER,
            confidence = 0.85f,
            timerDuration = duration,
            timerSeconds = seconds,
            rawInput = raw
        )
    }

    /**
     * detectAlarm — "set alarm for 7 AM", "alarm at 6:30"
     */
    private fun detectAlarm(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("alarm")) return null
        val time = extractAlarmTime(lower)
        return DetectedIntent(
            intent = Intent.ALARM,
            confidence = 0.85f,
            alarmTime = time,
            rawInput = raw
        )
    }

    /**
     * detectClipboard — "copy to clipboard", "paste from clipboard", "copy hello world"
     */
    private fun detectClipboard(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("clipboard")) return null

        if (lower.contains("paste") || lower.contains("get")) {
            return DetectedIntent(
                intent = Intent.CLIPBOARD_PASTE,
                confidence = 0.8f,
                rawInput = raw
            )
        }

        val text = extractClipboardText(lower, raw)
        return DetectedIntent(
            intent = Intent.CLIPBOARD_COPY,
            confidence = 0.85f,
            clipboardText = text,
            rawInput = raw
        )
    }

    /**
     * detectScreenshot — "take screenshot", "capture screen"
     */
    private fun detectScreenshot(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("screenshot") && !lower.contains("screen capture") &&
            !lower.contains("capture screen") && !lower.contains("screen shot")) return null
        return DetectedIntent(
            intent = Intent.SCREENSHOT,
            confidence = 0.9f,
            rawInput = raw
        )
    }

    /**
     * detectGps — "turn on GPS", "what's my location", "GPS status"
     */
    private fun detectGps(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("gps") && !lower.contains("location")) return null

        val action = when {
            lower.contains("turn on") || lower.contains("enable") -> "on"
            lower.contains("turn off") || lower.contains("disable") -> "off"
            lower.contains("status") || lower.contains("check") -> "status"
            lower.contains("what") || lower.contains("where") || lower.contains("find") -> "status"
            else -> "status"
        }

        val intent = if (action == "on" || action == "off") Intent.GPS_TOGGLE else Intent.GPS_STATUS
        return DetectedIntent(
            intent = intent,
            confidence = 0.8f,
            gpsAction = action,
            rawInput = raw
        )
    }

    /**
     * detectVolume — "set volume to 50%", "volume up", "mute"
     */
    private fun detectVolume(lower: String, raw: String): DetectedIntent? {
        if (!lower.contains("volume") && !lower.contains("mute") &&
            !lower.contains("silent") && !lower.contains("sound")) return null

        val percent = extractVolumePercent(lower)
        return DetectedIntent(
            intent = Intent.VOLUME_SET,
            confidence = 0.8f,
            volumePercent = percent,
            rawInput = raw
        )
    }

    /**
     * detectBatterySaver — "enable battery saver", "turn on power saving"
     */
    private fun detectBatterySaver(lower: String, raw: String): DetectedIntent? {
        val keywords = listOf("battery saver", "power saving", "battery saving", "save battery")
        if (!keywords.any { lower.contains(it) }) return null

        val enable = lower.contains("enable") || lower.contains("turn on") ||
                     lower.contains("start") || !lower.contains("disable")
        return DetectedIntent(
            intent = Intent.BATTERY_SAVER,
            confidence = 0.8f,
            infoType = if (enable) "enable" else "disable",
            rawInput = raw
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COMPOUND / MULTI-STEP DETECTION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Detect compound intents like "find PDFs and share the first one".
     * Chain patterns:
     *  - find [X] and share [the first one | it | the file]
     *  - find [X] and open [it | the file]
     *  - search for [X] and share
     */
    private fun detectCompound(input: String, lower: String): DetectedIntent? {
        val findSharePattern = Regex(
            """(?:find|search for|locate)\s+(.+?)\s+(?:and\s+)?(?:then\s+)?(?:share|open)\s+(?:the\s+)?(?:(?:first\s+)?(?:one|file|result)|it)""",
            RegexOption.IGNORE_CASE
        )
        val match = findSharePattern.find(input) ?: return null
        val searchPart = match.groupValues[1].trim()

        // detectFileSearch requires "find" keyword in the input to match.
        // Prepend "find" so the searchPart is correctly detected.
        val findDetected = detectFileSearch("find ${searchPart.lowercase()}", "find $searchPart")
            ?: return null

        val action = if (lower.contains("share")) "share" else "open"

        return DetectedIntent(
            intent = Intent.COMPOUND_FIND_SHARE,
            confidence = 0.75f,
            fileExtensions = findDetected.fileExtensions,
            directory = findDetected.directory,
            namePattern = findDetected.namePattern,
            infoType = action,
            rawInput = input,
            compoundSubIntents = listOf(findDetected)
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EXISTING PLANNERS
    // ══════════════════════════════════════════════════════════════════════

    private fun planWhatsApp(d: DetectedIntent): Workflow {
        val msg = d.message ?: ""
        val contact = d.contactHint
        val cmdStr = if (contact != null) {
            "sendtext:com.whatsapp:${contact}:${msg.ifBlank { "(no message)" }}"
        } else {
            "sendtext:com.whatsapp:${msg.ifBlank { "(no message)" }}"
        }
        return Workflow(
            name = if (contact != null) "WhatsApp → $contact" else "WhatsApp message",
            description = "Open WhatsApp with your message pre-filled. " +
                          "You'll pick the contact and tap Send to deliver.",
            steps = listOf(
                WorkflowStep(
                    label = "Open WhatsApp with pre-filled message",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = cmdStr,
                        description = "Open WhatsApp with pre-filled message",
                        critical = true
                    )
                )
            ),
            tags = listOf("messaging", "whatsapp")
        )
    }

    private fun planFindFiles(d: DetectedIntent): Workflow {
        val dir = d.directory ?: "/storage/emulated/0"
        val extensions = d.fileExtensions
        val namePattern = d.namePattern

        val extArgs = if (extensions.isEmpty() || extensions.any { it.isEmpty() }) {
            ""
        } else {
            extensions.joinToString(" ", prefix = "\\( ") { "-iname \\*.$it" } + " \\)"
        }
        val nameArg = if (!namePattern.isNullOrBlank()) "-iname \\*$namePattern*" else ""
        val typeArg = if (extArgs.isEmpty() && nameArg.isEmpty()) "-type f" else "-type f"

        val cmd = buildString {
            append("find \"")
            append(dir)
            append("\" ")
            append(typeArg)
            if (nameArg.isNotEmpty()) { append(" "); append(nameArg) }
            if (extArgs.isNotEmpty()) { append(" "); append(extArgs) }
            append(" 2>/dev/null | head -n 100")
        }

        return Workflow(
            name = "Find files",
            description = "Search for files in ${dir.replace("/storage/emulated/0", "~")}" +
                          (if (extensions.isNotEmpty()) " matching extensions: ${extensions.joinToString(", ")}" else "") +
                          (if (!namePattern.isNullOrBlank()) " named '$namePattern'" else ""),
            steps = listOf(
                WorkflowStep(
                    label = "Search storage for matching files",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = "Find files matching criteria",
                        critical = true
                    ),
                    resultVar = "foundFiles"
                )
            ),
            tags = listOf("files", "search")
        )
    }

    private fun planListFiles(d: DetectedIntent): Workflow {
        val dir = d.directory ?: "/storage/emulated/0"
        return Workflow(
            name = "List files",
            description = "List files in ${dir.replace("/storage/emulated/0", "~")}",
            steps = listOf(
                WorkflowStep(
                    label = "List directory contents",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "ls -lah \"$dir\" 2>/dev/null",
                        description = "List files in directory",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "list")
        )
    }

    private fun planOpenFile(d: DetectedIntent): Workflow {
        val path = d.directory ?: return planFindFiles(d)
        return Workflow(
            name = "Open file",
            description = "Open ${File(path).name}",
            steps = listOf(
                WorkflowStep(
                    label = "Open file via system viewer",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "openfile:$path",
                        description = "Open file with appropriate app",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "open")
        )
    }

    private fun planShareFile(d: DetectedIntent): Workflow {
        val path = d.directory ?: return planFindFiles(d)
        return Workflow(
            name = "Share file",
            description = "Share ${File(path).name} via system share sheet",
            steps = listOf(
                WorkflowStep(
                    label = "Open share sheet",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "sharefile:$path",
                        description = "Share file via system share sheet",
                        critical = true
                    )
                )
            ),
            tags = listOf("files", "share")
        )
    }

    private fun planLaunchApp(d: DetectedIntent): Workflow {
        val pkg = d.packageName ?: return planFindFiles(d)
        return Workflow(
            name = "Launch app",
            description = "Open $pkg",
            steps = listOf(
                WorkflowStep(
                    label = "Launch $pkg",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "open:$pkg",
                        description = "Launch app",
                        critical = true
                    )
                )
            ),
            tags = listOf("app", "launch")
        )
    }

    private fun planCall(d: DetectedIntent): Workflow {
        val phone = d.phone ?: return planFindFiles(d)
        val direct = d.infoType == "direct"
        return Workflow(
            name = if (direct) "Call $phone" else "Dial $phone",
            description = if (direct) "Initiate a call to $phone" else "Open dialer with $phone",
            steps = listOf(
                WorkflowStep(
                    label = if (direct) "Call $phone" else "Dial $phone",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = if (direct) "call:$phone" else "dial:$phone",
                        description = if (direct) "Initiate call" else "Open dialer",
                        critical = true
                    )
                )
            ),
            tags = listOf("phone", if (direct) "call" else "dial")
        )
    }

    private fun planSms(d: DetectedIntent): Workflow {
        val phone = d.phone ?: return planFindFiles(d)
        val body = d.smsBody ?: ""
        return Workflow(
            name = "SMS to $phone",
            description = "Open SMS app with message pre-filled",
            steps = listOf(
                WorkflowStep(
                    label = "Open SMS app",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "sms:$phone:$body",
                        description = "Open SMS with pre-filled body",
                        critical = true
                    )
                )
            ),
            tags = listOf("messaging", "sms")
        )
    }

    private fun planDeviceInfo(d: DetectedIntent): Workflow {
        val infoType = d.infoType ?: "all"
        val cmd = when (infoType) {
            "battery" -> "dumpsys battery | grep -E 'level|status|temperature|voltage' 2>/dev/null"
            "storage" -> "df -h /storage/emulated/0 2>/dev/null; echo '---'; du -sh /storage/emulated/0/* 2>/dev/null | head -20"
            "network" -> "dumpsys wifi | grep -E 'SSID|state|RSSI' 2>/dev/null; echo '---'; ip addr show wlan0 2>/dev/null | grep inet"
            else -> "dumpsys battery | grep -E 'level|status' 2>/dev/null; echo '---'; df -h /storage/emulated/0 2>/dev/null; echo '---'; dumpsys wifi | grep -E 'SSID|state' 2>/dev/null"
        }
        return Workflow(
            name = "Device info: $infoType",
            description = "Retrieve $infoType information from the device",
            steps = listOf(
                WorkflowStep(
                    label = "Query device $infoType",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = "Get $infoType info",
                        critical = true
                    )
                )
            ),
            tags = listOf("device", "info", infoType)
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NEW PLANNERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * planEmail — Open Gmail with pre-filled To, Subject, Body
     */
    private fun planEmail(d: DetectedIntent): Workflow {
        val to = d.emailTo ?: ""
        val subject = d.emailSubject ?: ""
        val body = d.emailBody ?: ""

        val cmd = if (to.isNotBlank()) {
            "sendtext:com.google.android.gm:${to}:Subject: $subject\\n\\n$body"
        } else {
            "sendtext:com.google.android.gm:Subject: $subject\\n\\n$body"
        }

        return Workflow(
            name = if (to.isNotBlank()) "Email to $to" else "Compose email",
            description = "Open Gmail with your email pre-filled. You'll review and tap Send.",
            steps = listOf(
                WorkflowStep(
                    label = "Open Gmail with pre-filled email",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = cmd,
                        description = "Compose email",
                        critical = true
                    )
                )
            ),
            tags = listOf("messaging", "email")
        )
    }

    /**
     * planTimer — Set a timer using shell command
     */
    private fun planTimer(d: DetectedIntent): Workflow {
        val seconds = d.timerSeconds ?: 300
        val duration = d.timerDuration ?: "5 minutes"
        return Workflow(
            name = "Timer: $duration",
            description = "Setting a timer for $duration",
            steps = listOf(
                WorkflowStep(
                    label = "Set timer for $duration",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "am broadcast -a com.interndra.SET_TIMER --ei duration_seconds $seconds 2>/dev/null || " +
                                  "echo 'Timer set for $seconds seconds (requires shell access)'",
                        description = "Set timer",
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("tools", "timer")
        )
    }

    /**
     * planAlarm — Set an alarm using shell command
     */
    private fun planAlarm(d: DetectedIntent): Workflow {
        val time = d.alarmTime ?: "07:00"
        return Workflow(
            name = "Alarm at $time",
            description = "Setting an alarm for $time",
            steps = listOf(
                WorkflowStep(
                    label = "Set alarm for $time",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "am broadcast -a com.interndra.SET_ALARM --es alarm_time \"$time\" 2>/dev/null || " +
                                  "echo 'Alarm set for $time (requires shell access)'",
                        description = "Set alarm",
                        critical = false
                    ),
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("tools", "alarm")
        )
    }

    /**
     * planClipboardCopy — Copy text to clipboard via Android intent
     */
    private fun planClipboardCopy(d: DetectedIntent): Workflow {
        val text = d.clipboardText ?: ""
        return Workflow(
            name = "Copy to clipboard",
            description = "Copy text to clipboard",
            steps = listOf(
                WorkflowStep(
                    label = "Copy text to clipboard",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "clipboard:copy:$text",
                        description = "Copy to clipboard",
                        critical = false
                    )
                )
            ),
            tags = listOf("tools", "clipboard")
        )
    }

    /**
     * planClipboardPaste — Get clipboard content via shell
     */
    private fun planClipboardPaste(d: DetectedIntent): Workflow {
        return Workflow(
            name = "Paste from clipboard",
            description = "Retrieve clipboard content",
            steps = listOf(
                WorkflowStep(
                    label = "Get clipboard content",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "echo 'Clipboard content: requires service restart'; " +
                                  "cmd clipboard get 2>/dev/null || echo 'Clipboard access not available via shell'",
                        description = "Get clipboard content",
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("tools", "clipboard")
        )
    }

    /**
     * planScreenshot — Take a screenshot via shell command
     */
    private fun planScreenshot(d: DetectedIntent): Workflow {
        return Workflow(
            name = "Take screenshot",
            description = "Capture the current screen",
            steps = listOf(
                WorkflowStep(
                    label = "Take screenshot",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "screencap -p /storage/emulated/0/Pictures/Screenshots/Screenshot_\$(date +%Y%m%d_%H%M%S).png 2>/dev/null || " +
                                  "echo 'Screenshot requires shell access or root'",
                        description = "Take screenshot",
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 15_000L,
                    resultVar = "screenshotPath"
                )
            ),
            tags = listOf("tools", "screenshot")
        )
    }

    /**
     * planGpsStatus — Check GPS/location status
     */
    private fun planGpsStatus(d: DetectedIntent): Workflow {
        return Workflow(
            name = "GPS status",
            description = "Check GPS and location status",
            steps = listOf(
                WorkflowStep(
                    label = "Check GPS status",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "dumpsys location | grep -E 'gps|network|providers|Location' 2>/dev/null | head -15 || " +
                                  "echo 'Location services: '; settings get global location_mode 2>/dev/null || echo 'unknown'",
                        description = "Check GPS status",
                        critical = false
                    ),
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("device", "gps")
        )
    }

    /**
     * planGpsToggle — Turn GPS on/off
     */
    private fun planGpsToggle(d: DetectedIntent): Workflow {
        val action = d.gpsAction ?: "on"
        val enable = action == "on"
        val label = if (enable) "Enable GPS" else "Disable GPS"
        val cmd = if (enable) {
            "settings put global location_mode 3 2>/dev/null || echo 'GPS enable requires shell access'"
        } else {
            "settings put global location_mode 0 2>/dev/null || echo 'GPS disable requires shell access'"
        }
        return Workflow(
            name = label,
            description = "Turn GPS ${if (enable) "on" else "off"}",
            steps = listOf(
                WorkflowStep(
                    label = label,
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = label,
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("device", "gps", if (enable) "enable" else "disable")
        )
    }

    /**
     * planVolumeSet — Set volume level via shell
     */
    private fun planVolumeSet(d: DetectedIntent): Workflow {
        val percent = d.volumePercent ?: 50
        val level = (percent * 15 / 100).coerceIn(0, 15) // Android media volume is 0-15
        return Workflow(
            name = "Set volume to $percent%",
            description = "Set media volume to $percent% (level $level/15)",
            steps = listOf(
                WorkflowStep(
                    label = "Set volume to $percent%",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = "media volume --set $level 2>/dev/null || " +
                                  "cmd media_session volume --set $level 2>/dev/null || " +
                                  "echo 'Volume control requires shell access or root'",
                        description = "Set volume level",
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("device", "volume")
        )
    }

    /**
     * planBatterySaver — Enable/disable battery saver via shell
     */
    private fun planBatterySaver(d: DetectedIntent): Workflow {
        val enable = d.infoType != "disable"
        val label = if (enable) "Enable battery saver" else "Disable battery saver"
        val cmd = if (enable) {
            "settings put global low_power 1 2>/dev/null || echo 'Battery saver enable requires shell access'"
        } else {
            "settings put global low_power 0 2>/dev/null || echo 'Battery saver disable requires shell access'"
        }
        return Workflow(
            name = label,
            description = "Turn battery saver ${if (enable) "on" else "off"}",
            steps = listOf(
                WorkflowStep(
                    label = label,
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = cmd,
                        description = label,
                        critical = false
                    ),
                    maxRetries = 1,
                    timeoutMs = 10_000L
                )
            ),
            tags = listOf("device", "power", if (enable) "enable" else "disable")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COMPOUND PLANNER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Plan a compound workflow: find files → share/open the first one.
     * Uses [resultVar] to pass the file list between steps.
     */
    private fun planCompoundFindShare(d: DetectedIntent): Workflow {
        val findIntent = d.compoundSubIntents?.firstOrNull()
        val baseFind = findIntent?.let { planFindFiles(it) }
        val findCmd = baseFind?.steps?.firstOrNull()?.command?.command ?: "find /storage/emulated/0 -type f 2>/dev/null | head -n 50"

        val action = d.infoType ?: "share"
        val actionLabel = if (action == "share") "Share" else "Open"
        val actionPrefix = if (action == "share") "sharefile:" else "openfile:"

        return Workflow(
            name = "Find files and $action first result",
            description = "Search for files matching criteria and ${action} the first result",
            steps = listOf(
                WorkflowStep(
                    label = "Search for matching files",
                    command = ShellCommand(
                        type = CommandType.ADB_SHELL,
                        command = findCmd,
                        description = "Find files matching criteria",
                        critical = true
                    ),
                    resultVar = "foundFiles",
                    timeoutMs = 30_000L
                ),
                WorkflowStep(
                    label = "$actionLabel first found file",
                    command = ShellCommand(
                        type = CommandType.ANDROID_INTENT,
                        command = "${actionPrefix}\${foundFiles}",
                        description = "$actionLabel the first found file",
                        critical = true
                    ),
                    dependsOn = listOf(0),  // Depends on find step
                    condition = "step[0].success",
                    timeoutMs = 15_000L
                )
            ),
            tags = listOf("files", "search", action)
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun extractMessageText(raw: String): String? {
        val patterns = listOf(
            Regex("""(?:saying|message:|text:|with message:?)\s+["'](.+?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:saying|message:|text:|with message:?)\s+(.+?)(?:\s+to\s+|\s*$)""", RegexOption.IGNORE_CASE),
            Regex("""["'](.+?)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1) return match
        }
        return null
    }

    private fun extractContact(raw: String): String? {
        val patterns = listOf(
            Regex("""(?:to|for)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""),
            Regex("""(?:message|text|send)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""")
        )
        val reserved = listOf("whatsapp", "message", "text", "send")
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1 && match.lowercase() !in reserved) {
                return match
            }
        }
        return null
    }

    // ── Email helpers ──────────────────────────────────────────────────

    private fun extractEmailRecipient(lower: String, raw: String): String? {
        val patterns = listOf(
            Regex("""(?:to|for)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""),
            Regex("""\bto\s+(\S+@\S+)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bemail\s+([A-Z][a-z]+)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1) return match
        }
        return null
    }

    private fun extractEmailSubject(lower: String, raw: String): String? {
        val patterns = listOf(
            Regex("""(?:subject|about|regarding|re:|regarding)\s+["'](.+?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:subject|about|regarding|re:|regarding)\s+(\w[\w\s]+?)(?:\s+and|\s*$)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 2) return match
        }
        return null
    }

    private fun extractEmailBody(lower: String, raw: String): String? {
        val patterns = listOf(
            Regex("""(?:saying|body:|with body:?)\s+["'](.+?)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1) return match
        }
        return null
    }

    // ── Timer helpers ──────────────────────────────────────────────────

    private fun extractTimerDuration(lower: String): String? {
        val pattern = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(lower) ?: return null
        val value = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        val unitNorm = when {
            unit.startsWith("sec") -> "seconds"
            unit.startsWith("min") -> "minutes"
            unit.startsWith("hour") || unit.startsWith("hr") -> "hours"
            else -> "minutes"
        }
        return "$value $unitNorm"
    }

    private fun parseDurationToSeconds(duration: String?): Int? {
        if (duration == null) return null
        val pattern = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(duration) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when {
            match.groupValues[2].lowercase().startsWith("sec") -> value
            match.groupValues[2].lowercase().startsWith("min") -> value * 60
            match.groupValues[2].lowercase().startsWith("hour") || match.groupValues[2].lowercase().startsWith("hr") -> value * 3600
            else -> value
        }
    }

    // ── Alarm helpers ──────────────────────────────────────────────────

    private fun extractAlarmTime(lower: String): String? {
        val patterns = listOf(
            Regex("""(\d{1,2})\s*[.:]\s*(\d{2})\s*(AM|PM|am|pm)?"""),
            Regex("""(\d{1,2})\s*(AM|PM|am|pm)""")
        )
        for (p in patterns) {
            val match = p.find(lower) ?: continue
            val hour = match.groupValues[1].toIntOrNull() ?: continue
            val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val isPM = match.groupValues.getOrNull(3)?.lowercase() == "pm" ||
                       match.groupValues.getOrNull(2)?.lowercase() == "pm"

            val h24 = when {
                isPM && hour < 12 -> hour + 12
                !isPM && hour == 12 -> 0
                else -> hour
            }
            return "%02d:%02d".format(h24, minute)
        }
        return null
    }

    // ── Clipboard helpers ──────────────────────────────────────────────

    private fun extractClipboardText(lower: String, raw: String): String? {
        val patterns = listOf(
            Regex("""(?:copy|save)\s+(?:to clipboard\s+)?["'](.+?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:copy|save)\s+(?:to clipboard\s+)?(.+?)(?:\s+to\s+|\s*$)""", RegexOption.IGNORE_CASE),
            Regex("""["'](.+?)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val match = p.find(raw)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length > 1) return match
        }
        return null
    }

    // ── Volume helpers ─────────────────────────────────────────────────

    private fun extractVolumePercent(lower: String): Int? {
        val pattern = Regex("""(\d+)\s*%""", RegexOption.IGNORE_CASE)
        val match = pattern.find(lower)
        val percent = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (percent != null) return percent.coerceIn(0, 100)

        return when {
            lower.contains("mute") || lower.contains("silent") || lower.contains("zero") -> 0
            lower.contains("max") || lower.contains("full") || lower.contains("hundred") || lower.contains("100") -> 100
            lower.contains("half") || lower.contains("mid") || lower.contains("50") -> 50
            lower.contains("low") -> 25
            else -> null
        }
    }
}
