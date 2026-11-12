package com.todo.android.feature.detail

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.AiBadge
import com.todo.android.core.designsystem.EditDialog
import com.todo.android.core.designsystem.TagChip
import com.todo.android.core.designsystem.TodoCheckbox
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.designsystem.priorityLabel
import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.SubtaskItem
import kotlinx.coroutines.delay

@Composable
fun TaskDetailScreen(
    taskId: String?,
    onBack: () -> Unit,
    onOpenHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors

    LaunchedEffect(taskId) { viewModel.bind(taskId) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            viewModel.showToast(null)
        }
    }

    var priorityOpen by remember { mutableStateOf(false) }
    var dateField by remember { mutableStateOf<String?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var subtaskOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = "返回",
                    tint = colors.text2,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable(onClick = onBack)
                        .padding(4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isNew) "新建任务" else (state.listName ?: "任务详情"),
                    style = Tokens.Type.section,
                    color = colors.text2,
                )
                Spacer(Modifier.weight(1f))
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    TitleCard(
                        state = state,
                        onTitleChange = viewModel::onTitleChange,
                        onNewTipVisible = state.isNew,
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    FieldCard(
                        state = state,
                        onPriority = { priorityOpen = true },
                        onStart = { dateField = "start_at" },
                        onDue = { dateField = "due_at" },
                        onRemind = { dateField = "remind_at" },
                        onList = { listOpen = true },
                        onTags = { tagsOpen = true },
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    SubtaskCard(
                        state = state,
                        onAdd = { subtaskOpen = true },
                        onToggle = viewModel::toggleSubtask,
                        onDelete = viewModel::deleteSubtask,
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item { NotesCard(state.notes, viewModel::onNotesChange) }
                if (!state.isNew && state.taskId != null) {
                    item { Spacer(Modifier.height(10.dp)) }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(Tokens.Radius.lg))
                                .background(colors.card)
                                .clickable { state.taskId?.let(onOpenHistory) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = colors.text3, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("查看历史", style = Tokens.Type.body, color = colors.text)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.text3, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.card)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailButton(
                    text = if (state.isNew) "取消" else "删除",
                    primary = false,
                    danger = !state.isNew,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (state.isNew) viewModel.cancel() else deleteOpen = true
                    },
                )
                DetailButton(
                    text = if (state.isNew) "创建" else "保存",
                    primary = true,
                    danger = false,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::save,
                )
            }
        }

        TodoToast(
            message = state.toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )
    }

    if (priorityOpen) {
        ChoiceDialog(
            title = "优先级",
            options = listOf("high" to "高", "mid" to "中", "low" to "低"),
            selected = state.priority,
            onDismiss = { priorityOpen = false },
            onSelect = {
                viewModel.setPriority(it)
                priorityOpen = false
            },
        )
    }

    dateField?.let { field ->
        DateOptionDialog(
            title = when (field) {
                "start_at" -> "开始时间"
                "due_at" -> "截止时间"
                else -> "提醒时间"
            },
            onDismiss = { dateField = null },
            onSelect = { iso ->
                when (field) {
                    "start_at" -> viewModel.setStart(iso)
                    "due_at" -> viewModel.setDue(iso)
                    else -> viewModel.setRemind(iso)
                }
                dateField = null
            },
        )
    }

    if (listOpen) {
        ChoiceDialog(
            title = "选择清单",
            options = state.lists.map { it.id to it.name },
            selected = state.listId.orEmpty(),
            onDismiss = { listOpen = false },
            onSelect = {
                viewModel.setList(it)
                listOpen = false
            },
        )
    }

    if (tagsOpen) {
        TagsDialog(
            state = state,
            onToggle = viewModel::toggleTag,
            onDismiss = { tagsOpen = false },
        )
    }

    if (subtaskOpen) {
        EditDialog(
            title = "添加子任务",
            initial = "",
            placeholder = "子任务内容",
            confirmLabel = "添加",
            onDismiss = { subtaskOpen = false },
            onConfirm = { text ->
                viewModel.addSubtask(text)
                subtaskOpen = false
                true
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = colors.card,
            title = { Text("删除任务", style = Tokens.Type.pageTitle) },
            text = { Text("删除后可在历史中用更高 HLC 的反向 op 撤销。", style = Tokens.Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.delete()
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("取消", color = colors.text2) }
            },
        )
    }
}

