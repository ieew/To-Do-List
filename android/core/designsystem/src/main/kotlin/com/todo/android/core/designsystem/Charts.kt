package com.todo.android.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** 完成率环：96dp、stroke 9dp、主色→成功渐变。 */
@Composable
fun StatsRing(
    percent: Int,
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    Box(modifier = modifier.size(Tokens.Size.statRing), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(Tokens.Size.statRing)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val sweep = 360f * (percent.coerceIn(0, 100) / 100f)
            rotate(-90f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(colors.primary, colors.success, colors.primary)),
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = Tokens.Type.number, color = colors.text)
            Text("$completed / $total", style = Tokens.Type.meta, color = colors.text3)
        }
    }
}

/** 分布条：左侧标签、中间轨道、右侧数值。 */
@Composable
fun DistributionBar(
    label: String,
    value: Int,
    total: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    val ratio = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = Tokens.Type.chip,
            color = colors.text2,
            modifier = Modifier.width(28.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(Tokens.Radius.full))
                .background(if (colors.isDark) colors.border2 else androidx.compose.ui.graphics.Color(0xFFF1F5F9)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Tokens.Radius.full))
                    .background(color),
            )
        }
        Text(
            text = "$value",
            style = Tokens.Type.chip.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = colors.text2,
            modifier = Modifier.width(28.dp),
        )
    }
}

/** 最近 7 天完成柱状图：高 96dp，今日用成功色。 */
@Composable
fun TrendChart(
    values: List<Float>,
    todayIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEachIndexed { index, value ->
            val ratio = (value / max).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(ratio)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(
                        if (index == todayIndex) {
                            Brush.verticalGradient(listOf(colors.success, colors.success50))
                        } else {
                            Brush.verticalGradient(listOf(colors.primary, colors.primary50))
                        },
                    ),
            )
        }
    }
}

@Composable
fun StatNumberCard(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = Tokens.Type.chip, color = colors.text3)
        Spacer(Modifier.height(2.dp))
        Text(value, style = Tokens.Type.number, color = color)
    }
}
