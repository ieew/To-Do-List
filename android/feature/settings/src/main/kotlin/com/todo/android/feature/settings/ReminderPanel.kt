package com.todo.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.todo.android.core.designsystem.EditDialog
import com.todo.android.core.designsystem.SegmentedControl
import com.todo.android.core.designsystem.SettingDivider
import com.todo.android.core.designsystem.SettingRow
import com.todo.android.core.designsystem.SettingsGroup
import com.todo.android.core.designsystem.TodoSwitch
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.data.settings.KeepAliveModes

@Composable
internal fun ReminderPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = TodoTheme.colors
    var windowOpen by remember { mutableStateOf(false) }

    SettingsGroup(title = "通知") {
        SettingRow(
            title = "系统通知",
            description = "关闭后不再发提醒，但任务不受影响",
            trailing = { TodoSwitch(state.settings.notifications, viewModel::setNotifications) },
        )
        SettingDivider()
        SettingRow(
            title = "通知权限",
            description = if (state.notificationAllowed) "已授权" else "未授权：请在系统设置中允许通知（API 33+）",
        )
        SettingDivider()
        SettingRow(
            title = "精确提醒",
            description = if (state.exactAlarmAllowed) {
                "已授权，按提醒时间准点触发"
            } else {
                "未授权「闹钟与提醒」，将降级为 15 分钟窗口"
            },
            trailing = { TodoSwitch(state.settings.exactAlarm, viewModel::setExactAlarm) },
        )
    }

    SettingsGroup(title = "免打扰") {
        SettingRow(
            title = "启用免打扰",
            description = "时段内静默，但仍记录 last_fired_at",
            trailing = { TodoSwitch(state.settings.dndEnabled, viewModel::setDndEnabled) },
        )
        SettingDivider()
        SettingRow(
            title = "时段",
            description = "${state.settings.dndStart} 至 ${state.settings.dndEnd}（跨天自动处理）",
            onClick = { windowOpen = true },
        )
    }

    SettingsGroup(title = "后台存活") {
        SettingRow(
            title = "保活方式",
            description = when (state.settings.keepAliveMode) {
                KeepAliveModes.OFF -> "已关闭：进程被回收后提醒仍会响，但不会自动重排"
                KeepAliveModes.SERVICE -> "心跳 + 常驻服务：划掉最近任务后约 2 秒自动重拉"
                else -> "15 分钟心跳 + 周期任务，无明显耗电与常驻通知"
            },
            trailing = {
                SegmentedControl(
                    options = listOf("关闭", "心跳", "常驻"),
                    selectedIndex = KeepAliveModes.all.indexOf(state.settings.keepAliveMode).coerceAtLeast(0),
                    onSelected = { viewModel.setKeepAliveMode(KeepAliveModes.all[it]) },
                )
            },
        )
        SettingDivider()
        SettingRow(
            title = "电池优化白名单",
            description = if (state.batteryWhitelisted) {
                "已加入白名单，系统不会随意限制后台"
            } else {
                "未加入：点击前往系统设置允许后台运行"
            },
            onClick = { viewModel.openBatteryWhitelist() },
        )
        SettingDivider()
        SettingRow(
            title = "自启动权限",
            description = "小米 / 华为 / OPPO / vivo 等需手动允许，点击前往厂商设置页",
            onClick = { viewModel.openAutoStart() },
        )
        SettingDivider()
        SettingRow(
            title = "常驻通知设置",
            description = "保活通知使用独立渠道「后台保活」，可单独关闭",
            onClick = { viewModel.openKeepAliveChannel() },
        )
        SettingDivider()
        SettingRow(
            title = "心跳记录",
            description = buildString {
                append(if (state.keepAliveLastBeatMs == null) "尚未收到心跳" else "上次心跳 " + TimeText.relativeSyncLabel(state.keepAliveLastBeatMs, TimeText.now()))
                append(" · 累计 ")
                append(state.keepAliveBeatCount)
                append(" 次")
            },
            onClick = { viewModel.refreshKeepAliveInfo() },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(colors.border2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "提醒本身由系统闹钟承载，进程不在也会准点触发；在系统设置里「强行停止」应用后，所有广播与闹钟都会被冻结，需要手动重新打开应用才会恢复。",
            style = Tokens.Type.meta,
            color = colors.text3,
        )
    }

    if (windowOpen) {
        EditDialog(
            title = "免打扰时段",
            initial = "${state.settings.dndStart}-${state.settings.dndEnd}",
            placeholder = "22:00-08:00",
            confirmLabel = "保存",
            description = "格式 HH:mm-HH:mm，支持跨天",
            onDismiss = { windowOpen = false },
            onConfirm = { raw ->
                val parts = raw.split("-")
                if (parts.size != 2) {
                    false
                } else {
                    val saved = viewModel.setDndWindow(parts[0], parts[1])
                    if (saved) windowOpen = false
                    saved
                }
            },
        )
    }
}
