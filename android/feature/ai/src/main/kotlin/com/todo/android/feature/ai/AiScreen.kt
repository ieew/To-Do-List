package com.todo.android.feature.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.ai.PlannedTask
import com.todo.android.core.designsystem.SegmentedControl
import com.todo.android.core.designsystem.StatusBadge
import com.todo.android.core.designsystem.TagChip
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.priorityLabel
import kotlinx.coroutines.delay

@Composable
fun AiScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors
    var tab by remember { mutableStateOf(0) }
    var showImagesHint by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        uris.take(5).forEach(viewModel::addImage)
    }

    LaunchedEffect(Unit) { viewModel.refreshKeyState() }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            viewModel.showToast(null)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AI 助手", style = Tokens.Type.pageTitle, color = colors.text)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = colors.text2,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable(onClick = onBack)
                        .padding(4.dp),
                )
            }

            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                SegmentedControl(
                    options = listOf("API 直连", "网页版 DeepSeek"),
                    selectedIndex = tab,
                    onSelected = { tab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (tab == 1) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Tokens.Radius.lg))
                        .background(colors.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("网页桥接为预留能力", style = Tokens.Type.section, color = colors.text)
                    Text(
                        "请在浏览器中使用 DeepSeek 网页版生成任务列表，再复制回 API 直连模式。当前版本仅支持 API 直连。",
                        style = Tokens.Type.body,
                        color = colors.text3,
                    )
                }
                return@Column
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.keyConfigured) colors.success else colors.text3),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.settings.aiModel} · ${if (state.keyConfigured) "密钥已配置" else "未配置密钥"}",
                    style = Tokens.Type.chip,
                    color = colors.text2,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "设置",
                    style = Tokens.Type.chip,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Tokens.Radius.lg))
                            .background(colors.card)
                            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.lg))
                            .padding(12.dp),
                    ) {
                        BasicTextField(
                            value = state.input,
                            onValueChange = viewModel::onInputChange,
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            textStyle = Tokens.Type.body.copy(color = colors.text),
                            decorationBox = { inner ->
                                if (state.input.isEmpty()) {
                                    Text(
                                        "例如：帮我规划今晚的学习安排，8 点前完成笔记…",
                                        style = Tokens.Type.body,
                                        color = colors.text3,
                                    )
                                }
                                inner()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                                    .clickable {
                                        if (state.imageCount >= 5) {
                                            showImagesHint = true
                                        } else {
                                            picker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                            )
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Image, contentDescription = "添加图片", tint = colors.text2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (state.imageCount == 0) "可添加最多 5 张图片" else "已添加 ${state.imageCount} 张图片",
                                    style = Tokens.Type.meta,
                                    color = colors.text3,
                                )
                            }
                            if (state.imageCount > 0) {
                                Text(
                                    "清除",
                                    style = Tokens.Type.meta,
                                    color = colors.danger,
                                    modifier = Modifier
                                        .clickable { viewModel.removeImages() }
                                        .padding(4.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                                    .background(colors.primary)
                                    .clickable(enabled = !state.generating && state.keyConfigured) {
                                        viewModel.generate()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    if (state.generating) "生成中…" else "生成草案",
                                    style = Tokens.Type.chip,
                                    color = colors.onPrimary,
                                )
                            }
                        }
                    }
                }

                state.error?.let { error ->
                    item {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Tokens.Radius.sm))
                                .background(colors.danger50)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(error, style = Tokens.Type.meta, color = colors.danger)
                        }
                    }
                }

                val plan = state.plan
                if (plan != null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("草案预览", style = Tokens.Type.section, color = colors.text2)
                            Spacer(Modifier.width(6.dp))
                            Text("${plan.tasks.size} 条", style = Tokens.Type.meta, color = colors.text3)
                        }
                    }
                    items(plan.tasks) { task -> DraftCard(task) }
                    if (plan.warnings.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                                    .background(colors.warning50)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                plan.warnings.forEach {
                                    Text("· $it", style = Tokens.Type.meta, color = colors.warningFg)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }

            if (state.plan != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.card)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FooterButton("取消", false, Modifier.weight(1f)) { viewModel.cancelPlan() }
                    FooterButton(
                        "写入 ${state.plan?.tasks?.size ?: 0} 条任务",
                        true,
                        Modifier.weight(1f),
                    ) { viewModel.requestWrite() }
                }
            }
        }

        TodoToast(
            message = state.toast ?: if (showImagesHint) "最多添加 5 张图片" else null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
        )
    }

    LaunchedEffect(showImagesHint) {
        if (showImagesHint) {
            delay(2200)
            showImagesHint = false
        }
    }

    if (state.confirmPending) {
        val plan = state.plan
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            containerColor = colors.card,
            title = { Text("AI 想写入 ${plan?.tasks?.size ?: 0} 条任务", style = Tokens.Type.pageTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    plan?.tasks?.take(5)?.forEach { Text("· ${it.title}", style = Tokens.Type.body, color = colors.text) }
                    if ((plan?.tasks?.size ?: 0) > 5) {
                        Text("…还有 ${(plan?.tasks?.size ?: 0) - 5} 条", style = Tokens.Type.meta, color = colors.text3)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmWrite) { Text("确认写入", color = colors.primary) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirm) { Text("取消", color = colors.text2) }
            },
        )
    }
}

@Composable
private fun DraftCard(task: PlannedTask) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.md))
            .padding(12.dp),
    ) {
        Text(task.title, style = Tokens.Type.taskTitle, color = colors.text)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge("优先级 ${priorityLabel(task.priority)}", colors.primary50, colors.primary)
            StatusBadge(task.listName, colors.warning50, colors.warningFg)
            Text(TimeText.dueLabel(task.dueAtIso) ?: "无截止", style = Tokens.Type.meta, color = colors.text3)
        }
        if (task.tagNames.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                task.tagNames.forEach { Text(it, style = Tokens.Type.badge, color = colors.text3) }
            }
        }
        if (task.subtasks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("${task.subtasks.size} 个子任务", style = Tokens.Type.meta, color = colors.text3)
        }
    }
}

@Composable
private fun FooterButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = TodoTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(if (primary) colors.primary else colors.card)
            .border(1.dp, if (primary) colors.primary else colors.border, RoundedCornerShape(Tokens.Radius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Tokens.Type.section,
            color = if (primary) colors.onPrimary else colors.text2,
        )
    }
}
