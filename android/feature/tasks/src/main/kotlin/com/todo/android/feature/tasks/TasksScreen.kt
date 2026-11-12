package com.todo.android.feature.tasks

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.EditDialog
import com.todo.android.core.designsystem.EmptyState
import com.todo.android.core.designsystem.GradientProgress
import com.todo.android.core.designsystem.QuickAddRow
import com.todo.android.core.designsystem.SectionTitle
import com.todo.android.core.designsystem.SkeletonScreen
import com.todo.android.core.designsystem.StatusBadge
import com.todo.android.core.designsystem.SwipeTaskRow
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.WorkspaceItem
import com.todo.android.core.sync.SyncStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onOpenDetail: (String?) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMembers: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var sheetOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<TaskItem?>(null) }

    val colors = TodoTheme.colors
    val nowMs = TimeText.now()

    LaunchedEffect(state.toast, state.undoTaskId) {
        if (state.toast != null) {
            // 撤销只在 3 秒内可点，过后连提示一起消失。
            delay(if (state.undoTaskId != null) 3_000 else 2_200)
            viewModel.showToast(null)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                workspace = state.projection.workspaces.firstOrNull { it.id == state.projection.activeWorkspaceId },
                onWorkspaceClick = { sheetOpen = true },
                onSearchClick = { searchOpen = !searchOpen },
                onAiClick = onOpenAi,
            )

            if (searchOpen || state.search.isNotEmpty()) {
                SearchRow(
                    query = state.search,
                    onQueryChange = viewModel::onSearchChange,
                    onClose = {
                        viewModel.clearSearch()
                        searchOpen = false
                    },
                )
            }

            val tasks = state.groups.today + state.groups.upcoming + state.groups.completed
            if (state.loading) {
                SkeletonScreen(modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(4.dp))
                            Text(TimeText.greeting(nowMs), style = Tokens.Type.greeting, color = colors.text)
                            Text(TimeText.dateLine(nowMs), style = Tokens.Type.chip, color = colors.text3)
                            Spacer(Modifier.height(12.dp))
                            ProgressCard(
                                done = state.progressDone,
                                total = state.progressTotal,
                                percent = state.percent,
                            )
                            Spacer(Modifier.height(10.dp))
                            ScopeTabs(
                                allSelected = state.scope.type.name == "ALL",
                                onToday = { viewModel.selectScope(all = false) },
                                onAll = { viewModel.selectScope(all = true) },
                            )
                            Spacer(Modifier.height(10.dp))
                            QuickAddRow(
                                value = draft,
                                onValueChange = { draft = it },
                                onPlusClick = { onOpenDetail(null) },
                                onSubmit = {
                                    if (viewModel.quickCreate(draft)) draft = ""
                                },
                            )
                            SyncBanner(state)
                        }
                    }

                    if (tasks.isEmpty()) {
                        item {
                            EmptyState(
                                title = emptyTitle(state),
                                hint = if (state.search.isNotEmpty()) null else "点右上角搜索或直接输入标题快速创建",
                                actionLabel = if (state.search.isNotEmpty()) "清除搜索" else "＋ 添加一个",
                                onAction = {
                                    if (state.search.isNotEmpty()) viewModel.clearSearch() else onOpenDetail(null)
                                },
                            )
                        }
                    } else {
                        if (state.search.isEmpty() && state.groups.today.isNotEmpty()) {
                            item {
                                SectionTitle(
                                    text = "今天",
                                    count = "${state.groups.today.size} 项待办",
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            items(state.groups.today, key = { it.id }) { task ->
                                TaskListItem(task, state, nowMs, viewModel, onOpenDetail) { actionTarget = task }
                            }
                        }
                        if (state.search.isEmpty() && state.groups.upcoming.isNotEmpty()) {
                            item {
                                SectionTitle(
                                    text = "即将到来",
                                    count = "${state.groups.upcoming.size} 项",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            items(state.groups.upcoming, key = { it.id }) { task ->
                                TaskListItem(task, state, nowMs, viewModel, onOpenDetail) { actionTarget = task }
                            }
                        }
                        if (state.search.isNotEmpty()) {
                            item {
                                SectionTitle(
                                    text = "搜索结果",
                                    count = "${tasks.size} 项",
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            items(tasks, key = { it.id }) { task ->
                                TaskListItem(task, state, nowMs, viewModel, onOpenDetail) { actionTarget = task }
                            }
                        }
                        if (state.search.isEmpty() && state.groups.completed.isNotEmpty() && state.scope.type.name != "TODAY") {
                            item {
                                SectionTitle(
                                    text = "已完成",
                                    count = "${state.groups.completed.size} 项",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            items(state.groups.completed, key = { it.id }) { task ->
                                TaskListItem(task, state, nowMs, viewModel, onOpenDetail) { actionTarget = task }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }

        TodoToast(
            message = state.toast,
            actionLabel = if (state.undoTaskId != null) "撤销" else null,
            onAction = {
                state.undoTaskId?.let { taskId ->
                    viewModel.restoreTask(taskId)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            WorkspaceSheet(
                workspaces = state.projection.workspaces,
                activeId = state.projection.activeWorkspaceId,
                memberCount = state.projection.members.size,
                onSwitch = {
                    viewModel.switchWorkspace(it)
                    sheetOpen = false
                },
                onCreate = { name -> viewModel.createWorkspace(name) },
                onRename = { id, name -> viewModel.renameWorkspace(id, name) },
                onDelete = { id -> viewModel.deleteWorkspace(id) },
                onManageMembers = {
                    sheetOpen = false
                    onOpenMembers(state.projection.activeWorkspaceId)
                },
            )
        }
    }

    actionTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            TaskQuickActionSheet(
                task = target,
                tags = state.projection.tags,
                onDelete = {
                    actionTarget = null
                    viewModel.deleteTask(target)
                },
                onPriority = { priority ->
                    viewModel.setPriority(target, priority)
                    actionTarget = null
                },
                onToggleTag = { tagId, assigned ->
                    viewModel.toggleTag(target.id, tagId, assigned)
                    actionTarget = null
                },
            )
        }
    }
}

@Composable
private fun TaskListItem(
    task: TaskItem,
    state: TasksUiState,
    nowMs: Long,
    viewModel: TasksViewModel,
    onOpenDetail: (String?) -> Unit,
    onMore: () -> Unit,
) {
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        SwipeTaskRow(
            task = task,
            tagsById = state.tagsById,
            nowMs = nowMs,
            onToggle = { done -> viewModel.toggleTask(task, done) },
            onClick = { onOpenDetail(task.id) },
            onMore = onMore,
            titleStyle = Tokens.Type.taskTitleList,
        )
    }
}

@Composable
private fun TopBar(
    workspace: WorkspaceItem?,
    onWorkspaceClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAiClick: () -> Unit,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(Tokens.Radius.md))
                .clickable(onClick = onWorkspaceClick)
                .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.linearGradient(listOf(colors.primary, colors.purple))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = workspace?.name?.take(1) ?: "我",
                    style = Tokens.Type.badge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = workspace?.name ?: "未登录",
                style = Tokens.Type.section,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "切换工作区",
                tint = colors.text3,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        CircleIconButton(
            icon = Icons.Rounded.Search,
            description = "搜索",
            onClick = onSearchClick,
            background = colors.card,
            tint = colors.text2,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colors.aiGradientStart, colors.aiGradientEnd)))
                .clickable(onClick = onAiClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = "AI 助手",
                tint = colors.aiFg,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    background: Color,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(Tokens.Radius.md))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.text3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = Tokens.Type.body.copy(color = colors.text),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("搜索任务标题或备注", style = Tokens.Type.body, color = colors.text3)
                }
                inner()
            },
        )
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索", tint = colors.text3, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ProgressCard(done: Int, total: Int, percent: Int) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(Brush.linearGradient(listOf(colors.primary50, colors.success50)))
            .border(1.dp, colors.primary100, RoundedCornerShape(Tokens.Radius.lg))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日进度", style = Tokens.Type.chip, color = colors.text2)
            Spacer(Modifier.weight(1f))
            Text("$done / $total · $percent%", style = Tokens.Type.chip.copy(fontWeight = FontWeight.SemiBold), color = colors.text2)
        }
        Spacer(Modifier.height(8.dp))
        GradientProgress(progress = if (total == 0) 0f else done.toFloat() / total)
    }
}

