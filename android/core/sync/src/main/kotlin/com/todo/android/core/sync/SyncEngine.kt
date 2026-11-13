package com.todo.android.core.sync

import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.PulledOp
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.domain.Fields
import com.todo.android.core.domain.Op
import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.ServerConfig
import com.todo.android.core.network.api.SyncApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.session.SessionManager
import com.todo.android.core.network.session.SessionTokens
import com.todo.android.core.network.wire.PushRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * 同步唯一入口：`runOnce()` 用 Mutex 防重入，先 push 后 pull。
 * UI 不直接发 HTTP，只读 [state] 与 [notices]。
 */
@Singleton
class SyncEngine @Inject constructor(
    private val repository: OpRepository,
    private val syncApi: SyncApi,
    private val sessionManager: SessionManager,
    private val settingsStore: SettingsStore,
    private val serverConfig: ServerConfig,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    private val emittedNotices = mutableSetOf<String>()

    val state: StateFlow<SyncState> = _state.asStateFlow()
    val notices: SharedFlow<SyncNotice> = _notices.asSharedFlow()

    /** 首帧读取：投影由 ProjectionHolder 提供，这里只恢复同步状态与计数。 */
    suspend fun bootstrap(): SyncState {
        val session = sessionManager.load()
        val counts = repository.counts()
        val next = SyncState(
            status = if (session == null) SyncStatus.SIGNED_OUT else SyncStatus.SYNCED,
            account = session?.toAccount(),
            pendingCount = counts.pending,
            rejectedCount = counts.rejected,
            cursor = repository.syncCursor(),
        )
        _state.value = next
        return next
    }

    /**
     * 同步入口：任何未预期的异常都转成 OFFLINE 状态返回，
     * 不能抛给 `viewModelScope` —— 那样会直接把 App 打闪退，且 pending op 会让下次启动重复崩。
     */
    suspend fun runOnce(): SyncState = mutex.withLock {
        try {
            syncOnce()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            publish(
                SyncStatus.OFFLINE,
                null,
                error = "同步异常：" + (e.message ?: e.javaClass.simpleName).take(160),
            )
        }
    }

    private suspend fun syncOnce(): SyncState {
        val settings = settingsStore.current()
        var session = sessionManager.load()
        // 令牌是按某台服务器签发的，已登录时以会话里的地址为准，
        // 否则设置里的默认值会把刚登录用的地址顶掉。
        serverConfig.update(session?.serverUrl ?: settings.serverUrl)
        if (session == null) {
            return publish(SyncStatus.SIGNED_OUT)
        }
        publish(SyncStatus.SYNCING, session)

        session = sessionManager.ensureFresh()
        if (session == null) {
            return publish(SyncStatus.AUTH_REQUIRED, error = "登录已过期，请重新登录")
        }

        val pushStep = pushAll(session)
        if (pushStep is Step.Stop) return pushStep.state

        val pullStep = pullAll()
        if (pullStep is Step.Stop) return pullStep.state

        repairActiveWorkspace()
        return publish(SyncStatus.SYNCED, session, lastSyncedAt = System.currentTimeMillis())
    }

    fun cachedToken(): String? = sessionManager.cachedToken()

    /**
     * 兼容历史数据：早期版本把 epoch 毫秒（约 1.7e12）当成 order 写入 op，
     * 超出服务端 `|order| <= 1e9` 的约束，会导致整条 task 的 op 全部被拒。
     * 推送前归一化；本地 op 保持不可变语义，不修改已落库的值。
     */
    private fun normalizeLegacyOrder(op: Op): Op {
        if (op.field != Fields.ORDER) return op
        val primitive = op.value as? JsonPrimitive ?: return op
        val raw = primitive.content.toDoubleOrNull() ?: return op
        if (abs(raw) <= ORDER_LIMIT) return op
        return op.copy(value = JsonPrimitive(raw / 1_000_000.0))
    }

    private suspend fun pushAll(session: SessionTokens): Step {
        var batchLimit = PUSH_BATCH
        while (true) {
            val pending = repository.pendingOps(batchLimit)
            if (pending.isEmpty()) return Step.Continue

            val deviceId = repository.deviceId()
            val request = PushRequest(
                deviceId = deviceId,
                ops = pending.map { WireMapper.toOut(normalizeLegacyOrder(it.op)) },
            )
            when (val result = apiCall(json) { syncApi.push(request) }) {
                is ApiResult.Success -> {
                    val response = result.value
                    val synced = mutableListOf<Pair<String, Long>>()
                    response.results.forEach { item ->
                        when (item.status) {
                            "accepted", "duplicate" -> item.seq?.let { synced += item.opId to it }
                            "rejected" -> {
                                val code = item.error?.code ?: "rejected"
                                val message = item.error?.message ?: "操作未生效"
                                repository.markRejected(item.opId, "$code: $message")
                                notifyRejection(code, message)
                            }
                        }
                    }
                    repository.markSynced(synced)
                    repository.observeHlc(WireMapper.toDomain(response.serverHlc))
                }

                is ApiResult.Failure -> {
                    val failure = result.failure
                    when {
                        failure.code in AUTH_CODES -> {
                            sessionManager.clear()
                            return Step.Stop(publish(SyncStatus.AUTH_REQUIRED, null, error = failure.message))
                        }

                        failure.code == "hlc_too_far_ahead" -> {
                            failure.serverHlc?.let { repository.observeHlc(WireMapper.toDomain(it)) }
                            notice(NoticeKind.WARNING, failure.code, "本机时间偏差较大，已按服务器时间校正")
                        }

                        failure.httpStatus == 413 -> {
                            if (batchLimit > 1) {
                                batchLimit = maxOf(1, batchLimit / 2)
                            } else {
                                pending.forEach {
                                    repository.markRejected(it.op.opId, "${failure.code}: ${failure.message}")
                                }
                                notice(NoticeKind.ERROR, failure.code, "同步数据过大，已跳过无法提交的操作")
                                return Step.Continue
                            }
                        }

                        failure.httpStatus == 400 || failure.httpStatus == 403 -> {
                            pending.forEach {
                                repository.markRejected(it.op.opId, "${failure.code}: ${failure.message}")
                            }
                            notifyRejection(failure.code, failure.message)
                            return Step.Continue
                        }

                        failure.retryable -> {
                            val status = if (failure.httpStatus == 0) SyncStatus.OFFLINE else SyncStatus.RETRYING
                            return Step.Stop(publish(status, session, error = failure.message))
                        }

                        else -> {
                            pending.forEach {
                                repository.markRejected(it.op.opId, "${failure.code}: ${failure.message}")
                            }
                            notifyRejection(failure.code, failure.message)
                            return Step.Continue
                        }
                    }
                }
            }
        }
    }

    private suspend fun pullAll(): Step {
        var page = 0
        while (page < MAX_PULL_PAGES) {
            val cursor = repository.syncCursor()
            when (val result = apiCall(json) { syncApi.pull(sinceSeq = cursor, limit = PULL_LIMIT) }) {
                is ApiResult.Success -> {
                    val response = result.value
                    val pulled = response.ops.mapNotNull { wire ->
                        WireMapper.toDomain(wire)?.let { PulledOp(it, wire.seq ?: response.nextSeq) }
                    }
                    repository.applyPulled(
                        ops = pulled,
                        nextSeq = response.nextSeq,
                        serverHlc = response.serverHlc?.let(WireMapper::toDomain),
                    )
                    page++
                    if (!response.hasMore) return Step.Continue
                }

                is ApiResult.Failure -> {
                    val failure = result.failure
                    return when {
                        failure.code in AUTH_CODES -> {
                            sessionManager.clear()
                            Step.Stop(publish(SyncStatus.AUTH_REQUIRED, null, error = failure.message))
                        }

                        failure.retryable -> {
                            val status = if (failure.httpStatus == 0) SyncStatus.OFFLINE else SyncStatus.RETRYING
                            Step.Stop(publish(status, null, error = failure.message))
                        }

                        else -> {
                            notifyRejection(failure.code, failure.message)
                            Step.Continue
                        }
                    }
                }
            }
        }
        return Step.Continue
    }

    /** pull 落库后修正 active_workspace_id：不存在则回落到投影中的第一个工作区。 */
    private suspend fun repairActiveWorkspace() {
        val projection = repository.observeProjection().first()
        val active = repository.activeWorkspaceId()
        if (active == null || projection.workspaces.none { it.id == active }) {
            projection.workspaces.firstOrNull()?.let { repository.setActiveWorkspaceId(it.id) }
        }
    }

    private suspend fun publish(
        status: SyncStatus,
        session: SessionTokens? = null,
        lastSyncedAt: Long? = null,
        error: String? = null,
    ): SyncState {
        val counts = repository.counts()
        val next = SyncState(
            status = status,
            account = session?.toAccount() ?: _state.value.account,
            pendingCount = counts.pending,
            rejectedCount = counts.rejected,
            lastSyncedAt = lastSyncedAt ?: _state.value.lastSyncedAt,
            lastError = error,
            cursor = repository.syncCursor(),
        )
        _state.value = next
        return next
    }

    private fun notifyRejection(code: String, message: String) {
        val kind = if (code == "hlc_too_far_ahead") NoticeKind.WARNING else NoticeKind.ERROR
        notice(kind, code, message)
    }

    private fun notice(kind: NoticeKind, code: String, message: String) {
        if (emittedNotices.add("$kind:$code")) {
            _notices.tryEmit(SyncNotice(kind, code, message))
        }
    }

    private fun SessionTokens.toAccount(): AccountSnapshot =
        AccountSnapshot(userId = userId, username = username, displayName = displayName, serverUrl = serverUrl)

    private sealed interface Step {
        data object Continue : Step

        data class Stop(val state: SyncState) : Step
    }

    companion object {
        const val PUSH_BATCH = 100
        const val PULL_LIMIT = 200
        const val MAX_PULL_PAGES = 20

        /** 与服务端 `_is_number` 一致的上限。 */
        private const val ORDER_LIMIT = 1_000_000_000.0

        private val AUTH_CODES = setOf(
            "unauthorized",
            "token_expired",
            "invalid_refresh_token",
            "actor_mismatch",
        )
    }
}
