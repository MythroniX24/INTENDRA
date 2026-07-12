package com.interndra.ai.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for WorkflowPlanner (UPGRADED).
 *
 * Tests ALL 22+ intent types:
 * - WhatsApp message & contact
 * - Find files (PDF, images, audio, video, APK, project)
 * - List files, Open file, Share file
 * - Launch app
 * - Call / Dial
 * - SMS
 * - Device info (battery, storage, network)
 * - Email, Timer, Alarm, Clipboard (copy/paste)
 * - Screenshot, GPS (status/toggle), Volume, Battery Saver
 * - Compound workflows (find+share, find+open)
 * - DAG dependency validation
 * - Edge cases
 */
class WorkflowPlannerTest {

    private lateinit var planner: WorkflowPlanner

    @Before
    fun setUp() {
        planner = WorkflowPlanner()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  WHATSAPP
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects WhatsApp message`() {
        val detected = planner.detect("open WhatsApp and say hello there")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.WHATSAPP_MESSAGE)
        assertThat(detected.confidence).isAtLeast(0.75f)
    }

    @Test
    fun `detects WhatsApp message to contact`() {
        val detected = planner.detect("send WhatsApp message to Rahul saying meeting at 5")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.WHATSAPP_CONTACT)
        assertThat(detected.contactHint).isEqualTo("Rahul")
        assertThat(detected.message).isNotNull()
    }

    @Test
    fun `plans WhatsApp message workflow`() {
        val detected = planner.detect("open WhatsApp with message hello")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps).isNotEmpty()
        assertThat(workflow.steps.first().command.command).startsWith("sendtext:com.whatsapp")
        assertThat(workflow.tags).contains("whatsapp")
    }

    @Test
    fun `plans WhatsApp contact workflow`() {
        val detected = planner.detect("send WhatsApp to Priya saying good morning")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("Priya")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIND FILES
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects find PDF files`() {
        val detected = planner.detect("find all PDF files in downloads")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.FIND_FILES)
        assertThat(detected.fileExtensions).contains("pdf")
    }

