package com.todo.android.feature.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.EmptyState
import com.todo.android.core.designsystem.SectionTitle
import com.todo.android.core.designsystem.TaskRow
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens
import java.time.LocalDate

@Composable
fun CalendarScreen(
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors
    val nowMs = TimeText.now()
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .nestedScroll(
                rememberMonthOverscroll(
                    expanded = state.expanded,
                    listState = listState,
                    onExpand = viewModel::toggleExpanded,
                    onCollapse = viewModel::collapse,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = if (state.expanded) "上一个月" else "上一周",
                tint = colors.text2,
                modifier = Modifier.size(24.dp).clickable {
                    if (state.expanded) viewModel.shiftMonth(-1) else viewModel.shiftWeek(-7)
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .clickable { viewModel.toggleExpanded() }
                    .padding(vertical = 2.dp, horizontal = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (state.expanded) state.monthLabel else "${state.selectedDate.monthValue}月",
                        style = Tokens.Type.pageTitle,
                        color = colors.text,
                    )
                    Icon(
                        if (state.expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (state.expanded) "收起月视图" else "展开月视图",
                        tint = colors.text3,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (!state.expanded) {
                    Text("${state.selectedDate.year}", style = Tokens.Type.meta, color = colors.text3)
                }
            }
            Spacer(Modifier.weight(1f))
            ScopePill("今天", !state.allSelected) { viewModel.selectScope(false) }
            Spacer(Modifier.width(6.dp))
            ScopePill("全部", state.allSelected) { viewModel.selectScope(true) }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = if (state.expanded) "下一个月" else "下一周",
                tint = colors.text2,
                modifier = Modifier.size(24.dp).clickable {
                    if (state.expanded) viewModel.shiftMonth(1) else viewModel.shiftWeek(7)
                },
            )
        }

        AnimatedVisibility(
            visible = state.expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            MonthSection(state = state, onSelect = viewModel::selectFromMonth)
        }

        AnimatedVisibility(visible = !state.expanded, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.days.forEach { day ->
                    DayCell(
                        day = day,
                        selected = day.date == state.selectedDate,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.select(day.date) }
                }
            }
        }

        if (state.overdueCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(colors.warning50)
                    .border(1.dp, colors.warning, RoundedCornerShape(Tokens.Radius.sm))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "${state.overdueCount} 项任务已逾期，建议尽快处理",
                    style = Tokens.Type.chip,
                    color = colors.warningFg,
                )
            }
        }

        SectionTitle(
            text = TimeText.dayTitle(state.selectedDate),
            count = "${state.tasks.size} 项",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            if (state.tasks.isEmpty()) {
                // 空态也放进列表里：这样下拉手势仍能拿到嵌套滚动事件。
                item {
                    EmptyState(title = "这一天没有其他任务了")
                }
            }
            items(state.tasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    tagsById = emptyMap(),
                    nowMs = nowMs,
                    onToggle = { },
                    onClick = { onOpenDetail(task.id) },
                )
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = TimeText.weekdayShort(day.date),
            style = Tokens.Type.meta,
            color = if (selected) colors.onPrimary else colors.text3,
        )
        Text(
            text = "${day.date.dayOfMonth}",
            style = Tokens.Type.section.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) colors.onPrimary else colors.text,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        day.hasOverdue -> if (selected) colors.onPrimary else colors.danger
                        day.count > 0 -> if (selected) colors.onPrimary else colors.primary
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    },
                ),
        )
    }
}

@Composable
private fun ScopePill(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Text(
        text = label,
        style = Tokens.Type.chip,
        color = if (active) colors.onPrimary else colors.text2,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.full))
            .background(if (active) colors.primary else colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MonthSection(state: CalendarUiState, onSelect: (LocalDate) -> Unit) {
    val colors = TodoTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    style = Tokens.Type.meta,
                    color = colors.text3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.monthWeeks.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    week.forEach { cell ->
                        MonthDayCell(
                            cell = cell,
                            selected = cell.date == state.selectedDate,
                            modifier = Modifier.weight(1f),
                        ) { onSelect(cell.date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    cell: MonthCell,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = TodoTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(if (selected) colors.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${cell.date.dayOfMonth}",
            style = Tokens.Type.section,
            fontWeight = if (cell.isToday && !selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> colors.onPrimary
                !cell.inMonth -> colors.text3.copy(alpha = 0.5f)
                cell.isToday -> colors.primary
                else -> colors.text
            },
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        cell.hasOverdue -> if (selected) colors.onPrimary else colors.danger
                        cell.count > 0 -> if (selected) colors.onPrimary else colors.primary
                        else -> Color.Transparent
                    },
                ),
        )
    }
}

/**
 * 列表在顶部时继续下拉（>72dp）展开月视图；展开状态下上推（>72dp）收起。
 * 在 `onPreScroll` 里处理并消费掉位移，避免被 overscroll 拉伸效果吞掉。
 */
@Composable
private fun rememberMonthOverscroll(
    expanded: Boolean,
    listState: LazyListState,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val accumulator = remember { mutableFloatStateOf(0f) }
    return remember(expanded, threshold, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = !listState.canScrollBackward
                if (!expanded) {
                    if (available.y > 0f && atTop) {
                        accumulator.floatValue += available.y
                        if (accumulator.floatValue > threshold) {
                            accumulator.floatValue = 0f
                            onExpand()
                        }
                        return Offset(0f, available.y)
                    }
                    accumulator.floatValue = 0f
                } else {
                    if (available.y < 0f && atTop) {
                        accumulator.floatValue += available.y
                        if (accumulator.floatValue < -threshold) {
                            accumulator.floatValue = 0f
                            onCollapse()
                        }
                        return Offset(0f, available.y)
                    }
                    accumulator.floatValue = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                accumulator.floatValue = 0f
                return Velocity.Zero
            }
        }
    }
}
