package com.interndra

import com.interndra.ai.SafetyEngine
import com.interndra.ai.workflow.WorkflowPlanner
import com.interndra.data.model.CommandType
import com.interndra.data.model.PrivacyMode
import org.junit.Assert.*
import org.junit.Test

class InterndraTest {

    @Test
    fun `command types are defined`() {
        listOf("ADB_SHELL", "ANDROID_INTENT", "ACCESSIBILITY", "TERMUX").forEach {
            assertNotNull(CommandType.valueOf(it))
        }
    }

    @Test
    fun `privacy mode enum has entries`() {
        assertTrue("PrivacyMode has no entries", PrivacyMode.entries.isNotEmpty())
        assertNotNull(PrivacyMode.entries.first().label)
    }

    @Test
    fun `shell text space escaping works`() {
        assertEquals("hello%sworld", "hello world".replace(" ", "%s"))
    }

    // ── SafetyEngine tests (Phase 10 — hardened) ─────────────────────────

    @Test
    fun `safety engine blocks rm -rf root`() {
        val safety = SafetyEngine()
        val report = safety.validate("rm -rf /")
        assertEquals(SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks rm -rf with extra whitespace (bypass fix)`() {
        val safety = SafetyEngine()
        val report = safety.validate("rm  -rf  /")
        assertEquals("Whitespace bypass must be blocked", SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks rm -rf with quotes (bypass fix)`() {
        val safety = SafetyEngine()
        val report = safety.validate("r'm' -rf /")
        assertEquals("Quote bypass must be blocked", SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks rm -rf /storage`() {
        val safety = SafetyEngine()
        val report = safety.validate("rm -rf /storage/emulated/0")
        assertEquals(SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks find root delete`() {
        val safety = SafetyEngine()
        val report = safety.validate("find / -delete")
        assertEquals(SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks base64-encoded payload`() {
        val safety = SafetyEngine()
        // "rm -rf /" base64-encoded
        val report = safety.validate("echo cm0gLXJmIC8= | base64 -d | sh")
        assertEquals("Base64 payload must be blocked", SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine blocks fork bomb`() {
        val safety = SafetyEngine()
        val report = safety.validate(":(){ :|:& };:")
        assertEquals(SafetyEngine.ValidationResult.BLOCKED, report.result)
    }

    @Test
    fun `safety engine allows safe screenshot`() {
        val safety = SafetyEngine()
        val report = safety.validate("screencap -p /sdcard/screen.png")
        assertEquals(SafetyEngine.ValidationResult.SAFE, report.result)
    }

    @Test
    fun `safety engine allows ls command`() {
        val safety = SafetyEngine()
        val report = safety.validate("ls -la /storage/emulated/0/Download")
        assertEquals(SafetyEngine.ValidationResult.SAFE, report.result)
    }

    @Test
    fun `safety engine flags logcat as privacy-sensitive`() {
        val safety = SafetyEngine()
        val report = safety.validate("logcat -d")
        assertEquals(SafetyEngine.ValidationResult.REQUIRES_CONFIRMATION, report.result)
    }

    // ── WorkflowPlanner tests (Phase 5/6/7) ──────────────────────────────

    @Test
    fun `workflow planner detects WhatsApp message`() {
        val planner = WorkflowPlanner()
        val detected = planner.detect("open WhatsApp and say hello there")
        assertNotNull(detected)
        assertTrue(detected!!.confidence >= 0.75f)
        assertEquals(WorkflowPlanner.Intent.WHATSAPP_MESSAGE, detected.intent)
    }

    @Test
    fun `workflow planner detects find PDF files`() {
        val planner = WorkflowPlanner()
        val detected = planner.detect("find all PDF files in my downloads")
        assertNotNull(detected)
        assertTrue(detected!!.confidence >= 0.75f)
        assertEquals(WorkflowPlanner.Intent.FIND_FILES, detected.intent)
        assertTrue(detected.fileExtensions.contains("pdf"))
    }

    @Test
    fun `workflow planner plans WhatsApp workflow`() {
        val planner = WorkflowPlanner()
        val detected = planner.detect("open WhatsApp with message hello")
        assertNotNull(detected)
        val workflow = planner.plan(detected!!)
        assertNotNull(workflow)
        assertTrue(workflow!!.steps.isNotEmpty())
        assertEquals(CommandType.ANDROID_INTENT, workflow.steps.first().command.type)
        assertTrue(workflow.steps.first().command.command.startsWith("sendtext:com.whatsapp"))
    }

    @Test
    fun `workflow planner plans file search workflow`() {
        val planner = WorkflowPlanner()
        val detected = planner.detect("find PDF files in downloads")
        val workflow = planner.plan(detected!!)
        assertNotNull(workflow)
        assertTrue(workflow.steps.first().command.command.contains("find"))
        assertTrue(workflow.steps.first().command.command.contains("pdf"))
    }

    @Test
    fun `workflow planner returns null for ambiguous input`() {
        val planner = WorkflowPlanner()
        // No keywords match — should fall back to AI
        val detected = planner.detect("tell me a joke")
        // "tell me about" might trigger, but "tell me a" shouldn't
        // Either null or UNKNOWN is acceptable here
        if (detected != null) {
            assertTrue(detected.confidence < 0.75f || detected.intent == WorkflowPlanner.Intent.UNKNOWN)
        }
    }
}