@Composable
private fun ScopeTabs(allSelected: Boolean, onToday: () -> Unit, onAll: () -> Unit) {
    val colors = TodoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("今天" to !allSelected, "全部" to allSelected).forEach { (label, active) ->
            Text(
                text = label,
                style = Tokens.Type.chip,
                color = if (active) colors.onPrimary else colors.text2,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.full))
                    .background(if (active) colors.primary else colors.card)
                    .border(1.dp, if (active) colors.primary else colors.border, RoundedCornerShape(Tokens.Radius.full))
                    .clickable { if (label == "今天") onToday() else onAll() }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun SyncBanner(state: TasksUiState) {
    val colors = TodoTheme.colors
    val (text, bg, fg) = when (state.sync.status) {
        SyncStatus.AUTH_REQUIRED -> Triple("登录已过期，请重新登录", colors.danger50, colors.danger)
        SyncStatus.OFFLINE, SyncStatus.RETRYING -> Triple("网络不稳定，正在重试", colors.warning50, colors.warningFg)
        else -> Triple("", colors.card, colors.text3)
    }
    if (text.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = Tokens.Type.meta, color = fg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSheet(
    workspaces: List<WorkspaceItem>,
    activeId: String,
    memberCount: Int,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Boolean,
    onRename: (String, String) -> Boolean,
    onDelete: (String) -> Boolean,
    onManageMembers: () -> Unit,
) {
    val colors = TodoTheme.colors
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WorkspaceItem?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceItem?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "切换工作区",
            style = Tokens.Type.pageTitle,
            color = colors.text,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        workspaces.forEach { workspace ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.md))
                    .background(if (workspace.id == activeId) colors.primary50 else Color.Transparent)
                    .clickable { onSwitch(workspace.id) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Brush.linearGradient(listOf(colors.primary, colors.purple))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        workspace.name.take(1),
                        style = Tokens.Type.section,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(workspace.name, style = Tokens.Type.section, color = colors.text)
                    Text(
                        "$memberCount 名成员",
                        style = Tokens.Type.meta,
                        color = colors.text3,
                    )
                }
                StatusBadge("所有者", colors.warning50, colors.warningFg)
                if (workspace.id == activeId) {
                    Icon(Icons.Rounded.Check, contentDescription = "当前工作区", tint = colors.primary, modifier = Modifier.size(18.dp))
                }
                Box {
                    IconButton(onClick = { menuFor = workspace.id }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = "更多", tint = colors.text3, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuFor == workspace.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text("重命名", style = Tokens.Type.body) },
                            onClick = {
                                menuFor = null
                                renameTarget = workspace
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "删除工作区",
                                    style = Tokens.Type.body,
                                    color = if (workspaces.size <= 1) colors.text3 else colors.danger,
                                )
                            },
                            enabled = workspaces.size > 1,
                            onClick = {
                                menuFor = null
                                deleteTarget = workspace
                            },
                        )
                    }
                }
            }
        }

        if (workspaces.size <= 1) {
            Text(
                "至少需要保留一个工作区",
                style = Tokens.Type.meta,
                color = colors.text3,
                modifier = Modifier.padding(bottom = 6.dp, start = 10.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .border(1.dp, colors.dashed, RoundedCornerShape(Tokens.Radius.md))
                .clickable { createOpen = true }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建工作区", style = Tokens.Type.chip, color = colors.primary)
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onManageMembers, modifier = Modifier.fillMaxWidth()) {
            Text("管理成员", style = Tokens.Type.chip, color = colors.text2)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (createOpen) {
        EditDialog(
            title = "新建工作区",
            initial = "",
            placeholder = "工作区名称（1–24 字符）",
            confirmLabel = "创建",
            onDismiss = { createOpen = false },
            onConfirm = { name ->
                if (onCreate(name)) {
                    createOpen = false
                    true
                } else {
                    false
                }
            },
        )
    }

    renameTarget?.let { target ->
        EditDialog(
            title = "重命名工作区",
            initial = target.name,
            placeholder = "工作区名称（1–24 字符）",
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                if (onRename(target.id, name)) {
                    renameTarget = null
                    true
                } else {
                    false
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除工作区", style = Tokens.Type.pageTitle) },
            text = { Text("删除「${target.name}」后，本地数据不会立即消失，但服务端会将其标记为已删除。", style = Tokens.Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = colors.text2) }
            },
            containerColor = colors.card,
        )
    }
}

private fun emptyTitle(state: TasksUiState): String = when {
    state.search.isNotEmpty() -> "没有找到匹配的任务"
    state.scope.type.name == "ALL" -> "还没有未完成的任务"
    state.scope.type.name == "LIST" -> "这个清单还没有任务"
    state.scope.type.name == "TAG" -> "这个标签还没有任务"
    else -> "今天没有进行中的任务"
}
