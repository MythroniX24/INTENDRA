package com.interndra.ai.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for WorkflowPlanner.
 *
 * Tests ALL 15 intent types:
 * - WhatsApp message & contact
 * - Find files (PDF, images, audio, video, APK, project)
 * - List files
 * - Open file
 * - Share file
 * - Launch app
 * - Call / Dial
 * - SMS
 * - Device info (battery, storage, network)
 */
class WorkflowPlannerTest {

    private lateinit var planner: WorkflowPlanner

    @Before
    fun setUp() {
        planner = WorkflowPlanner()
    }

    // ── WhatsApp ────────────────────────────────────────────────────────────
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
        assertThat(workflow.steps.first().command.command)
            .startsWith("sendtext:com.whatsapp")
        assertThat(workflow.tags).contains("whatsapp")
    }

    @Test
    fun `plans WhatsApp contact workflow`() {
        val detected = planner.detect("send WhatsApp to Priya saying good morning")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .contains("Priya")
    }

    @Test
    fun `returns null for unrelated WhatsApp input`() {
        val detected = planner.detect("whatsapp is a messaging app")
        assertThat(detected?.intent).isIn(listOf(
            WorkflowPlanner.Intent.WHATSAPP_MESSAGE, WorkflowPlanner.Intent.UNKNOWN
        ))
    }

    // ── Find Files ──────────────────────────────────────────────────────────
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
    fun `detects find video files`() {
        val detected = planner.detect("search for video files")
        assertThat(detected).isNotNull()
        assertThat(detected!!.fileExtensions).containsAtLeast("mp4", "mkv")
    }

    @Test
    fun `detects find project files`() {
        val detected = planner.detect("find project files in downloads")
        assertThat(detected).isNotNull()
        assertThat(detected!!.fileExtensions).containsAtLeast("kt", "java", "py")
    }

    @Test
    fun `plans find files workflow`() {
        val detected = planner.detect("find PDF files in downloads")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .contains("find")
        assertThat(workflow.steps.first().command.command)
            .contains("pdf")
    }

    @Test
    fun `detects find files with name pattern`() {
        val detected = planner.detect("find files named report in documents")
        assertThat(detected).isNotNull()
        assertThat(detected!!.namePattern).isEqualTo("report")
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Documents")
    }

    // ── List Files ──────────────────────────────────────────────────────────
    @Test
    fun `detects list files`() {
        val detected = planner.detect("list files in downloads")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LIST_FILES)
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Download")
    }

    @Test
    fun `detects list documents`() {
        val detected = planner.detect("show files in documents")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LIST_FILES)
        assertThat(detected.directory).isEqualTo("/storage/emulated/0/Documents")
    }

    @Test
    fun `plans list files workflow`() {
        val detected = planner.detect("list files in downloads")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .contains("ls -lah")
    }

    // ── Open File ───────────────────────────────────────────────────────────
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
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("openfile:")
    }

    // ── Share File ──────────────────────────────────────────────────────────
    @Test
    fun `detects share file`() {
        val detected = planner.detect("share /storage/emulated/0/Download/image.jpg")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.SHARE_FILE)
        assertThat(detected.directory).contains("image.jpg")
    }

    @Test
    fun `plans share file workflow`() {
        val detected = planner.detect("share /sdcard/Documents/file.pdf")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("sharefile:")
    }

    // ── Launch App ──────────────────────────────────────────────────────────
    @Test
    fun `detects launch app`() {
        val detected = planner.detect("open settings")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LAUNCH_APP)
        assertThat(detected.packageName).isEqualTo("com.android.settings")
    }

    @Test
    fun `detects launch calculator`() {
        val detected = planner.detect("open calculator")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LAUNCH_APP)
    }

    @Test
    fun `detects launch YouTube`() {
        val detected = planner.detect("launch YouTube")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LAUNCH_APP)
    }

    @Test
    fun `detects launch settings`() {
        val detected = planner.detect("open settings")
        assertThat(detected).isNotNull()
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.LAUNCH_APP)
    }

    @Test
    fun `plans launch app workflow`() {
        val detected = planner.detect("open settings")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("open:")
    }

    // ── Call / Dial ─────────────────────────────────────────────────────────
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
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("call:")
    }

    @Test
    fun `plans dial workflow`() {
        val detected = planner.detect("dial 9876543210")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("dial:")
    }

    // ── SMS ─────────────────────────────────────────────────────────────────
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
        assertThat(workflow!!.steps.first().command.command)
            .startsWith("sms:")
    }

    // ── Device Info ─────────────────────────────────────────────────────────
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
        assertThat(workflow!!.steps.first().command.command)
            .contains("dumpsys battery")
    }

    @Test
    fun `plans storage info workflow`() {
        val detected = planner.detect("check storage space")
        val workflow = planner.plan(detected!!)
        assertThat(workflow).isNotNull()
        assertThat(workflow!!.steps.first().command.command)
            .contains("df -h")
    }

    // ── Edge Cases ─────────────────────────────────────────────────────────
    @Test
    fun `returns null for empty input`() {
        assertThat(planner.detect("")).isNull()
    }

    @Test
    fun `returns null for gibberish`() {
        assertThat(planner.detect("asdfghjkl")).isNull()
    }

    @Test
    fun `detects multi-intent with highest confidence`() {
        // This could match both WHATSAPP and DEVICE_INFO
        val detected = planner.detect("open WhatsApp and check battery")
        assertThat(detected).isNotNull()
        // WhatsApp should have higher confidence
        assertThat(detected!!.intent).isEqualTo(WorkflowPlanner.Intent.WHATSAPP_MESSAGE)
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
        // Should either be null or UNKNOWN with low confidence
        if (detected != null) {
            assertThat(detected.confidence).isLessThan(0.75f)
        }
    }

    // ── Workflow Tag Verification ──────────────────────────────────────────
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
    }
}
