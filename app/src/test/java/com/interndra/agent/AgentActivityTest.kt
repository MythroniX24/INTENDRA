package com.interndra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the AgentActivity event model. */
class AgentActivityTest {

    @Test
    fun `tool start carries tool, description and command preview`() {
        val evt = AgentActivity.ToolStart("terminal", "Install python", "pkg install python")

        assertEquals("terminal", evt.tool)
        assertEquals("Install python", evt.description)
        assertEquals("pkg install python", evt.command)
        assertTrue(evt.timestampMs > 0)
    }

    @Test
    fun `tool result carries success flag and duration`() {
        val ok = AgentActivity.ToolResult("terminal", true, "installed", 1200L)
        val bad = AgentActivity.ToolResult("terminal", false, "permission denied", 300L)

        assertTrue(ok.success)
        assertFalse(bad.success)
        assertEquals(1200L, ok.durationMs)
    }

    @Test
    fun `search and reading events are user-safe summaries`() {
        val s = AgentActivity.Search("latest kotlin version")
        val r = AgentActivity.Reading("Reading 3 webpages")

        assertTrue(s.query.contains("kotlin"))
        assertEquals("Reading 3 webpages", r.message)
    }

    @Test
    fun `verification records pass and fail`() {
        val pass = AgentActivity.Verification("Build passed", success = true)
        val fail = AgentActivity.Verification("Build failed", success = false)

        assertTrue(pass.success)
        assertFalse(fail.success)
    }

    @Test
    fun `completed is the terminal success event`() {
        assertEquals(0L, AgentActivity.Completed.timestampMs)
    }

    @Test
    fun `timestamps are monotonic within a run`() {
        var prev = 0L
        for (i in 1..50) {
            val evt = AgentActivity.Thinking("step $i")
            assertTrue(evt.timestampMs >= prev)
            prev = evt.timestampMs
        }
    }
}