@Composable
private fun TitleCard(
    state: TaskDetailUiState,
    onTitleChange: (String) -> Unit,
    onNewTipVisible: Boolean,
) {
    val colors = TodoTheme.colors
    val task = state.projection.tasks.firstOrNull { it.id == state.taskId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.lg))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!state.isNew) {
                TodoCheckbox(
                    checked = task?.done == true,
                    onCheckedChange = { },
                    contentDescription = "任务完成状态",
                )
                Spacer(Modifier.width(4.dp))
            }
            BasicTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f),
                textStyle = Tokens.Type.pageTitle.copy(color = colors.text),
                keyboardOptions = KeyboardOptions.Default,
                decorationBox = { inner ->
                    if (state.title.isEmpty()) {
                        Text("输入任务标题", style = Tokens.Type.pageTitle, color = colors.text3)
                    }
                    inner()
                },
            )
        }
        if (!state.isNew && (task?.source == OpSource.AI || state.selectedTags.isNotEmpty())) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task?.source == OpSource.AI) AiBadge()
                state.selectedTags.forEach { TagChip(it.name, it.bg, it.fg) }
            }
        }
        if (onNewTipVisible) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(colors.primary50)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "开始时间 = 创建时刻，截止时间 = 开始时间 + 7 天，可随时修改。",
                    style = Tokens.Type.meta,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun FieldCard(
    state: TaskDetailUiState,
    onPriority: () -> Unit,
    onStart: () -> Unit,
    onDue: () -> Unit,
    onRemind: () -> Unit,
    onList: () -> Unit,
    onTags: () -> Unit,
) {
    val colors = TodoTheme.colors
    val rows = listOf(
        Triple("优先级", priorityLabel(state.priority), onPriority),
        Triple("开始", TimeText.dueLabel(state.startAt) ?: "未设置", onStart),
        Triple("截止", TimeText.dueLabel(state.dueAt) ?: "未设置", onDue),
        Triple("提醒", TimeText.dueLabel(state.remindAt) ?: "未设置", onRemind),
        Triple("清单", state.listName ?: "未选择", onList),
        Triple("标签", if (state.selectedTags.isEmpty()) "未添加标签" else state.selectedTags.joinToString("、") { it.name }, onTags),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.lg)),
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = row.third)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.first, style = Tokens.Type.body, color = colors.text3, modifier = Modifier.width(60.dp))
                Text(
                    row.second,
                    style = Tokens.Type.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.text3, modifier = Modifier.size(14.dp))
            }
            if (index != rows.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border2))
            }
        }
    }
}

@Composable
private fun SubtaskCard(
    state: TaskDetailUiState,
    onAdd: () -> Unit,
    onToggle: (SubtaskItem, Boolean) -> Unit,
    onDelete: (SubtaskItem) -> Unit,
) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.lg))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("子任务", style = Tokens.Type.section, color = colors.text)
            Spacer(Modifier.width(8.dp))
            Text("${state.subtaskDone} / ${state.subtasks.size}", style = Tokens.Type.chip, color = colors.text3)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Add,
                contentDescription = "添加子任务",
                tint = colors.primary,
                modifier = Modifier.size(18.dp).clickable(onClick = onAdd),
            )
        }
        if (state.subtasks.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("暂无子任务", style = Tokens.Type.meta, color = colors.text3)
        } else {
            state.subtasks.forEach { subtask ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TodoCheckbox(
                        checked = subtask.done,
                        onCheckedChange = { onToggle(subtask, it) },
                        size = Tokens.Size.miniCheckbox,
                        contentDescription = "子任务完成状态",
                    )
                    Text(
                        text = subtask.text,
                        style = Tokens.Type.body,
                        color = if (subtask.done) colors.text3 else colors.text,
                        textDecoration = if (subtask.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "删除子任务",
                        tint = colors.text3,
                        modifier = Modifier.size(18.dp).clickable { onDelete(subtask) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: String, onNotesChange: (String) -> Unit) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.lg))
            .padding(16.dp),
    ) {
        Text("备注", style = Tokens.Type.section, color = colors.text)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = notes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            textStyle = Tokens.Type.body.copy(color = colors.text),
            decorationBox = { inner ->
                if (notes.isEmpty()) {
                    Text("点击添加备注…", style = Tokens.Type.body, color = colors.text3)
                }
                inner()
            },
        )
    }
}

@Composable
private fun DetailButton(
    text: String,
    primary: Boolean,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = TodoTheme.colors
    val background = when {
        primary -> colors.primary
        danger -> colors.danger50
        else -> colors.card
    }
    val border = when {
        primary -> colors.primary
        danger -> colors.dangerBorder
        else -> colors.border
    }
    val content = when {
        primary -> colors.onPrimary
        danger -> colors.danger
        else -> colors.text2
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(Tokens.Radius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Tokens.Type.section, color = content)
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = TodoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Tokens.Radius.sm))
                            .background(if (value == selected) colors.primary50 else Color.Transparent)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = Tokens.Type.body, color = colors.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.text2) }
        },
    )
}

@Composable
private fun DateOptionDialog(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val now = TimeText.now()
    val options = listOf(
        "今天 18:00" to TimeText.isoTodayAt(18, 0, now),
        "明天 09:00" to TimeText.isoTomorrowAt(9, 0, now),
        "3 天后" to TimeText.isoAfterDays(3, now),
        "7 天后" to TimeText.isoAfterDays(7, now),
    )
    val colors = TodoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column {
                options.forEach { (label, iso) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(iso) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(label, style = Tokens.Type.body, color = colors.text)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text("清除", style = Tokens.Type.body, color = colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text2) }
        },
    )
}

@Composable
private fun TagsDialog(
    state: TaskDetailUiState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TodoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("标签", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column {
                if (state.tags.isEmpty()) {
                    Text("当前工作区还没有标签", style = Tokens.Type.body, color = colors.text3)
                }
                state.tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(tag.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TodoCheckbox(
                            checked = state.tagIds.contains(tag.id),
                            onCheckedChange = { onToggle(tag.id) },
                            size = Tokens.Size.miniCheckbox,
                            contentDescription = null,
                        )
                        TagChip(tag.name, tag.bg, tag.fg)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成", color = colors.primary) }
        },
    )
}
