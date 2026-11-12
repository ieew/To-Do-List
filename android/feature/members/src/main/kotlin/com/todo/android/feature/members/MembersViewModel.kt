package com.todo.android.feature.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.network.ApiResult
import com.todo.android.core.sync.MembersManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 成员行：远端返回的 username 优先，缺失时退回本地投影的邮箱。 */
data class MemberRow(
    val userId: String,
    val name: String,
    val initial: String,
    val color: String,
    val role: String,
    val account: String,
    val isSelf: Boolean,
)

data class MembersUiState(
    val loading: Boolean = true,
    val signedIn: Boolean = false,
    val workspaceName: String = "",
    val members: List<MemberRow> = emptyList(),
    val myRole: String = "",
    val currentUserId: String = "",
    val busy: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
) {
    val canManage: Boolean get() = signedIn && myRole == ROLE_OWNER

    companion object {
        const val ROLE_OWNER = "owner"
    }
}

@HiltViewModel
class MembersViewModel @Inject constructor(
    private val membersManager: MembersManager,
    private val projectionHolder: ProjectionHolder,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()

    private var workspaceId: String = ""
    private var bound = false

    fun start(id: String) {
        if (bound && workspaceId == id) {
            refresh()
            return
        }
        workspaceId = id
        bound = true
        applyLocal()
        refresh()
    }

    /** 先用本地投影渲染，联网结果回来后再覆盖。 */
    private fun applyLocal() {
        val projection = projectionHolder.projection.value
        _state.value = _state.value.copy(
            workspaceName = projection.workspaces.firstOrNull { it.id == workspaceId }?.name.orEmpty(),
            currentUserId = projection.currentUserId,
            myRole = projection.members.firstOrNull { it.userId == projection.currentUserId }?.role.orEmpty(),
            members = projection.members.map {
                MemberRow(
                    userId = it.userId,
                    name = it.name,
                    initial = it.initial,
                    color = it.color,
                    role = it.role,
                    account = it.email,
                    isSelf = it.userId == projection.currentUserId,
                )
            },
        )
    }

    fun refresh() {
        val signedIn = membersManager.isSignedIn()
        if (!signedIn) {
            applyLocal()
            _state.value = _state.value.copy(loading = false, signedIn = false, error = null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, signedIn = true, error = null)
            when (val result = membersManager.load(workspaceId)) {
                is ApiResult.Success -> {
                    val projection = projectionHolder.projection.value
                    _state.value = _state.value.copy(
                        loading = false,
                        currentUserId = projection.currentUserId,
                        myRole = projection.members
                            .firstOrNull { it.userId == projection.currentUserId }?.role.orEmpty(),
                        members = result.value.map { member ->
                            MemberRow(
                                userId = member.userId,
                                name = member.displayName.ifBlank { member.name },
                                initial = member.initial,
                                color = member.color,
                                role = member.role,
                                account = member.username.ifBlank { member.email },
                                isSelf = member.userId == projection.currentUserId,
                            )
                        },
                    )
                }

                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = result.failure.message.ifBlank { "加载成员失败" },
                    )
                }
            }
        }
    }

    fun invite(account: String, role: String) {
        if (account.isBlank()) {
            _state.value = _state.value.copy(toast = "请填写用户名或邮箱")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = membersManager.invite(workspaceId, account, role)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(busy = false, toast = "已邀请 $account")
                    refresh()
                }

                is ApiResult.Failure -> _state.value = _state.value.copy(busy = false, toast = result.failure.message)
            }
        }
    }

    fun setRole(userId: String, role: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = membersManager.setRole(workspaceId, userId, role)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(busy = false, toast = "角色已更新为${roleLabel(role)}")
                    refresh()
                }

                is ApiResult.Failure -> _state.value = _state.value.copy(busy = false, toast = result.failure.message)
            }
        }
    }

    fun remove(userId: String, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = membersManager.remove(workspaceId, userId)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(busy = false, toast = "已移除 $name")
                    refresh()
                }

                is ApiResult.Failure -> _state.value = _state.value.copy(busy = false, toast = result.failure.message)
            }
        }
    }

    fun showToast(message: String?) {
        _state.value = _state.value.copy(toast = message)
    }
}

fun roleLabel(role: String): String = when (role) {
    "owner" -> "所有者"
    "editor" -> "编辑者"
    "viewer" -> "只读"
    else -> role
}
