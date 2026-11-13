package com.todo.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.data.settings.StartupModes
import com.todo.android.core.designsystem.SegmentedControl
import com.todo.android.core.designsystem.SettingDivider
import com.todo.android.core.designsystem.SettingRow
import com.todo.android.core.designsystem.SettingsGroup
import com.todo.android.core.designsystem.TodoSwitch
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TAB_LABELS = listOf("通用", "外观", "AI", "提醒", "数据")

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMembers: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors
    val pagerState = rememberPagerState(pageCount = { TAB_LABELS.size })
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var loginOpen by remember { mutableStateOf(false) }
    val now = TimeText.now()

    // 手指滑动与点击标签双向同步。
    LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            viewModel.showToast(null)
        }
    }

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
                Text("设置", style = Tokens.Type.pageTitle, color = colors.text)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TAB_LABELS.forEachIndexed { index, label ->
                    val active = index == tab
                    Text(
                        text = label,
                        style = Tokens.Type.chip,
                        color = if (active) colors.onPrimary else colors.text2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.full))
                            .background(if (active) colors.primary else colors.card)
                            .clickable {
                                tab = index
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (page) {
                        0 -> CommonPanel(
                            state = state,
                            viewModel = viewModel,
                            now = now,
                            onLogin = { loginOpen = true },
                            onOpenMembers = onOpenMembers,
                        )

                        1 -> AppearancePanel(state, viewModel)
                        2 -> AiPanel(state, viewModel)
                        3 -> ReminderPanel(state, viewModel)
                        4 -> DataPanel(state, viewModel)
                        else -> Unit
                    }
                    Spacer(Modifier.height(56.dp))
                }
            }
        }

        TodoToast(
            message = state.toast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }

    if (loginOpen) {
        LoginDialog(
            defaultServer = state.settings.serverUrl,
            onDismiss = { loginOpen = false },
            onSubmit = { server, username, password, register ->
                viewModel.login(server, username, password, register)
                loginOpen = false
            },
        )
    }
}

@Composable
private fun CommonPanel(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    now: Long,
    onLogin: () -> Unit,
    onOpenMembers: (String) -> Unit,
) {
    val colors = TodoTheme.colors
    var serverEditOpen by remember { mutableStateOf(false) }

    SettingsGroup(title = "账户") {
        SettingRow(
            title = if (state.signedIn) "我的账号" else "登录 / 注册",
            description = if (state.signedIn) state.accountName else "登录后离线修改会按 HLC 顺序推送",
            onClick = onLogin,
        )
        SettingDivider()
        SettingRow(
            title = "工作区与成员",
            description = "${state.projection.activeWorkspace?.name ?: "未选择工作区"} · ${state.projection.members.size} 名成员",
            onClick = { onOpenMembers(state.projection.activeWorkspaceId) },
        )
    }
    SyncSettings(state = state, viewModel = viewModel, now = now)
    SettingsGroup(title = "服务器") {
        SettingRow(
            title = "服务器地址",
            description = state.settings.serverUrl,
            trailing = {
                Text(
                    text = "修改",
                    style = Tokens.Type.chip,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable { serverEditOpen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            },
            onClick = { serverEditOpen = true },
        )
        SettingDivider()
        SettingRow(
            title = "测试连接",
            description = "请求 /v1/health，确认地址与网络是否可达",
            onClick = { if (!state.busy) viewModel.testConnection() },
        )
    }
    SettingsGroup(title = "通用") {
        SettingRow(title = "界面语言", description = "中文（简体）")
        SettingDivider()
        SettingRow(
            title = "启动时打开",
            trailing = {
                SegmentedControl(
                    options = SettingsViewModel.startupLabels,
                    selectedIndex = SettingsViewModel.startupValues.indexOf(state.settings.startup).coerceAtLeast(0),
                    onSelected = { viewModel.setStartupMode(SettingsViewModel.startupValues[it]) },
                )
            },
        )
    }
    if (state.signedIn) {
        SettingsGroup(title = "登录状态") {
            SettingRow(
                title = "退出登录",
                description = "保留本地数据与同步账户，仅清除令牌",
                danger = true,
                onClick = { viewModel.logout() },
            )
        }
    }
    if (serverEditOpen) {
        ServerUrlDialog(
            initial = state.settings.serverUrl,
            onDismiss = { serverEditOpen = false },
            onConfirm = { url ->
                viewModel.setServerUrl(url)
                serverEditOpen = false
            },
        )
    }
}

@Composable
private fun ServerUrlDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = TodoTheme.colors
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("服务器地址", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("例如 http://192.168.1.8:8000") },
                    singleLine = true,
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(colors),
                )
                Text(
                    "手机与电脑需在同一网段；保存后可通过下方「测试连接」自检。",
                    style = Tokens.Type.meta,
                    color = colors.text2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) {
                Text("保存", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text2) }
        },
    )
}

