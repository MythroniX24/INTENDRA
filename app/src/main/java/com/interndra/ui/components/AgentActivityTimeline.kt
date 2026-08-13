package com.interndra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.agent.AgentActivity
import com.interndra.agent.AgentState
import com.interndra.ui.theme.LocalInterndraColors
import com.interndra.ui.theme.TerminalGreen
import com.interndra.ui.theme.TerminalRed
import com.interndra.ui.theme.TerminalWhite
import com.interndra.ui.theme.TerminalYellow

/**
 * AgentActivityTimeline — a live, Claude-style "Working…" panel for the chat.
 *
 * Shows user-safe activity events as the agent works (thinking, searching,
 * reading, executing tools with command previews, verifying). Auto-expands
 * while the agent is active, collapses to a one-line status when done.
 *
 * The panel NEVER shows private chain-of-thought — only the high-level
 * [AgentActivity] summaries emitted by [com.interndra.agent.AgentOrchestrator].
 */
@Composable
fun AgentActivityTimeline(
    activities: List<AgentActivity>,
    state: AgentState,
    modifier: Modifier = Modifier
) {
    val colors = LocalInterndraColors.current
    if (activities.isEmpty() && state == AgentState.IDLE) return

    val active = state.isActive()
    var expanded by remember(state) { mutableStateOf(active) }
    // Keep it expanded while work is running, unless the user collapsed it.
    if (active && !expanded) expanded = true

    val headerColor by animateColorAsState(
        targetValue = when {
            state == AgentState.FAILED -> TerminalRed
            state == AgentState.COMPLETED -> TerminalGreen
            else -> colors.accent
        },
        animationSpec = tween(300),
        label = "agentHeaderColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.codeBlockBg.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.codeBlockBorder.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── Header row ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (active) {
                    val infiniteTransition = rememberInfiniteTransition(label = "agent_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        0.3f, 1.0f,
                        infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "agent_pulse"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(headerColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "✦ Working · ${state.label}",
                        color = headerColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    val icon = when (state) {
                        AgentState.COMPLETED -> "✓"
                        AgentState.FAILED -> "✕"
                        else -> "✦"
                    }
                    Text(
                        "$icon ${state.label}",
                        color = headerColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (activities.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(headerColor.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${activities.size}",
                            color = headerColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Hide" else "Details",
                    color = colors.accent.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse activity" else "Expand activity",
                    tint = TerminalWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // ── Expanded activity list ───────────────────────────────────
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    activities.forEachIndexed { i, act ->
                        AgentActivityRow(act)
                        if (i < activities.size - 1) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentActivityRow(activity: AgentActivity) {
    val colors = LocalInterndraColors.current
    val (icon, tint) = when (activity) {
        is AgentActivity.Thinking -> Icons.Default.Psychology to colors.accent
        is AgentActivity.Planning -> Icons.Default.Psychology to colors.accent
        is AgentActivity.Search -> Icons.Default.Language to colors.accent
        is AgentActivity.Reading -> Icons.Default.MenuBook to colors.accent
        is AgentActivity.ToolStart -> Icons.Default.Terminal to TerminalYellow
        is AgentActivity.ToolResult -> if (activity.success) Icons.Default.Verified to TerminalGreen else Icons.Default.Warning to TerminalRed
        is AgentActivity.Verification -> Icons.Default.Verified to (if (activity.success) TerminalGreen else TerminalRed)
        is AgentActivity.Error -> Icons.Default.Warning to TerminalRed
        is AgentActivity.Completed -> Icons.Default.Verified to TerminalGreen
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = activityMessage(activity),
                color = TerminalWhite.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Duration for finished tool calls.
            if (activity is AgentActivity.ToolResult && activity.durationMs > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(activity.durationMs / 1000.0).let { "%.1f".format(it) }}s",
                    color = TerminalWhite.copy(alpha = 0.35f),
                    fontSize = 10.sp
                )
            }
        }
        // Command preview for running tools.
        if (activity is AgentActivity.ToolStart && activity.command.isNotBlank()) {
            Text(
                text = "$ ${activity.command.take(140)}",
                color = TerminalWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )
        }
    }
}

private fun activityMessage(activity: AgentActivity): String = when (activity) {
    is AgentActivity.Thinking -> activity.message
    is AgentActivity.Planning -> activity.message
    is AgentActivity.ToolStart -> activity.description
    is AgentActivity.ToolResult ->
        if (activity.success) "${activity.tool}: ${activity.summary}"
        else "${activity.tool} failed: ${activity.summary}"
    is AgentActivity.Search -> "${activity.message} \"${activity.query.take(60)}\""
    is AgentActivity.Reading -> activity.message
    is AgentActivity.Verification ->
        if (activity.success) "✓ ${activity.message}" else "✕ ${activity.message}"
    is AgentActivity.Error -> activity.message
    is AgentActivity.Completed -> "Task completed"
}
