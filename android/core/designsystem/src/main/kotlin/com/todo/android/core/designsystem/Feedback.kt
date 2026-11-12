package com.todo.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** 首屏骨架：3 条任务 + 1 块进度卡，1.2 秒脉冲；禁止全屏 spinner。 */
@Composable
fun SkeletonScreen(modifier: Modifier = Modifier) {
    val colors = TodoTheme.colors
    val transition = rememberInfiniteTransition(label = "skeleton")
    val target = if (TodoTheme.reduceMotion) 1f else 0.45f
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = target,
        animationSpec = infiniteRepeatable(
            animation = tween(Tokens.Motion.SKELETON_PULSE),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .alpha(alpha),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(colors.border2),
        )
        Spacer(Modifier.height(14.dp))
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.md))
                    .background(colors.card)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(Tokens.Radius.full))
                        .background(colors.border2),
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(160.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(Tokens.Radius.sm))
                            .background(colors.border2),
                    )
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(Tokens.Radius.sm))
                            .background(colors.border2),
                    )
                }
            }
        }
    }
}

/** 底部轻提示：胶囊样式，时长由调用方控制；可选一枚可点击的操作（如「撤销」）。 */
@Composable
fun TodoToast(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = TodoTheme.colors
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.full))
                    .background(colors.text.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = Tokens.Type.chip,
                    color = colors.card,
                )
                if (actionLabel != null && onAction != null) {
                    Text(
                        text = actionLabel,
                        style = Tokens.Type.chip.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = colors.card,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.full))
                            .clickable(onClick = onAction)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
