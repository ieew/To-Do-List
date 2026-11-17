package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 任意日期 + 任意分钟的时间选择对话框。
 *
 * 交互：先点快捷日期（今天/明天/…）或「日期」行打开日历，再用「时间」行打开表盘/键盘输入，
 * 确认后合成为带时区偏移的 ISO 字符串；「清除」回传 null 表示未设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    title: String,
    initialIso: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val colors = TodoTheme.colors
    val nowMs = remember { TimeText.now() }
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(initialIso) { TimeText.localDateTime(initialIso) }
    val today = remember(nowMs, zone) { Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() }

    var date by remember(initialIso) { mutableStateOf(initial?.toLocalDate() ?: today) }
    var hour by remember(initialIso) { mutableIntStateOf(initial?.hour ?: 9) }
    var minute by remember(initialIso) { mutableIntStateOf(initial?.minute ?: 0) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column {
                Text("快捷日期", style = Tokens.Type.meta, color = colors.text3)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = listOf(
                        "今天" to today,
                        "明天" to today.plusDays(1),
                        "3 天后" to today.plusDays(3),
                        "下周" to today.plusWeeks(1),
                    )
                    presets.forEach { (label, value) ->
                        DateChip(
                            label = label,
                            selected = date == value,
                            onClick = { date = value },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PickerRow(label = "日期", value = TimeText.dayTitle(date)) { datePickerOpen = true }
                Spacer(Modifier.height(8.dp))
                PickerRow(label = "时间", value = TimeText.clock(hour, minute)) { timePickerOpen = true }
                Spacer(Modifier.height(12.dp))
                Text(
                    "已选：${TimeText.dayTitle(date)} ${TimeText.clock(hour, minute)}",
                    style = Tokens.Type.meta,
                    color = colors.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(TimeText.isoAt(date, hour, minute)) }) {
                Text("确定", style = Tokens.Type.section, color = colors.primary)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onConfirm(null) }) {
                    Text("清除", style = Tokens.Type.section, color = colors.danger)
                }
                TextButton(onClick = onDismiss) {
                    Text("取消", style = Tokens.Type.section, color = colors.text2)
                }
            }
        },
    )

    if (datePickerOpen) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        datePickerOpen = false
                    },
                ) {
                    Text("确定", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) { Text("取消", color = colors.text2) }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (timePickerOpen) {
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePickerOpen = false },
            containerColor = colors.card,
            title = { Text("选择时间", style = Tokens.Type.pageTitle, color = colors.text) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hour = timeState.hour
                        minute = timeState.minute
                        timePickerOpen = false
                    },
                ) {
                    Text("确定", style = Tokens.Type.section, color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { timePickerOpen = false }) { Text("取消", color = colors.text2) }
            },
        )
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(if (selected) colors.primary50 else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.primary else colors.border2,
                shape = RoundedCornerShape(Tokens.Radius.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Tokens.Type.chip.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) colors.primary else colors.text2,
        )
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Tokens.Type.body, color = colors.text3, modifier = Modifier.width(48.dp))
        Text(
            value,
            style = Tokens.Type.body.copy(fontWeight = FontWeight.Medium),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Text("修改", style = Tokens.Type.chip, color = colors.primary)
    }
}
