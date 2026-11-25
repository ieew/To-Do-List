package com.todo.android.core.dsh

import android.content.Context
import android.provider.Settings
import com.todo.android.core.data.db.DshClaimDao
import com.todo.android.core.data.db.DshClaimEntity
import com.todo.android.core.data.db.MetaDao
import com.todo.android.core.data.db.MetaEntity
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.api.DshApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.session.SessionManager
import com.todo.android.core.network.wire.DshAckRequest
import com.todo.android.core.network.wire.DshMessage
import com.todo.android.core.network.wire.DshRegisterRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 新数据通道（dsh）在 Android 端的唯一入口。
 *
 * 职责边界：
 * - **设备与凭证**：App 自己的 `dsh-app-*` 设备用账号 Bearer 续期；输入法插件用
 *   `dsh-ime-*` 设备 + 设备级凭证，由 App 首次签发、插件自主续期（见 server-protocol §15.12）。
 * - **消息**：pull → 发到 [messages] → ack → 推进游标。通知/提醒如何展示由 `core:reminder` 决定。
 * - **通道选择**：输入法是默认输入法且开关打开 → 插件负责实时 nudge，App 只做安全轮询；
 *   否则 App 自建 MQTT；再不行退化为轮询。
 * - **去重**：`dsh_claim` 表做「单一展示者」，App 与插件都通过同一个 provider 认领。
 */
