package com.todo.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.todo.android.core.dsh.DshMode

@Composable
internal fun ReminderPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = TodoTheme.colors
    var windowOpen by remember { mutableStateOf(false) }
    var revokeOpen by remember { mutableStateOf(false) }
    var reenableOpen by remember { mutableStateOf(false) }

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
        SettingDivider()
        SettingRow(
            title = "提前提醒",
            description = when (val lead = state.settings.reminderLead) {
                0 -> "到提醒时间才响"
                else -> "提前 $lead 分钟响铃（通知上仍可稍后提醒）"
            },
            trailing = {
                SegmentedControl(
                    options = listOf("准点", "5 分", "15 分", "30 分"),
                    selectedIndex = LEAD_OPTIONS.indexOf(state.settings.reminderLead).coerceAtLeast(0),
                    onSelected = { viewModel.setReminderLead(LEAD_OPTIONS[it]) },
                )
            },
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

    SettingsGroup(title = "数据通道") {
        val dsh by viewModel.dshStatus.collectAsStateWithLifecycle()
        SettingRow(
            title = "启用新数据通道",
            description = "推送、提醒、剪贴板走 dsh 通道；关闭后只保留本地闹钟",
            trailing = {
                TodoSwitch(state.settings.dshChannelEnabled, viewModel::setDshChannelEnabled)
            },
        )
        SettingDivider()
        SettingRow(
            title = "通道形态",
            description = when {
                !state.settings.dshChannelEnabled -> "已关闭"
                dsh.mode == DshMode.IME -> "经输入法插件（实时 nudge，剪贴板由插件负责）"
                dsh.mode == DshMode.SELF -> "App 自建 MQTT（未检测到输入法插件）"
                else -> "仅轮询"
            },
        )
        SettingDivider()
        SettingRow(
            title = "优先使用输入法通道",
            description = if (state.settings.dshPreferIme) {
                "输入法是默认输入法时自动委托，避免两条长连接"
            } else {
                "始终由 App 自己维持通道"
            },
            trailing = { TodoSwitch(state.settings.dshPreferIme, viewModel::setDshPreferIme) },
        )
        SettingDivider()
        SettingRow(
            title = "连接状态",
            description = buildString {
                append(if (dsh.connected) "已连接" else "未连接")
                append(" · 游标 ")
                append(dsh.cursor)
                if (dsh.lastError.isNotBlank()) append(" · ").append(dsh.lastError)
            },
            onClick = { viewModel.refreshDsh() },
        )
        SettingDivider()
        SettingRow(
            title = "设备",
            description = "App ${dsh.appDeviceId.ifBlank { "未注册" }} · 输入法 ${dsh.imeDeviceId.ifBlank { "未授权" }}",
        )
        SettingDivider()
        if (!dsh.imeRevoked) {
            SettingRow(
                title = "撤销输入法权限",
                description = "通知推送与剪贴板同步一起停止；撤销是终态，恢复要重新启用并换新设备",
                danger = true,
                onClick = { revokeOpen = true },
            )
        } else {
            SettingRow(
                title = if (dsh.imeRevokePending) "已撤销 · 待同步到服务端" else "已在 App 中撤销推送权限",
                description = if (dsh.imeRevokePending) {
                    "本机已停发，但服务端还没收到 —— 这期间被偷的凭证仍然有效，点此重试同步"
                } else {
                    "服务端已吊销：插件下次续期会被拒绝，并进入「已在 App 中撤销」终态"
                },
                danger = dsh.imeRevokePending,
                onClick = if (dsh.imeRevokePending) ({ viewModel.retryImeRevoke() }) else null,
            )
            SettingDivider()
            SettingRow(
                title = "重新启用输入法通道",
                description = if (dsh.imeRevokePending) {
                    "先完成撤销同步；否则会出现旧设备没撤干净就发了新凭证"
                } else {
                    "会生成一个新的输入法设备，旧设备永久失效"
                },
                onClick = if (dsh.imeRevokePending) null else ({ reenableOpen = true }),
            )
        }
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

    if (revokeOpen) {
        ConfirmDialog(
            title = "撤销输入法通道？",
            body = "通知推送与剪贴板同步会一起停止。撤销后服务端会吊销这台设备，插件下次续期会被拒绝并进入「已撤销」终态。\n\n撤销是终态：恢复需要重新启用，并生成一个新的输入法设备。",
            confirmLabel = "撤销",
            danger = true,
            onConfirm = {
                revokeOpen = false
                viewModel.revokeIme()
            },
            onDismiss = { revokeOpen = false },
        )
    }

    if (reenableOpen) {
        ConfirmDialog(
            title = "重新启用输入法通道？",
            body = "会生成一个新的输入法设备并重新签发凭证；旧设备保持已撤销状态、永久失效。\n\n启用后请在输入法面板确认通道已恢复。",
            confirmLabel = "重新启用",
            danger = false,
            onConfirm = {
                reenableOpen = false
                viewModel.reenableIme()
            },
            onDismiss = { reenableOpen = false },
        )
    }
}

/** 两按钮确认框：危险操作把确认键标红，取消始终在右侧。 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TodoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = Tokens.Type.pageTitle, color = colors.text) },
        text = { Text(body, style = Tokens.Type.body, color = colors.text2) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) colors.danger else colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text3) }
        },
    )
}

/** 提前提醒可选分钟数（0 = 准点）。 */
private val LEAD_OPTIONS = listOf(0, 5, 15, 30)
