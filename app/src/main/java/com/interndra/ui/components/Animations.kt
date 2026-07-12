package com.interndra.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interndra.ui.theme.LocalInterndraColors

// ═══════════════════════════════════════════════════════════════════════════════
// Skeleton — Shimmer loading placeholder
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Animated shimmer placeholder for content that hasn't loaded yet.
 * Shows a moving gradient that sweeps left to right.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp)
) {
    val colors = LocalInterndraColors.current
    val shimmerColors = listOf(
        colors.surfaceInteractive.copy(alpha = 0.08f),
        colors.surfaceInteractive.copy(alpha = 0.2f),
        colors.surfaceInteractive.copy(alpha = 0.08f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f)
    )

    val mod = modifier
        .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
        .height(height)
        .clip(shape)
        .background(brush)

    Box(mod)
}

/**
 * Skeleton card — mimics DashboardCard layout while loading.
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3
) {
    val colors = LocalInterndraColors.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title line
            Skeleton(width = 120.dp, height = 14.dp)
            // Content lines
            repeat(lines) {
                Skeleton(height = 12.dp)
            }
        }
    }
}

/**
 * Skeleton stat card — mimics StatCard while loading.
 */
@Composable
fun SkeletonStatCard(modifier: Modifier = Modifier) {
    val colors = LocalInterndraColors.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Skeleton(width = 24.dp, height = 24.dp, shape = RoundedCornerShape(4.dp))
            Skeleton(width = 40.dp, height = 20.dp)
            Skeleton(width = 48.dp, height = 10.dp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Message entry animation specs
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Creates a slide-up + fade-in animation spec for chat messages.
 * Messages enter from below with a smooth fade and scale.
 */
fun messageEnterTransition(): EnterTransition {
    return slideInVertically(
        initialOffsetY = { it / 4 },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
}

/**
 * Creates a slide-out + fade-out animation spec for chat messages.
 */
fun messageExitTransition(): ExitTransition {
    return slideOutVertically(
        targetOffsetY = { it / 4 },
        animationSpec = tween(200)
    ) + fadeOut(animationSpec = tween(150))
}

/**
 * Animated visibility for a single message bubble with staggered delay.
 * Each message in a group gets a slight delay so they appear one after another.
 *
 * @param index the message's position in the group (0 = first)
 * @param visible whether this message is visible
 * @param content composable content
 */
@Composable
fun AnimatedMessage(
    index: Int = 0,
    visible: Boolean = true,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val delayMs = (index * 50).coerceAtMost(200)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = tween(300, delayMs, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(300, delayMs)),
        exit = slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(200)
        ) + fadeOut(animationSpec = tween(150)),
        content = content
    )
}

/**
 * Scale + fade entrance for UI cards and elements.
 */
@Composable
fun AnimatedCard(
    visible: Boolean = true,
    delay: Int = 0,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.95f,
            animationSpec = tween(250, delay, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(250, delay)),
        exit = scaleOut(
            targetScale = 0.95f,
            animationSpec = tween(150)
        ) + fadeOut(animationSpec = tween(150)),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// TabTransition — AnimatedContent spec for page transitions
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Creates an [AnimatedContent] transition spec with combined slide + fade.
 * Slides content in from the right/left and fades.
 */
fun tabTransition(targetIndex: Int, currentIndex: Int): ContentTransform {
    val offset = if (targetIndex > currentIndex) 150 else -150
    return slideInHorizontally(initialOffsetX = { offset }) + fadeIn(animationSpec = tween(250)) togetherWith
            slideOutHorizontally(targetOffsetX = { -offset }) + fadeOut(animationSpec = tween(200))
}

/**
 * Simple fade transition for dashboard content.
 */
val fadeTransition: ContentTransform = fadeIn(animationSpec = tween(200)) togetherWith
        fadeOut(animationSpec = tween(150))

// ═══════════════════════════════════════════════════════════════════════════════
// PressAnimation — Scale-down effect on press
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Applies a scale animation to a composable: scales down to 0.97 when pressed,
 * returns to 1.0 when released. Use with [Modifier.pressAnimation] on clickable
 * items for a subtle tactile feedback effect.
 */
@Composable
fun Modifier.pressAnimation(
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press_scale"
    )
    return this.then(
        Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    )
}

/**
 * Shimmer border effect — draws an animated gradient border.
 * Useful for loading/processing states.
 */
@Composable
fun ShimmerBorder(
    modifier: Modifier = Modifier,
    isLoading: Boolean = true
) {
    if (!isLoading) return

    val colors = LocalInterndraColors.current
    val transition = rememberInfiniteTransition(label = "shimmer_border")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            RepeatMode.Restart
        ), label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.sweepGradient(
                    colors = listOf(
                        colors.terminalWhite.copy(alpha = 0f),
                        colors.terminalWhite.copy(alpha = 0.15f),
                        colors.terminalWhite.copy(alpha = 0f)
                    )
                ),
                RoundedCornerShape(1.dp)
            )
    )
}