@Singleton
class DshManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DshApi,
    private val sessionManager: SessionManager,
    private val settingsStore: SettingsStore,
    private val metaDao: MetaDao,
    private val claimDao: DshClaimDao,
    private val mqtt: DshMqtt,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(DshStatus())
    val status = _status.asStateFlow()

    private val _messages = MutableSharedFlow<DshMessage>(extraBufferCapacity = 128)
    val messages = _messages.asSharedFlow()

    /** 「其它设备写了数据」的信号：上层收到后立即 `syncEngine.runOnce()`。 */
    private val _syncSignals = MutableSharedFlow<DshSyncSignal>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val syncSignals = _syncSignals.asSharedFlow()

    private var started = false
    private var pollJob: Job? = null
    /** boot() 可能被多处并发触发；注册/签凭证串行化，避免一次启动注册出两个设备。 */
    private val deviceLock = Mutex()
    private var appTicket: String = ""
    /** §5.3：自管重连的退避状态。 */
    private var reconnectJob: Job? = null
    @Volatile
    private var failStreak: Int = 0
    private var appTicketExpiresAt: Long = 0L

    /** 由 Application / KeepAliveService 调用；重复调用只会重新确认一次状态。 */
    @Synchronized
    fun start() {
        started = true
        scope.launch { runGuarded { boot() } }
    }

    @Synchronized
    fun stop() {
        started = false
        pollJob?.cancel()
        pollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        failStreak = 0
        mqtt.disconnect()
        _status.value = _status.value.copy(enabled = false, mode = DshMode.OFF, connected = false)
    }

    /** 账号切换 / 退出登录时清空通道状态（凭证哈希已在服务端随吊销/重新注册失效）。 */
    suspend fun reset() {
        runGuarded {
            mqtt.disconnect()
            listOf(
                APP_DEVICE_ID,
                IME_DEVICE_ID,
                IME_CREDENTIAL,
                IME_CREDENTIAL_EXP,
                CURSOR,
                LAST_PULL_AT,
                IME_STATUS,
                IME_REVOKED_AT,
                IME_REVOKE_PENDING,
            ).forEach { metaDao.delete(it) }
            _status.value = DshStatus()
        }
    }

    fun refreshNow() {
        scope.launch { runGuarded { boot(forceReconnect = true) } }
    }

    /** 插件发来 nudge / 用户手动刷新时调用。 */
    fun pullNow(source: String = "manual") {
        scope.launch {
            runGuarded {
                if (source == "nudge") {
                    _status.value = _status.value.copy(lastNudgeAt = System.currentTimeMillis())
                }
                pullUntilIdle()
            }
        }
    }

    /** 数据变更信号：让 op 同步立即跑一轮（由 AppViewModel 收集）。 */
    fun requestDataSync(seq: Long = 0L, workspaces: List<String> = emptyList()) {
        _syncSignals.tryEmit(DshSyncSignal(seq = seq, workspaces = workspaces))
    }

    /**
     * 单一展示者：`INSERT OR IGNORE` 成功才返回 true。
     * 输入法插件通过 provider 调用到这里，App 内部也走这里。
     */
    suspend fun claimReminder(
        workspaceId: String,
        taskId: String,
        remindAt: Long,
        source: String,
    ): Boolean {
        if (taskId.isBlank() || remindAt <= 0) return false
        val key = "$workspaceId|$taskId|$remindAt"
        val rowId = claimDao.claim(
            DshClaimEntity(dedupeKey = key, claimedAt = System.currentTimeMillis(), source = source),
        )
        claimDao.prune(System.currentTimeMillis() - CLAIM_TTL_MS)
        return rowId > 0
    }

    // ------------------------------------------------------------ 撤销输入法通道（§5.5）

    /**
     * 撤销输入法通道。权威在服务端：本地先停发、再调 revoke。
     *
     * 顺序不能反：先断本地（清凭证、写 pending）再请求服务端，这样即使请求失败，
     * 插件也拿不到新凭证；失败时状态停在 [DshStatus.imeRevokePending]，由下次联网补调。
     *
     * **绝不删除 [IME_DEVICE_ID]**：删了下次启动会造一台全新设备并重新签发，撤销当场失效。
     */
    suspend fun revokeIme(): Boolean {
        val deviceId = metaDao.get(IME_DEVICE_ID).orEmpty().takeIf { it.isNotBlank() }
        stopImeLocally()
        metaDao.put(MetaEntity(IME_REVOKE_PENDING, "1"))
        refreshRevokeState()
        if (deviceId == null) {
            // 本地没有设备记录时没有可吊销的远端对象，但用户的意图是「关掉这条通道」：
            // 必须落 revoked_at，否则 authorizePayload 的 closed 判断不成立，
            // 插件下一个 dsh_authorize 还能拿到新凭证 —— 点了撤销却什么都没停。
            metaDao.delete(IME_REVOKE_PENDING)
            metaDao.put(MetaEntity(IME_REVOKED_AT, System.currentTimeMillis().toString()))
            refreshRevokeState()
            return true
        }
        return when (val result = apiCall(json) { api.revoke(deviceId) }) {
            is ApiResult.Success -> {
                metaDao.delete(IME_REVOKE_PENDING)
                metaDao.put(MetaEntity(IME_REVOKED_AT, System.currentTimeMillis().toString()))
                refreshRevokeState()
                true
            }
            is ApiResult.Failure -> {
                // 服务端大概率已经有这条设备，404 说明它本来就不存在 —— 也算撤干净。
                if (result.failure.httpStatus == 404) {
                    metaDao.delete(IME_REVOKE_PENDING)
                    metaDao.put(MetaEntity(IME_REVOKED_AT, System.currentTimeMillis().toString()))
                    refreshRevokeState()
                    true
                } else {
                    _status.value = _status.value.copy(lastError = result.failure.message)
                    false
                }
            }
        }
    }

    /** 断网撤销的补调入口；成功后才落 revoked_at，进而在界面上从「待同步」变「已撤销」。 */
    suspend fun retryPendingRevoke(): Boolean {
        if (metaDao.get(IME_REVOKE_PENDING) != "1") return false
        val deviceId = metaDao.get(IME_DEVICE_ID).orEmpty().takeIf { it.isNotBlank() } ?: run {
            metaDao.delete(IME_REVOKE_PENDING)
            refreshRevokeState()
            return true
        }
        return when (val result = apiCall(json) { api.revoke(deviceId) }) {
            is ApiResult.Success -> {
                metaDao.delete(IME_REVOKE_PENDING)
                metaDao.put(MetaEntity(IME_REVOKED_AT, System.currentTimeMillis().toString()))
                refreshRevokeState()
                true
            }
            is ApiResult.Failure -> {
                if (result.failure.httpStatus == 404) {
                    metaDao.delete(IME_REVOKE_PENDING)
                    metaDao.put(MetaEntity(IME_REVOKED_AT, System.currentTimeMillis().toString()))
                    refreshRevokeState()
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * 重新启用：旧的 `dsh-ime-*` 已 revoked 且不能再次注册（409），所以必须换新 device_id。
     * pending 期间禁止调用 —— 否则会出现「旧设备没撤干净又发了一张新凭证」的空档。
     */
    suspend fun reenableIme(): Boolean {
        // 注意：不要在这里拿 deviceLock —— ensureImeDevice() 自己会拿，Mutex 不可重入。
        if (metaDao.get(IME_REVOKE_PENDING) == "1") return false
        metaDao.delete(IME_DEVICE_ID)
        metaDao.delete(IME_REVOKED_AT)
        metaDao.delete(IME_CREDENTIAL)
        metaDao.delete(IME_CREDENTIAL_EXP)
        metaDao.delete(IME_STATUS)
        refreshRevokeState()
        ensureImeDevice()
        return metaDao.get(IME_CREDENTIAL)?.isNotBlank() == true
    }

    /** 撤销时本地先停：凭证、过期时间、状态一律清掉；device_id 保留。 */
    private suspend fun stopImeLocally() {
        metaDao.delete(IME_CREDENTIAL)
        metaDao.delete(IME_CREDENTIAL_EXP)
        metaDao.delete(IME_STATUS)
        _status.value = _status.value.copy(connected = false)
    }

    private suspend fun refreshRevokeState() {
        _status.value = _status.value.copy(
            imeRevokedAt = metaDao.get(IME_REVOKED_AT)?.toLongOrNull() ?: 0L,
            imeRevokePending = metaDao.get(IME_REVOKE_PENDING) == "1",
        )
    }

    private suspend fun imeChannelClosed(): Boolean {
        val pending = metaDao.get(IME_REVOKE_PENDING) == "1"
        val revoked = (metaDao.get(IME_REVOKED_AT)?.toLongOrNull() ?: 0L) > 0L
        return pending || revoked
    }

    /** 插件授权信息（provider 的 `dsh_authorize` 直接拿这个）。 */
    suspend fun authorizePayload(): Map<String, Any?> {
        runGuarded { ensureImeDevice() }
        val settings = settingsStore.current()
        val session = sessionManager.load()
        val closed = imeChannelClosed()
        return mapOf(
            DshContract.EXTRA_AVAILABLE to (
                !closed && session?.hasCredentials == true && _status.value.imeDeviceId.isNotEmpty()
                ),
            DshContract.EXTRA_SERVER to (session?.serverUrl ?: settings.serverUrl),
            DshContract.EXTRA_USER_ID to (session?.userId ?: ""),
            DshContract.EXTRA_DEVICE_ID to _status.value.imeDeviceId,
            DshContract.EXTRA_CREDENTIAL to if (closed) "" else (metaDao.get(IME_CREDENTIAL) ?: ""),
            DshContract.EXTRA_CREDENTIAL_EXP to if (closed) {
                0L
            } else {
                metaDao.get(IME_CREDENTIAL_EXP)?.toLongOrNull() ?: 0L
            },
            DshContract.EXTRA_DND_ENABLED to settings.dndEnabled,
            DshContract.EXTRA_DND_START to settings.dndStart,
            DshContract.EXTRA_DND_END to settings.dndEnd,
            DshContract.EXTRA_REMINDER_LEAD to settings.reminderLead,
            DshContract.EXTRA_NOTIFICATIONS to settings.notifications,
        )
    }

    /** 插件续期后回写轮换后的凭证；只接受更晚的过期时间，避免旧请求覆盖新值。
     *  空凭证表示「插件明确报告失效」，此时清掉映射，下次 authorize 会重新签发。 */
    suspend fun updateImeCredential(credential: String, expiresAt: Long) {
        if (credential.isBlank()) {
            metaDao.delete(IME_CREDENTIAL)
            metaDao.delete(IME_CREDENTIAL_EXP)
            return
        }
        val currentExp = metaDao.get(IME_CREDENTIAL_EXP)?.toLongOrNull() ?: 0L
        if (expiresAt in 1 until currentExp) return
        metaDao.put(MetaEntity(IME_CREDENTIAL, credential))
        metaDao.put(MetaEntity(IME_CREDENTIAL_EXP, expiresAt.toString()))
    }

    /** 插件把 MQTT 连接状态回写，设置页据此显示「已连接 / 未连接」。 */
    suspend fun noteImeStatus(connected: Boolean, error: String) {
        metaDao.put(
            MetaEntity(IME_STATUS, json.encodeToString(ImeStatus.serializer(), ImeStatus(connected, error))),
        )
        _status.value = _status.value.copy(
            connected = connected,
            lastError = if (connected) "" else error,
        )
    }

    // ------------------------------------------------------------ 内部

    private suspend fun boot(forceReconnect: Boolean = false) {
        val settings = settingsStore.current()
        val session = sessionManager.load()
        if (!settings.dshChannelEnabled || session?.hasCredentials != true) {
            mqtt.disconnect()
            _status.value = _status.value.copy(
                enabled = false,
                mode = DshMode.OFF,
                connected = false,
                // 通道关着也要显示真实的设备号：否则界面上永远是「未授权」，
                // 撤销时到底有没有可吊销的对象就看不出来了。
                appDeviceId = metaDao.get(APP_DEVICE_ID).orEmpty(),
                imeDeviceId = metaDao.get(IME_DEVICE_ID).orEmpty(),
            )
            // 通道关着也可能已经撤销过（撤销是终态），界面要能显示出来。
            refreshRevokeState()
            return
        }
        val cursor = metaDao.get(CURSOR)?.toLongOrNull() ?: 0L
        val imeMode = settings.dshPreferIme && isTrimeDefaultIme()
        _status.value = _status.value.copy(
            enabled = true,
            mode = if (imeMode) DshMode.IME else DshMode.SELF,
            cursor = cursor,
            lastPullAt = metaDao.get(LAST_PULL_AT)?.toLongOrNull() ?: 0L,
        )
        // 断网撤销的补调：先于任何签发动作，避免在「服务端还没吊销」时又续出一张新凭证。
        runGuarded { retryPendingRevoke() }
        refreshRevokeState()

        // 无论哪种形态，App 都要自己 pull/ack（插件只提供 nudge 与剪贴板）。
        ensureAppDevice()
        if (imeMode) {
            // 输入法负责实时推送；App 仍保留 60s 安全轮询，防止插件被系统冻结。
            mqtt.disconnect()
            // IME 模式下 App 自己不建长连接，connected 只由插件回写（dsh_status）而来；
            // 进程重启后先恢复最后已知状态，否则界面会一直显示「未连接」。
            val known = lastImeStatus()
            _status.value = _status.value.copy(
                connected = known?.connected == true,
                lastError = known?.error.orEmpty(),
            )
            runGuarded { ensureImeDevice() }
        } else if (forceReconnect || !mqtt.connected) {
            connectMqtt()
        }
        startPolling()
        runGuarded { pullUntilIdle() }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runGuarded { pullUntilIdle() }
            }
        }
    }

    private suspend fun ensureAppDevice() {
        deviceLock.withLock {
            val storedId = metaDao.get(APP_DEVICE_ID)
            val deviceId = storedId ?: "dsh-app-" + randomSuffix()
            val result = apiCall(json) {
                api.register(
                    DshRegisterRequest(name = androidDeviceName(), platform = "android", deviceId = deviceId),
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    metaDao.put(MetaEntity(APP_DEVICE_ID, result.value.deviceId))
                    metaDao.put(
                        MetaEntity(
                            APP_MQTT,
                            json.encodeToString(
                                com.todo.android.core.network.wire.DshMqttEndpoint.serializer(),
                                result.value.mqtt,
                            ),
                        ),
                    )
                    appTicket = result.value.ticket
                    appTicketExpiresAt = result.value.ticketExpiresAt
                    _status.value = _status.value.copy(appDeviceId = result.value.deviceId)
                }

                is ApiResult.Failure -> {
                    _status.value = _status.value.copy(lastError = result.failure.message)
                }
            }
        }
    }

    /** 输入法设备：App 首次注册 + 签发凭证；之后插件自主续期，App 只在过期时补签。 */
    private suspend fun ensureImeDevice() = deviceLock.withLock {
        // 已撤销（或撤销待同步）的设备不能再签发：服务端会 409，本地也不该留凭证。
        if (imeChannelClosed()) {
            _status.value = _status.value.copy(imeDeviceId = metaDao.get(IME_DEVICE_ID).orEmpty())
            return@withLock
        }
        val storedId = metaDao.get(IME_DEVICE_ID)?.takeIf { it.isNotBlank() }
        val deviceId = storedId ?: "dsh-ime-" + randomSuffix()
        val session = sessionManager.load() ?: return@withLock
        if (storedId == null) {
            when (val registered = apiCall(json) {
                api.register(
                    DshRegisterRequest(name = "输入法", platform = "android-ime", deviceId = deviceId),
                )
            }) {
                is ApiResult.Success -> metaDao.put(MetaEntity(IME_DEVICE_ID, registered.value.deviceId))
                is ApiResult.Failure -> {
                    _status.value = _status.value.copy(lastError = registered.failure.message)
                    return@withLock
                }
            }
        }
        val credential = metaDao.get(IME_CREDENTIAL)
        val expiresAt = metaDao.get(IME_CREDENTIAL_EXP)?.toLongOrNull() ?: 0L
        val needIssue = credential.isNullOrBlank() ||
            expiresAt - System.currentTimeMillis() < CREDENTIAL_RENEW_MARGIN_MS
        if (needIssue && session.userId.isNotBlank()) {
            val target = metaDao.get(IME_DEVICE_ID).orEmpty().ifBlank { deviceId }
            when (val issued = apiCall(json) { api.issueCredential(target) }) {
                is ApiResult.Success -> {
                    metaDao.put(MetaEntity(IME_CREDENTIAL, issued.value.credential))
                    metaDao.put(MetaEntity(IME_CREDENTIAL_EXP, issued.value.credentialExpiresAt.toString()))
                }

                is ApiResult.Failure -> {
                    _status.value = _status.value.copy(lastError = issued.failure.message)
                }
            }
        }
        val finalId = metaDao.get(IME_DEVICE_ID).orEmpty()
        _status.value = _status.value.copy(imeDeviceId = finalId)
    }

    private fun connectMqtt() {
        scope.launch {
            runGuarded {
                if (!started) return@runGuarded
                // 每次重连前先看票还在不在：Paho 不会替我们续期，带着过期票一定是 421/401
                if (appTicket.isBlank() || appTicketExpiresAt - System.currentTimeMillis() < TICKET_MARGIN_MS) {
                    ensureAppDevice()
                }
                if (appTicket.isBlank()) {
                    scheduleReconnect()
                    return@runGuarded
                }
                val endpoint = lastEndpoint() ?: return@runGuarded
                mqtt.connect(
                    endpoint = endpoint,
                    clientId = _status.value.appDeviceId,
                    ticket = appTicket,
                    keepAliveSeconds = 60,
                    onNudge = { pullNow("nudge") },
                    onSync = { signal -> requestDataSync(signal.seq, signal.workspaces) },
                    onState = { connected, detail ->
                        _status.value = _status.value.copy(
                            connected = connected,
                            lastError = if (connected) "" else detail,
                        )
                        if (connected) {
                            failStreak = 0
                            reconnectJob?.cancel()
                            reconnectJob = null
                        } else if (started) {
                            scheduleReconnect()
                        }
                    },
                )
            }
        }
    }

    /**
     * §5.2/§5.3：自己接管重连。
     *
     * delay = min(2s * 2^n, 60s) * (0.7 + random*0.6)。jitter 不是装饰：服务端重启时所有设备
     * 会在同一秒掉线，没有抖动就会在同一秒一起回来，把一次重启放大成一次雪崩。
     */
    @Synchronized
    private fun scheduleReconnect() {
        if (!started) return
        if (reconnectJob?.isActive == true) return
        val base = 2_000L * (1L shl failStreak.coerceIn(0, 5))
        val delayMs = (minOf(base, 60_000L) * (0.7 + kotlin.random.Random.nextDouble() * 0.6)).toLong()
        failStreak += 1
        reconnectJob = scope.launch {
            delay(delayMs)
            connectMqtt()
        }
    }

    private suspend fun pullUntilIdle() {
        if (!_status.value.enabled) return
        val deviceId = _status.value.appDeviceId.ifBlank { metaDao.get(APP_DEVICE_ID).orEmpty() }
        if (deviceId.isBlank()) return
        var cursor = metaDao.get(CURSOR)?.toLongOrNull() ?: 0L
        var pages = 0
        while (pages < MAX_PAGES) {
            val result = apiCall(json) { api.pull(deviceId = deviceId, since = cursor, limit = PAGE_SIZE) }
            val page = when (result) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> {
                    _status.value = _status.value.copy(lastError = result.failure.message)
                    if (result.failure.httpStatus == 401) {
                        // Bearer 失效由 authenticator 负责；设备侧则重新注册一次。
                        ensureAppDevice()
                    }
                    return
                }
            }
            val accepted = mutableListOf<Long>()
            page.messages.forEach { message ->
                _messages.tryEmit(message)
                accepted.add(message.seq)
            }
            if (accepted.isNotEmpty()) {
                when (val ack = apiCall(json) { api.ack(DshAckRequest(deviceId, accepted)) }) {
                    is ApiResult.Success -> Unit
                    is ApiResult.Failure -> {
                        _status.value = _status.value.copy(lastError = ack.failure.message)
                        return
                    }
                }
            }
            cursor = maxOf(cursor, page.nextSeq)
            metaDao.put(MetaEntity(CURSOR, cursor.toString()))
            _status.value = _status.value.copy(
                cursor = cursor,
                lastPullAt = System.currentTimeMillis(),
                lastError = "",
            )
            metaDao.put(MetaEntity(LAST_PULL_AT, System.currentTimeMillis().toString()))
            pages += 1
            if (!page.hasMore) break
        }
    }

    private suspend fun lastImeStatus() = runCatching {
        metaDao.get(IME_STATUS)?.let { json.decodeFromString(ImeStatus.serializer(), it) }
    }.getOrNull()

    private suspend fun lastEndpoint() = runCatching {
        metaDao.get(APP_MQTT)?.let {
            json.decodeFromString(com.todo.android.core.network.wire.DshMqttEndpoint.serializer(), it)
        }
    }.getOrNull()

    private fun isTrimeDefaultIme(): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty()
            .contains(DshContract.IME_HOST_PACKAGE)
    }.getOrDefault(false)

    private fun androidDeviceName(): String =
        (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: android.os.Build.MODEL ?: "Android").take(48)

    private fun randomSuffix(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend inline fun runGuarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // 通道是旁路：任何异常都不能影响主流程（尤其不能闪退）。
        }
    }

    @Serializable
    private data class ImeStatus(val connected: Boolean, val error: String)
    companion object {
        const val APP_DEVICE_ID = "dsh_app_device_id"
        const val IME_DEVICE_ID = "dsh_ime_device_id"
        const val IME_CREDENTIAL = "dsh_ime_credential"
        const val IME_CREDENTIAL_EXP = "dsh_ime_credential_exp"
        const val CURSOR = "dsh_cursor"
        const val LAST_PULL_AT = "dsh_last_pull_at"
        const val IME_STATUS = "dsh_ime_status"
        const val IME_REVOKED_AT = "dsh_ime_revoked_at"
        const val IME_REVOKE_PENDING = "dsh_ime_revoke_pending"
        const val APP_MQTT = "dsh_app_mqtt"

        /** §5.3：票据剩余不足这么久就先续期，留出一次握手的时间。 */
        const val TICKET_MARGIN_MS = 60_000L

        private const val POLL_INTERVAL_MS = 60_000L
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 20
        private const val CREDENTIAL_RENEW_MARGIN_MS = 3L * 86_400_000L
        private const val CLAIM_TTL_MS = 7L * 86_400_000L
    }
}
