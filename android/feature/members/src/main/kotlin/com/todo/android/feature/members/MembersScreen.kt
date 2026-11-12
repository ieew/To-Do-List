package com.todo.android.feature.members

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import com.todo.android.core.designsystem.EmptyState
import com.todo.android.core.designsystem.SegmentedControl
import com.todo.android.core.designsystem.SkeletonScreen
import com.todo.android.core.designsystem.StatusBadge
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import kotlinx.coroutines.delay

private val ROLE_VALUES = listOf("editor", "viewer", "owner")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    workspaceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MembersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors
    var inviteOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<MemberRow?>(null) }

    LaunchedEffect(workspaceId) { viewModel.start(workspaceId) }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2_200)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("成员管理", style = Tokens.Type.pageTitle, color = colors.text)
                    Text(
                        text = buildString {
                            append(state.workspaceName.ifBlank { "当前工作区" })
                            append(" · ")
                            append(state.members.size)
                            append(" 名成员")
                            if (state.myRole.isNotBlank()) {
                                append(" · 我是")
                                append(roleLabel(state.myRole))
                            }
                        },
                        style = Tokens.Type.meta,
                        color = colors.text3,
                    )
                }
                if (state.canManage) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.full))
                            .background(colors.primary50)
                            .clickable(enabled = !state.busy) { inviteOpen = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.PersonAdd,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("邀请", style = Tokens.Type.chip, color = colors.primary)
                    }
                }
            }

            when {
                !state.signedIn -> EmptyState(
                    title = "成员管理需要登录服务器",
                    hint = "登录后可以邀请同事，按角色分配编辑或只读权限",
                    actionLabel = "返回设置登录",
                    onAction = onBack,
                )

                state.loading && state.members.isEmpty() -> SkeletonScreen(
                    modifier = Modifier.padding(top = 12.dp),
                )

                state.error != null && state.members.isEmpty() -> EmptyState(
                    title = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { viewModel.refresh() },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 96.dp,
                    ),
                ) {
                    items(state.members, key = { it.userId }) { member ->
                        MemberCard(
                            member = member,
                            onClick = {
                                if (state.canManage && !member.isSelf) actionTarget = member
                            },
                        )
                    }
                    item {
                        Text(
                            "邀请后对方需要登录并同步一次才能看到该工作区。",
                            style = Tokens.Type.meta,
                            color = colors.text3,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                }
            }
        }

        TodoToast(
            message = state.toast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }

    if (inviteOpen) {
        InviteDialog(
            busy = state.busy,
            onDismiss = { inviteOpen = false },
            onConfirm = { account, role ->
                viewModel.invite(account, role)
                inviteOpen = false
            },
        )
    }

    actionTarget?.let { member ->
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            MemberActionSheet(
                member = member,
                onRoleChange = { role ->
                    viewModel.setRole(member.userId, role)
                    actionTarget = null
                },
                onRemove = {
                    viewModel.remove(member.userId, member.name)
                    actionTarget = null
                },
            )
        }
    }
}

@Composable
private fun MemberCard(member: MemberRow, onClick: () -> Unit) {
    val colors = TodoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .border(1.dp, colors.border2, RoundedCornerShape(Tokens.Radius.md))
            .clickable(enabled = true, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colors.primary, colors.purple))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.initial.ifBlank { member.name.take(1) },
                style = Tokens.Type.section,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = member.name.ifBlank { member.account },
                    style = Tokens.Type.section,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.isSelf) {
                    StatusBadge("我", colors.primary50, colors.primary)
                }
            }
            if (member.account.isNotBlank()) {
                Text(
                    text = "@${member.account.removePrefix("@")}",
                    style = Tokens.Type.meta,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val (badgeBg, badgeFg) = when (member.role) {
            "owner" -> colors.warning50 to colors.warningFg
            "editor" -> colors.primary50 to colors.primary
            else -> colors.border2 to colors.text3
        }
        StatusBadge(roleLabel(member.role), badgeBg, badgeFg)
    }
}

@Composable
private fun MemberActionSheet(
    member: MemberRow,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = TodoTheme.colors
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = member.name.ifBlank { member.account },
            style = Tokens.Type.pageTitle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Text("角色", style = Tokens.Type.badge, color = colors.text3)
        Spacer(Modifier.height(6.dp))
        SegmentedControl(
            options = listOf("编辑者", "只读", "所有者"),
            selectedIndex = ROLE_VALUES.indexOf(member.role).coerceAtLeast(0),
            onSelected = { index -> onRoleChange(ROLE_VALUES[index]) },
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .background(colors.danger50)
                .clickable(onClick = onRemove)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "移出工作区",
                style = Tokens.Type.chip.copy(fontWeight = FontWeight.SemiBold),
                color = colors.danger,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InviteDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val colors = TodoTheme.colors
    var account by remember { mutableStateOf("") }
    var roleIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("邀请成员", style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("用户名或邮箱") },
                    singleLine = true,
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.card,
                        unfocusedContainerColor = colors.card,
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        cursorColor = colors.primary,
                        focusedLabelColor = colors.text2,
                        unfocusedLabelColor = colors.text3,
                    ),
                )
                Text("角色", style = Tokens.Type.badge, color = colors.text3)
                SegmentedControl(
                    options = listOf("编辑者", "只读", "所有者"),
                    selectedIndex = roleIndex,
                    onSelected = { roleIndex = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (account.isNotBlank()) onConfirm(account, ROLE_VALUES[roleIndex])
                },
            ) {
                Text("发送邀请", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.text2) }
        },
    )
}
