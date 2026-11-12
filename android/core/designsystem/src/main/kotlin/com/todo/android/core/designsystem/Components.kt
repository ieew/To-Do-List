package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 圆形复选框：20dp 视觉、48dp 触控区。 */
@Composable
fun TodoCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = Tokens.Size.checkbox,
    contentDescription: String? = null,
) {
    val colors = TodoTheme.colors
    Box(
        modifier = modifier
            .size(Tokens.Size.touch)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (checked) colors.success else colors.card)
                .border(1.5.dp, if (checked) colors.success else colors.dashed, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = contentDescription ?: "已完成",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        }
    }
}

@Composable
fun TagChip(
    text: String,
    bg: String?,
    fg: String?,
    modifier: Modifier = Modifier,
) {
    val (background, foreground) = TodoTheme.colors.tagColors(bg, fg)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.full))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(foreground),
        )
        Text(
            text = text,
            style = Tokens.Type.chip,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PriorityDot(priority: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Tokens.Size.priorityDot)
            .clip(CircleShape)
            .background(TodoTheme.colors.priorityColor(priority)),
    )
}

@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    val colors = TodoTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.full))
            .background(Brush.linearGradient(listOf(colors.aiGradientStart, colors.aiGradientEnd)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text = "✦ AI", style = Tokens.Type.badge, color = colors.aiFg)
    }
}

@Composable
fun SectionTitle(
    text: String,
    count: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = Tokens.Type.section, color = colors.text2)
        Spacer(Modifier.weight(1f))
        if (count != null) {
            Text(text = count, style = Tokens.Type.chip, color = colors.text3)
        }
    }
}

@Composable
fun GradientProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    val safe = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Tokens.Size.progressHeight)
            .clip(RoundedCornerShape(Tokens.Radius.full))
            .background(colors.track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(Tokens.Size.progressHeight)
                .clip(RoundedCornerShape(Tokens.Radius.full))
                .background(Brush.horizontalGradient(listOf(colors.primary, colors.success))),
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = TodoTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = Tokens.Type.body, color = colors.text2)
        if (hint != null) {
            Spacer(Modifier.height(Tokens.Space.xs))
            Text(text = hint, style = Tokens.Type.meta, color = colors.text3)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Tokens.Space.md))
            Text(
                text = actionLabel,
                style = Tokens.Type.chip.copy(fontWeight = FontWeight.SemiBold),
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .clickable(onClick = onAction)
                    .padding(horizontal = Tokens.Space.md, vertical = Tokens.Space.sm),
            )
        }
    }
}

@Composable
fun CardContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = TodoTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.md)),
    ) {
        content()
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(1.dp)
            .height(16.dp)
            .background(TodoTheme.colors.border),
    )
}
