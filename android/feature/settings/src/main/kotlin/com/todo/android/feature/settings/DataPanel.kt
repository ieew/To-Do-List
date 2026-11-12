package com.todo.android.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.todo.android.core.designsystem.SettingDivider
import com.todo.android.core.designsystem.SettingRow
import com.todo.android.core.designsystem.SettingsGroup
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens

@Composable
internal fun DataPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = TodoTheme.colors
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::inspectBackup) }

    SettingsGroup(title = "备份") {
        SettingRow(
            title = "导出备份",
            description = "包含业务数据与 op_log；不含密钥、令牌与设备偏好",
            onClick = { exportLauncher.launch("todo-backup.zip") },
        )
        SettingDivider()
        SettingRow(
            title = "导入备份",
            description = "导入前会显示摘要；重复 op_id 跳过，不覆盖本地未同步修改",
            onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        )
    }

    SettingsGroup(title = "本地存储") {
        SettingRow(title = "数据库大小", description = state.databaseSize)
        SettingDivider()
        SettingRow(
            title = "清除本地缓存",
            description = "保留 op_log，下次启动重新投影",
            danger = true,
            onClick = viewModel::clearCache,
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
            "⚠️ 迁移到新设备时，AI 配置与登录令牌不随备份恢复，需重新填写与登录。",
            style = Tokens.Type.meta,
            color = colors.warningFg,
        )
    }

    val preview = state.importPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            containerColor = colors.card,
            title = { Text("导入备份", style = Tokens.Type.pageTitle) },
            text = {
                Column {
                    Text(preview.summary.fileName, style = Tokens.Type.chip, color = colors.text2)
                    Text(
                        "工作区 ${preview.summary.workspaces} · 清单 ${preview.summary.lists} · 标签 ${preview.summary.tags}",
                        style = Tokens.Type.meta,
                        color = colors.text3,
                    )
                    Text(
                        "任务 ${preview.summary.tasks} · op ${preview.ops.size} 条",
                        style = Tokens.Type.meta,
                        color = colors.text3,
                    )
                    Text(
                        "导入的 op 会与本地数据按 HLC 合并；重复 op_id 跳过；不会覆盖本地未同步的修改，也不会删除任何本地数据。",
                        style = Tokens.Type.meta,
                        color = colors.warningFg,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text("确认导入", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) {
                    Text("取消", color = colors.text2)
                }
            },
        )
    }
}
