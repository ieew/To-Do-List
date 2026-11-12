package com.todo.android.core.data

import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.di.AppScope
import com.todo.android.core.domain.WorkspaceProjection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 投影持有者：UI 只读，任何写操作都必须回到 `OpRepository.appendOps`。
 * 投影由 op_log 的 Flow 驱动，写事务提交后自动重算并去重。
 */
@Singleton
class ProjectionHolder @Inject constructor(
    repository: OpRepository,
    @AppScope scope: CoroutineScope,
) {
    val projection: StateFlow<WorkspaceProjection> = repository.observeProjection()
        .stateIn(scope, SharingStarted.Eagerly, WorkspaceProjection.EMPTY)
}
