package com.todo.android.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.DistributionBar
import com.todo.android.core.designsystem.StatNumberCard
import com.todo.android.core.designsystem.StatsRing
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TrendChart
import com.todo.android.core.designsystem.parseHex

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors
    val now = TimeText.now()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.allSelected) "全部统计" else "今日统计",
            style = Tokens.Type.pageTitle,
            color = colors.text,
        )
        Text(
            if (state.allSelected) "全部任务 · ${TimeText.dateLine(now)}" else "今天 · ${TimeText.dateLine(now)}",
            style = Tokens.Type.meta,
            color = colors.text3,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("今天" to !state.allSelected, "全部" to state.allSelected).forEach { (label, active) ->
                Text(
                    label,
                    style = Tokens.Type.chip,
                    color = if (active) colors.onPrimary else colors.text2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.full))
                        .background(if (active) colors.primary else colors.card)
                        .clickable { viewModel.selectScope(label == "全部") }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(Brush.linearGradient(listOf(colors.primary50, colors.success50)))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatsRing(
                percent = state.percent,
                completed = state.completed,
                total = state.scopedTotal,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Legend("进行中", state.active, colors.primary)
                Legend("已完成", state.completed, colors.success)
                Legend("已逾期", state.overdue, colors.danger)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatNumberCard("已完成", "${state.completed}", colors.success, Modifier.weight(1f))
            StatNumberCard("进行中", "${state.active}", colors.primary, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatNumberCard("已逾期", "${state.overdue}", colors.danger, Modifier.weight(1f))
            StatNumberCard("AI 创建", "${state.aiCreated}", colors.purple, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        StatsCard(title = "优先级分布 · 共 ${state.scopedTotal} 项") {
            DistributionBar("高", state.high, state.scopedTotal, colors.danger)
            DistributionBar("中", state.mid, state.scopedTotal, colors.warning)
            DistributionBar("低", state.low, state.scopedTotal, colors.primary)
        }

        Spacer(Modifier.height(12.dp))
        StatsCard(title = "清单分布") {
            if (state.listDistribution.isEmpty()) {
                Text("暂无数据", style = Tokens.Type.chip, color = colors.text3)
            }
            state.listDistribution.forEach { item ->
                DistributionBar(
                    label = item.name.take(2),
                    value = item.count,
                    total = state.scopedTotal,
                    color = parseHex(item.color, colors.primary),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        StatsCard(title = "最近 7 天完成 · 日均 ${state.averagePerDay} 项") {
            TrendChart(values = state.lastSevenDays, todayIndex = state.todayIndex)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("6天前", "3天前", "今天").forEach {
                    Text(it, style = Tokens.Type.meta, color = colors.text3)
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun Legend(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    val colors = TodoTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth(0.14f)
                .clip(RoundedCornerShape(Tokens.Radius.full))
                .background(color),
        )
        Text("$label $value 项", style = Tokens.Type.chip, color = colors.text2)
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .padding(14.dp),
    ) {
        Text(title, style = Tokens.Type.section, color = colors.text2)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
