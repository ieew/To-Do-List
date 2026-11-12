package com.todo.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.todo.android.core.designsystem.EditDialog
import com.todo.android.core.designsystem.SettingDivider
import com.todo.android.core.designsystem.SettingRow
import com.todo.android.core.designsystem.SettingsGroup
import com.todo.android.core.designsystem.TodoSwitch
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens

@Composable
internal fun AiPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = TodoTheme.colors
    var endpointOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var thresholdOpen by remember { mutableStateOf(false) }
    var keyOpen by remember { mutableStateOf(false) }

    SettingsGroup(title = "AI 能力") {
        SettingRow(
            title = "启用 AI 助手",
            description = "关闭后清单页入口与生成按钮不可用",
            trailing = { TodoSwitch(state.settings.aiEnabled, viewModel::setAiEnabled) },
        )
        SettingDivider()
        SettingRow(
            title = "支持图片输入",
            description = "最多 5 张图片随提示词一起发送",
            trailing = { TodoSwitch(state.settings.aiImageInput, viewModel::setAiImageInput) },
        )
    }

    SettingsGroup(title = "模型连接") {
        SettingRow(
            title = "API 端点",
            description = state.settings.aiEndpoint,
            onClick = { endpointOpen = true },
        )
        SettingDivider()
        SettingRow(
            title = "模型名称",
            description = state.settings.aiModel,
            onClick = { modelOpen = true },
        )
        SettingDivider()
        SettingRow(
            title = "API 密钥",
            description = if (state.aiKeyConfigured) "已配置（仅本机保存）" else "未配置",
            onClick = { keyOpen = true },
        )
        SettingDivider()
        SettingRow(
            title = "批量确认阈值",
            description = "一次写入 ≥ ${state.settings.aiConfirmThreshold} 条时先弹确认摘要",
            onClick = { thresholdOpen = true },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(colors.warning50)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "🔒 密钥仅本机保存（Keystore 加密），AI 能力逐设备可用。备份与迁移不会携带密钥。",
            style = Tokens.Type.meta,
            color = colors.warningFg,
        )
    }

    if (endpointOpen) {
        EditDialog(
            title = "API 端点",
            initial = state.settings.aiEndpoint,
            placeholder = "https://api.deepseek.com/v1",
            confirmLabel = "保存",
            onDismiss = { endpointOpen = false },
            onConfirm = {
                viewModel.setAiEndpoint(it)
                endpointOpen = false
                true
            },
        )
    }

    if (modelOpen) {
        EditDialog(
            title = "模型名称",
            initial = state.settings.aiModel,
            placeholder = "deepseek-chat",
            confirmLabel = "保存",
            onDismiss = { modelOpen = false },
            onConfirm = {
                viewModel.setAiModel(it)
                modelOpen = false
                true
            },
        )
    }

    if (thresholdOpen) {
        EditDialog(
            title = "批量确认阈值",
            initial = state.settings.aiConfirmThreshold.toString(),
            placeholder = "10",
            confirmLabel = "保存",
            keyboardType = KeyboardType.Number,
            onDismiss = { thresholdOpen = false },
            onConfirm = { raw ->
                val value = raw.toIntOrNull()
                if (value == null || value < 1) {
                    false
                } else {
                    viewModel.setAiThreshold(value)
                    thresholdOpen = false
                    true
                }
            },
        )
    }

    if (keyOpen) {
        KeyDialog(
            configured = state.aiKeyConfigured,
            onDismiss = { keyOpen = false },
            onSave = {
                viewModel.saveAiKey(it)
                keyOpen = false
            },
            onClear = {
                viewModel.clearAiKey()
                keyOpen = false
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(colors.primary50)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "提示：AI 写入与手动写入在数据层等价（source=ai），可在任务历史中查看并撤销。",
            style = Tokens.Type.meta,
            color = colors.primary,
        )
    }
}

@Composable
private fun KeyDialog(
    configured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = TodoTheme.colors
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("API 密钥", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    label = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (configured) {
                    Text(
                        "清除密钥",
                        style = Tokens.Type.chip,
                        color = colors.danger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.sm))
                            .clickable(onClick = onClear)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank(),
                onClick = { onSave(key) },
            ) {
                Text("保存", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text2) }
        },
    )
}