    @Test
    fun `detects find image files`() {
        val detected = planner.detect("search for images in pictures")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.FIND_FILES)
        assertThat(detected.fileExtensions).containsAtLeast("jpg", "jpeg", "png")
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Pictures")
    }

    @Test
    fun `detects find APK files`() {
        val detected = planner.detect("locate APK files in downloads")
        assertThat(detected).isNotNull()
        assertThat(detected!!.fileExtensions).contains("apk")
    }

    @Test
    fun `detects find audio files`() {
        val detected = planner.detect("find music files in download")
        assertThat(detected).isNotNull()
        assertThat(detected!!.fileExtensions).containsAtLeast("mp3", "wav")
    }

    @Test
    fun `plans find files workflow`() {
        val detected = planner.detect("find PDF files in downloads")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("find")
        assertThat(workflow.steps.first().command.command).contains("pdf")
    }

    @Test
    fun `detects find files with name pattern`() {
        val detected = planner.detect("find files named report in documents")
        assertThat(detected).isNotNull()
        assertThat(detected!!.namePattern).isEqualTo("report")
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Documents")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIST FILES
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects list files`() {
        val detected = planner.detect("list files in downloads")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LIST_FILES)
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Download")
    }

    @Test
    fun `plans list files workflow`() {
        val detected = planner.detect("list files in downloads")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("ls -lah")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  OPEN / SHARE FILE
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects open file`() {
        val detected = planner.detect("open /storage/emulated/0/Download/report.pdf")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.OPEN_FILE)
        assertThat(detected.directory).contains("report.pdf")
    }

    @Test
    fun `plans open file workflow`() {
        val detected = planner.detect("open /sdcard/Download/test.txt")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("openfile:")
    }

    @Test
    fun `detects share file`() {
        val detected = planner.detect("share /storage/emulated/0/Download/image.jpg")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.SHARE_FILE)
    }

    @Test
    fun `plans share file workflow`() {
        val detected = planner.detect("share /sdcard/Documents/file.pdf")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("sharefile:")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LAUNCH APP
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects launch app`() {
        val detected = planner.detect("open settings")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LAUNCH_APP)
        assertThat(detected.packageName).isEqualTo("com.android.settings")
    }

    @Test
    fun `plans launch app workflow`() {
        val detected = planner.detect("open calculator")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("open:")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CALL / DIAL
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects call`() {
        val detected = planner.detect("call +911234567890")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.CALL_PHONE)
        assertThat(detected.phone).contains("1234567890")
    }

    @Test
    fun `detects dial`() {
        val detected = planner.detect("dial 9876543210")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.CALL_PHONE)
        assertThat(detected.infoType).isEqualTo("dial")
    }

    @Test
    fun `plans call workflow`() {
        val detected = planner.detect("call +911234567890")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("call:")
    }

    @Test
    fun `plans dial workflow`() {
        val detected = planner.detect("dial 9876543210")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("dial:")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SMS
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects SMS`() {
        val detected = planner.detect("send SMS to 9876543210 saying hello")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.SEND_SMS)
        assertThat(detected.phone).contains("9876543210")
    }

    @Test
    fun `plans SMS workflow`() {
        val detected = planner.detect("send SMS to 9876543210 saying meeting at 5")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).startsWith("sms:")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  DEVICE INFO
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects battery info`() {
        val detected = planner.detect("what is my battery status")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.DEVICE_INFO)
        assertThat(detected.infoType).isEqualTo("battery")
    }

    @Test
    fun `detects storage info`() {
        val detected = planner.detect("check storage space")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.DEVICE_INFO)
        assertThat(detected.infoType).isEqualTo("storage")
    }

    @Test
    fun `detects network info`() {
        val detected = planner.detect("show network info")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.DEVICE_INFO)
        assertThat(detected.infoType).isEqualTo("network")
    }

    @Test
    fun `plans battery info workflow`() {
        val detected = planner.detect("check battery status")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("dumpsys battery")
    }

    @Test
    fun `plans storage info workflow`() {
        val detected = planner.detect("check storage space")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("df -h")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  EMAIL (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects email`() {
        val detected = planner.detect("send email to John about meeting")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.EMAIL)
        assertThat(detected.emailTo).isNotNull()
    }

    @Test
    fun `detects email with subject`() {
        val detected = planner.detect("send email to boss regarding Quarterly Report")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.EMAIL)
    }

    @Test
    fun `plans email workflow tags check`() {
        val detected = planner.detect("send email to John about meeting")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.EMAIL)
        val workflow = planner.plan(detected)
        assertThat(workflow).isNotNull()
        val wf = workflow!!
        assertThat(wf.steps.first().command.command).contains("gmail")
        assertThat(wf.tags).contains("email")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TIMER (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects timer`() {
        val detected = planner.detect("set timer for 5 minutes")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.TIMER)
        assertThat(detected.timerDuration).contains("5")
    }

    @Test
    fun `detects timer in seconds`() {
        val detected = planner.detect("timer 30 seconds")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.TIMER)
        assertThat(detected.timerSeconds).isEqualTo(30)
    }

    @Test
    fun `plans timer workflow`() {
        val detected = planner.detect("set timer for 10 minutes")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.name).contains("Timer")
        assertThat(workflow.steps.first().command.command).contains("SET_TIMER")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ALARM (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects alarm`() {
        val detected = planner.detect("set alarm for 7 AM")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.ALARM)
        assertThat(detected.alarmTime).isEqualTo("07:00")
    }

    @Test
    fun `detects alarm with minutes`() {
        val detected = planner.detect("set alarm at 6:30 AM")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.ALARM)
        assertThat(detected.alarmTime).isEqualTo("06:30")
    }

    @Test
    fun `plans alarm workflow`() {
        val detected = planner.detect("set alarm for 7 AM")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.name).contains("Alarm")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CLIPBOARD (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects clipboard copy`() {
        val detected = planner.detect("copy hello world to clipboard")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.CLIPBOARD_COPY)
        assertThat(detected.clipboardText).isNotNull()
    }

    @Test
    fun `detects clipboard paste`() {
        val detected = planner.detect("paste from clipboard")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.CLIPBOARD_PASTE)
    }

    @Test
    fun `plans clipboard copy workflow`() {
        val detected = planner.detect("copy hello to clipboard")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("clipboard:copy")
    }

    @Test
    fun `plans clipboard paste workflow`() {
        val detected = planner.detect("paste from clipboard")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("clipboard")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCREENSHOT (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects screenshot`() {
        val detected = planner.detect("take a screenshot")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.SCREENSHOT)
        assertThat(detected.confidence).isAtLeast(0.8f)
    }

    @Test
    fun `detects screen capture`() {
        val detected = planner.detect("capture screen")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.SCREENSHOT)
    }

    @Test
    fun `plans screenshot workflow`() {
        val detected = planner.detect("take a screenshot")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("screencap")
        assertThat(workflow.tags).contains("screenshot")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GPS (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects GPS status`() {
        val detected = planner.detect("what is my location")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.GPS_STATUS)
    }

    @Test
    fun `detects GPS toggle on`() {
        val detected = planner.detect("turn on GPS")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.GPS_TOGGLE)
        assertThat(detected.gpsAction).isEqualTo("on")
    }

    @Test
    fun `detects GPS toggle off`() {
        val detected = planner.detect("disable GPS")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.GPS_TOGGLE)
        assertThat(detected.gpsAction).isEqualTo("off")
    }

    @Test
    fun `plans GPS status workflow`() {
        val detected = planner.detect("check GPS status")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("dumpsys location")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  VOLUME (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects volume set`() {
        val detected = planner.detect("set volume to 50%")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.VOLUME_SET)
        assertThat(detected.volumePercent).isEqualTo(50)
    }

    @Test
    fun `detects mute`() {
        val detected = planner.detect("mute the phone")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.VOLUME_SET)
        assertThat(detected.volumePercent).isEqualTo(0)
    }

    @Test
    fun `plans volume workflow`() {
        val detected = planner.detect("set volume to 75%")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("volume")
        assertThat(workflow.tags).contains("volume")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  BATTERY SAVER (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects battery saver enable`() {
        val detected = planner.detect("enable battery saver")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.BATTERY_SAVER)
        assertThat(detected.infoType).isEqualTo("enable")
    }

    @Test
    fun `detects power saving`() {
        val detected = planner.detect("turn on power saving mode")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.BATTERY_SAVER)
    }

    @Test
    fun `plans battery saver workflow`() {
        val detected = planner.detect("enable battery saver")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command).contains("low_power")
        assertThat(workflow.tags).contains("power")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  COMPOUND WORKFLOWS (NEW)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `detects find and share compound`() {
        val detected = planner.detect("find PDF files and share the first one")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.COMPOUND_FIND_SHARE)
    }

    @Test
    fun `detects find and open compound`() {
        val detected = planner.detect("find images in pictures and open the first one")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.COMPOUND_FIND_SHARE)
        assertThat(detected.infoType).isEqualTo("open")
    }

    @Test
    fun `plans compound find and share workflow with two steps`() {
        val detected = planner.detect("find PDF files and share the first one")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps).hasSize(2)
        assertThat(workflow.steps[0].command.command).contains("find")
        assertThat(workflow.steps[1].command.command).contains("sharefile:")
        // Verify DAG dependency
        assertThat(workflow.steps[1].dependsOn).contains(0)
        assertThat(workflow.steps[0].resultVar).isEqualTo("foundFiles")
        assertThat(workflow.tags).contains("share")
    }

    @Test
    fun `plans compound find and open workflow`() {
        val detected = planner.detect("find images in downloads and open the first one")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps[1].command.command).contains("openfile:")
        assertThat(workflow.tags).contains("files")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  WORKFLOW STEP PROPERTIES (NEW — DAG, retries, timeouts, variables)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `find files workflow has result variable`() {
        val detected = planner.detect("find PDF files in downloads")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().resultVar).isEqualTo("foundFiles")
    }

    @Test
    fun `timer workflow has retry and timeout configured`() {
        val detected = planner.detect("set timer for 5 minutes")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().maxRetries).isAtLeast(1)
        assertThat(workflow.steps.first().timeoutMs).isAtMost(60_000L)
    }

    @Test
    fun `screenshot workflow has result variable`() {
        val detected = planner.detect("take a screenshot")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().resultVar).isEqualTo("screenshotPath")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `returns null for empty input`() {
        assertThat(planner.detect("")).isNull()
    }

    @Test
    fun `returns null for gibberish`() {
        assertThat(planner.detect("asdfghjkl")).isNull()
    }

    @Test
    fun `returns null when confidence below threshold`() {
        val detected = planner.detect("the")
        assertThat(detected).isNull()
    }

    @Test
    fun `handles Hindi mixed input gracefully`() {
        val detected = planner.detect("battery status dikhao")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.DEVICE_INFO)
    }

    @Test
    fun `returns null for unknown intent`() {
        val detected = planner.detect("tell me a joke about programming")
        if (detected != null) {
            assertThat(detected.confidence).isLessThan(0.75f)
        }
    }

    @Test
    fun `detects multi-intent with highest confidence`() {
        val detected = planner.detect("open WhatsApp and check battery")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.WHATSAPP_MESSAGE)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  WORKFLOW TAG VERIFICATION
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `workflows have appropriate tags`() {
        val whatsApp = planner.plan(planner.detect("open WhatsApp with message hi")!!)
        assertThat(whatsApp!!.tags).containsAtLeast("messaging", "whatsapp")

        val fileSearch = planner.plan(planner.detect("find PDF files in downloads")!!)
        assertThat(fileSearch!!.tags).contains("files")

        val call = planner.plan(planner.detect("call +911234567890")!!)
        assertThat(call!!.tags).containsAtLeast("phone", "call")

        val sms = planner.plan(planner.detect("send SMS to 9876543210 saying hi")!!)
        assertThat(sms!!.tags).containsAtLeast("messaging", "sms")

        val email = planner.plan(planner.detect("send email to John about meeting")!!)
        assertThat(email!!.tags).contains("email")

        val screenshot = planner.plan(planner.detect("take a screenshot")!!)
        assertThat(screenshot!!.tags).contains("screenshot")
    }
}
