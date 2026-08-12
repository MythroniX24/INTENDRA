package com.interndra.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure follow-scroll / bottom-pin policy. */
class StreamScrollPolicyTest {

    // ── shouldFollowViewport ─────────────────────────────────────────────

    @Test
    fun `follows when item bottom is near the viewport bottom`() {
        // Bottom edge exactly at the viewport bottom.
        assertTrue(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 2000, viewportHeightPx = 2000))
        // Within the 160px slack.
        assertTrue(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 1900, viewportHeightPx = 2000))
        // Content extends past the bottom edge — newest lines are visible.
        assertTrue(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 2400, viewportHeightPx = 2000))
    }

    @Test
    fun `does not follow when the user scrolled up`() {
        assertFalse(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 1200, viewportHeightPx = 2000))
        assertFalse(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 0, viewportHeightPx = 2000))
        // Just past the slack boundary.
        assertFalse(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 1839, viewportHeightPx = 2000))
    }

    @Test
    fun `empty viewport never follows`() {
        assertFalse(StreamScrollPolicy.shouldFollowViewport(itemBottomOffsetPx = 500, viewportHeightPx = 0))
    }

    // ── pinOffset ─────────────────────────────────────────────────────────

    @Test
    fun `pin offset pins tall items to the viewport bottom`() {
        assertEquals(1000, StreamScrollPolicy.pinOffset(itemHeightPx = 3000, viewportHeightPx = 2000))
    }

    @Test
    fun `pin offset clamps to zero for items shorter than the viewport`() {
        assertEquals(0, StreamScrollPolicy.pinOffset(itemHeightPx = 500, viewportHeightPx = 2000))
        assertEquals(0, StreamScrollPolicy.pinOffset(itemHeightPx = 2000, viewportHeightPx = 2000))
    }

    @Test
    fun `pin offset is safe for zero-height (not yet measured) items`() {
        assertEquals(0, StreamScrollPolicy.pinOffset(itemHeightPx = 0, viewportHeightPx = 2000))
    }

    @Test
    fun `pin offset never exceeds the item height`() {
        assertTrue(StreamScrollPolicy.pinOffset(100, 50) < 100)
        assertTrue(StreamScrollPolicy.pinOffset(100, 10000) >= 0)
    }

    // ── isFreshUserMessage ────────────────────────────────────────────────

    @Test
    fun `fresh user message is detected exactly once`() {
        assertTrue(StreamScrollPolicy.isFreshUserMessage(previousId = 5, messageId = 6, isUser = true))
        assertFalse(StreamScrollPolicy.isFreshUserMessage(previousId = 6, messageId = 6, isUser = true))
        assertFalse(StreamScrollPolicy.isFreshUserMessage(previousId = 5, messageId = 6, isUser = false))
    }
}
