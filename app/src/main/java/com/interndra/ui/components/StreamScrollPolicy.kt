package com.interndra.ui.components

/**
 * Pure, side-effect-free policy for chat follow-scrolling and streaming reveals.
 *
 * All decisions are computed from plain integers (pixel offsets / item counts)
 * so they can be unit tested on the JVM without an emulator. The composable
 * layer just measures the layout, asks this policy what to do, and executes it.
 */
object StreamScrollPolicy {

    /**
     * Whether the newest content is still in view.
     *
     * @param itemBottomOffsetPx the bottom edge of the LAST visible list item,
     *   relative to the top of the viewport (e.g. from
     *   `visibleItemsInfo.last().offset + .size`). A value >= the viewport
     *   height means content extends past the bottom edge, so new streamed
     *   lines are visible.
     * @param slackPx how much empty space below the last item is tolerated
     *   while still considering the user "at the bottom".
     */
    fun shouldFollowViewport(itemBottomOffsetPx: Int, viewportHeightPx: Int, slackPx: Int = 160): Boolean =
        viewportHeightPx > 0 && itemBottomOffsetPx >= viewportHeightPx - slackPx

    /**
     * The LazyList scroll offset that pins an item's bottom edge to the viewport
     * bottom. `scrollToItem(index, offset)` places the item's start `offset`
     * pixels below the viewport top, so an item taller than the viewport is
     * bottom-pinned with offset = itemHeight - viewportHeight. Shorter items
     * clamp to 0 (top-aligned, fully visible). Zero-height items (not yet
     * measured) are safe and return 0.
     */
    fun pinOffset(itemHeightPx: Int, viewportHeightPx: Int): Int {
        val maxOffset = (itemHeightPx - 1).coerceAtLeast(0)
        return (itemHeightPx - viewportHeightPx).coerceIn(0, maxOffset)
    }

    /**
     * A freshly appended user message should animate in and re-arm following.
     * Stream chunks of an already-anchored AI message must not re-animate.
     */
    fun isFreshUserMessage(previousId: Long?, messageId: Long, isUser: Boolean): Boolean =
        isUser && messageId != previousId
}