@Composable
private fun AppearancePanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsGroup(title = "主题") {
        SettingRow(
            title = "颜色主题",
            description = "纯黑模式在 OLED 屏上更省电",
            trailing = {
                SegmentedControl(
                    options = SettingsViewModel.themeLabels,
                    selectedIndex = SettingsViewModel.themeOptions.indexOf(state.settings.theme).coerceAtLeast(0),
                    onSelected = { viewModel.setTheme(SettingsViewModel.themeOptions[it]) },
                )
            },
        )
    }
    SettingsGroup(title = "动效") {
        SettingRow(
            title = "减少动效",
            description = "尊重系统无障碍设置，可手动覆盖",
            trailing = { TodoSwitch(state.settings.reduceMotion, viewModel::setReduceMotion) },
        )
    }
}

@Composable
private fun SyncSettings(state: SettingsUiState, viewModel: SettingsViewModel, now: Long) {
    val colors = TodoTheme.colors
    val statusText = when (state.sync.status) {
        SyncStatus.SIGNED_OUT -> "未登录"
        SyncStatus.SYNCING -> "同步中…"
        SyncStatus.SYNCED -> "已同步"
        SyncStatus.OFFLINE -> "离线"
        SyncStatus.RETRYING -> "重试中"
        SyncStatus.AUTH_REQUIRED -> "登录已过期"
    }
    SettingsGroup(title = "同步状态") {
        SettingRow(
            title = statusText,
            description = buildString {
                append(TimeText.relativeSyncLabel(state.sync.lastSyncedAt, now))
                append(" · cursor seq ").append(state.sync.cursor)
                if (state.sync.pendingCount > 0) append(" · 待推送 ").append(state.sync.pendingCount)
            },
            trailing = {
                Text(
                    text = if (state.busy) "同步中…" else "立即同步",
                    style = Tokens.Type.chip,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable(enabled = !state.busy) { viewModel.syncNow() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            },
        )
        SettingDivider()
        SettingRow(
            title = "同步间隔",
            trailing = {
                SegmentedControl(
                    options = listOf("5 分钟", "15 分钟", "手动"),
                    selectedIndex = when (state.settings.syncIntervalMinutes) {
                        5 -> 0
                        15 -> 1
                        else -> 2
                    },
                    onSelected = { viewModel.setSyncInterval(if (it == 0) 5 else if (it == 1) 15 else 0) },
                )
            },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(colors.primary50)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "离线修改暂存在本地 op_log，重连后按 HLC 顺序推送。权限被拒的 op 标记 REJECTED，不自动重试。",
            style = Tokens.Type.meta,
            color = colors.primary,
        )
    }
}

@Composable
private fun LoginDialog(
    defaultServer: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, Boolean) -> Unit,
) {
    val colors = TodoTheme.colors
    var server by remember { mutableStateOf(defaultServer) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(if (register) "注册账号" else "登录", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(colors),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（3–32 位）") },
                    singleLine = true,
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(colors),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（8–128 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(colors),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable { register = !register }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TodoSwitch(register, { register = it })
                    Spacer(Modifier.width(8.dp))
                    Text("注册新账号并创建工作区", style = Tokens.Type.chip, color = colors.text2)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                        onSubmit(server, username, password, register)
                    }
                },
            ) {
                Text(if (register) "注册" else "登录", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text2) }
        },
    )
}

@Composable
private fun dialogFieldColors(colors: com.todo.android.core.designsystem.TodoColors) = TextFieldDefaults.colors(
    focusedContainerColor = colors.card,
    unfocusedContainerColor = colors.card,
    focusedTextColor = colors.text,
    unfocusedTextColor = colors.text,
    cursorColor = colors.primary,
    focusedLabelColor = colors.text2,
    unfocusedLabelColor = colors.text3,
)
