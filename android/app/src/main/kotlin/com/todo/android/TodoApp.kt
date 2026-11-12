package com.todo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Person
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.EmptyState
import com.todo.android.core.designsystem.SkeletonScreen
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens
import com.todo.android.feature.ai.AiScreen
import com.todo.android.feature.calendar.CalendarScreen
import com.todo.android.feature.detail.HistoryScreen
import com.todo.android.feature.detail.TaskDetailScreen
import com.todo.android.feature.members.MembersScreen
import com.todo.android.feature.settings.SettingsScreen
import com.todo.android.feature.stats.StatsScreen
import com.todo.android.feature.tasks.TasksScreen

@Composable
fun TodoApp(
    initialTaskId: String? = null,
    viewModel: AppViewModel = viewModel(),
) {
    val boot by viewModel.boot.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crashText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.bootstrap()
        crashText = CrashLogger.read(context)
    }
    LaunchedEffect(initialTaskId) {
        if (!initialTaskId.isNullOrBlank()) viewModel.openDetail(initialTaskId)
    }

    BackHandler(enabled = screen !is Screen.Tasks) { viewModel.back() }

    com.todo.android.core.designsystem.TodoTheme(
        mode = theme,
        reduceMotion = settings.reduceMotion,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TodoTheme.colors.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            when (val state = boot) {
                BootState.Loading -> {
                    Column(modifier = Modifier.fillMaxSize().padding(top = 80.dp)) {
                        SkeletonScreen()
                    }
                }

                is BootState.Failed -> EmptyState(
                    title = state.message,
                    actionLabel = "重试",
                    onAction = { viewModel.bootstrap() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                BootState.Ready -> {
                    when (val current = screen) {
                        Screen.Tasks -> ScaffoldWithTabs(
                            selected = 0,
                            onSelect = viewModel::navigate,
                        ) {
                            TasksScreen(
                                onOpenDetail = viewModel::openDetail,
                                onOpenAi = { viewModel.navigate(Screen.Ai) },
                                onOpenMembers = viewModel::openMembers,
                            )
                        }

                        Screen.Calendar -> ScaffoldWithTabs(
                            selected = 1,
                            onSelect = viewModel::navigate,
                        ) {
                            CalendarScreen(onOpenDetail = viewModel::openDetail)
                        }

                        Screen.Stats -> ScaffoldWithTabs(
                            selected = 2,
                            onSelect = viewModel::navigate,
                        ) {
                            StatsScreen()
                        }

                        Screen.Settings -> ScaffoldWithTabs(
                            selected = 3,
                            onSelect = viewModel::navigate,
                        ) {
                            SettingsScreen(
                                onBack = { viewModel.back() },
                                onOpenMembers = viewModel::openMembers,
                            )
                        }

                        is Screen.Members -> MembersScreen(
                            workspaceId = current.workspaceId,
                            onBack = { viewModel.back() },
                        )

                        Screen.Ai -> AiScreen(
                            onBack = { viewModel.back() },
                            onOpenSettings = { viewModel.navigate(Screen.Settings) },
                        )

                        is Screen.Detail -> TaskDetailScreen(
                            taskId = current.taskId,
                            onBack = { viewModel.back() },
                            onOpenHistory = viewModel::openHistory,
                        )

                        is Screen.History -> HistoryScreen(
                            taskId = current.taskId,
                            onBack = { viewModel.back() },
                        )
                    }
                }
            }
        }

        crashText?.let { text ->
            CrashReportDialog(
                text = text,
                onCopy = { clipboard.setText(AnnotatedString(text)) },
                onDismiss = {
                    CrashLogger.clear(context)
                    crashText = null
                },
            )
        }
    }
}

/** 上次闪退的堆栈：只本机保存，可一键复制反馈。 */
@Composable
private fun CrashReportDialog(text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val colors = TodoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("上次运行遇到问题", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Text(
                text = text.take(4_000),
                style = Tokens.Type.mono,
                color = colors.text2,
                maxLines = 24,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text("复制", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.text2) }
        },
    )
}

@Composable
private fun ScaffoldWithTabs(
    selected: Int,
    onSelect: (Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) { content() }
        TabBar(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Screen) -> Unit) {
    val colors = TodoTheme.colors
    val tabs = listOf(
        Triple("清单", Icons.Rounded.FormatListBulleted, Screen.Tasks),
        Triple("日历", Icons.Rounded.CalendarMonth, Screen.Calendar),
        Triple("统计", Icons.Rounded.BarChart, Screen.Stats),
        Triple("我的", Icons.Rounded.Person, Screen.Settings),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, (label, icon, target) ->
            TabItem(
                label = label,
                icon = icon,
                active = index == selected,
                onClick = { onSelect(target) },
            )
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) colors.primary else colors.text3,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = Tokens.Type.badge,
            color = if (active) colors.primary else colors.text3,
        )
    }
}
