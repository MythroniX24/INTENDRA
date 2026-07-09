package com.interndra.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interndra.ui.theme.*

// ═══════════════════════════════════════════════════════════════════════════════
// DashboardCard — Reusable card wrapper for all dashboard screens
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * A themed card wrapper with consistent styling for all dashboard screens.
 * Provides a consistent surface color, shape, and optional padding.
 */
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    verticalSpacing: androidx.compose.foundation.layout.Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardContent = @Composable {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = verticalSpacing,
            content = content
        )
    }

    if (onClick != null) {
        // Press animation: scale down slightly on press for tactile feedback
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "card_press"
        )
        Card(
            modifier = modifier
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            cardContent()
        }
    } else {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            cardContent()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DashboardCard with header — Convenience variant with title row built-in
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun DashboardCard(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header row
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    color = TerminalWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                action?.invoke()
            }
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// StatCard — Display a statistic with emoji, value, and label
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun StatCard(
    emoji: String,
    value: String,
    label: String,
    accentColor: Color = Accent,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color = accentColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            Text(
                label,
                color = TerminalWhite.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SearchBar — Unified search input with debounce support
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search…",
    accentColor: Color = Accent,
    onSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val kbController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                placeholder,
                color = TerminalWhite.copy(alpha = 0.4f)
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        "Clear",
                        tint = TerminalWhite.copy(alpha = 0.6f)
                    )
                }
            }
        },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                kbController?.hide()
                onSearch?.invoke()
            }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = SurfaceLight.copy(alpha = 0.5f),
            focusedTextColor = TerminalWhite,
            unfocusedTextColor = TerminalWhite,
            cursorColor = accentColor
        ),
        shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 14.sp,
            color = TerminalWhite
        )
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// FilterChipRow — Horizontal scrollable filter chips
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun FilterChipRow(
    items: List<FilterChipItem>,
    selectedItem: String?,
    onItemSelected: (String?) -> Unit,
    accentColor: Color = Accent,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        item {
            FilterChip(
                selected = selectedItem == null,
                onClick = { onItemSelected(null) },
                label = { Text("All", fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accentColor,
                    selectedLabelColor = Background800,
                    labelColor = TerminalWhite.copy(alpha = 0.7f)
                )
            )
        }
        items(items.size) { idx ->
            val item = items[idx]
            FilterChip(
                selected = selectedItem == item.id,
                onClick = {
                    onItemSelected(if (selectedItem == item.id) null else item.id)
                },
                label = { Text(item.label, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accentColor,
                    selectedLabelColor = Background800,
                    labelColor = TerminalWhite.copy(alpha = 0.7f)
                )
            )
        }
    }
}

data class FilterChipItem(
    val id: String,
    val label: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// EmptyState — Unified empty state for all dashboard screens
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(emoji, fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                color = TerminalWhite.copy(alpha = 0.5f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = TerminalWhite.copy(alpha = 0.35f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SectionHeader — Section label with optional count
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SectionHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title,
            color = TerminalWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (count != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceLight.copy(alpha = 0.3f)
            ) {
                Text(
                    count.toString(),
                    color = TerminalWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TagChip — Small colored tag chip for metadata
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TagChip(
    text: String,
    color: Color = Accent,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
