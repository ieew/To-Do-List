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

    private var started = false
    private var pollJob: Job? = null
    private var appTicket: String = ""
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

    /** 插件授权信息（provider 的 `dsh_authorize` 直接拿这个）。 */
    suspend fun authorizePayload(): Map<String, Any?> {
        runGuarded { ensureImeDevice() }
        val settings = settingsStore.current()
        val session = sessionManager.load()
        return mapOf(
            DshContract.EXTRA_AVAILABLE to (session != null && _status.value.imeDeviceId.isNotEmpty()),
            DshContract.EXTRA_SERVER to (session?.serverUrl ?: settings.serverUrl),
            DshContract.EXTRA_USER_ID to (session?.userId ?: ""),
            DshContract.EXTRA_DEVICE_ID to _status.value.imeDeviceId,
            DshContract.EXTRA_CREDENTIAL to (metaDao.get(IME_CREDENTIAL) ?: ""),
            DshContract.EXTRA_CREDENTIAL_EXP to (metaDao.get(IME_CREDENTIAL_EXP)?.toLongOrNull() ?: 0L),
            DshContract.EXTRA_DND_ENABLED to settings.dndEnabled,
            DshContract.EXTRA_DND_START to settings.dndStart,
            DshContract.EXTRA_DND_END to settings.dndEnd,
            DshContract.EXTRA_REMINDER_LEAD to settings.reminderLead,
            DshContract.EXTRA_NOTIFICATIONS to settings.notifications,
        )
    }

    /** 插件续期后回写轮换后的凭证；只接受更晚的过期时间，避免旧请求覆盖新值。 */
    suspend fun updateImeCredential(credential: String, expiresAt: Long) {
        if (credential.isBlank()) return
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
        if (connected) {
            _status.value = _status.value.copy(connected = true, lastError = "")
        } else if (error.isNotBlank()) {
            _status.value = _status.value.copy(connected = false, lastError = error)
        }
    }

    // ------------------------------------------------------------ 内部

    private suspend fun boot(forceReconnect: Boolean = false) {
        val settings = settingsStore.current()
        val session = sessionManager.load()
        if (!settings.dshChannelEnabled || session == null) {
            mqtt.disconnect()
            _status.value = _status.value.copy(enabled = false, mode = DshMode.OFF, connected = false)
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

        // 无论哪种形态，App 都要自己 pull/ack（插件只提供 nudge 与剪贴板）。
        ensureAppDevice()
        if (imeMode) {
            // 输入法负责实时推送；App 仍保留 60s 安全轮询，防止插件被系统冻结。
            mqtt.disconnect()
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

    /** 输入法设备：App 首次注册 + 签发凭证；之后插件自主续期，App 只在过期时补签。 */
    private suspend fun ensureImeDevice() {
        val storedId = metaDao.get(IME_DEVICE_ID)
        val deviceId = storedId ?: "dsh-ime-" + randomSuffix()
        val session = sessionManager.load() ?: return
        if (storedId == null) {
            when (val registered = apiCall(json) {
                api.register(
                    DshRegisterRequest(name = "输入法", platform = "android-ime", deviceId = deviceId),
                )
            }) {
                is ApiResult.Success -> metaDao.put(MetaEntity(IME_DEVICE_ID, registered.value.deviceId))
                is ApiResult.Failure -> {
                    _status.value = _status.value.copy(lastError = registered.failure.message)
                    return
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
                if (appTicket.isBlank()) return@runGuarded
                val endpoint = lastEndpoint() ?: return@runGuarded
                mqtt.connect(
                    endpoint = endpoint,
                    clientId = _status.value.appDeviceId,
                    ticket = appTicket,
                    keepAliveSeconds = 60,
                    onNudge = { pullNow("nudge") },
                    onState = { connected, detail ->
                        _status.value = _status.value.copy(
                            connected = connected,
                            lastError = if (connected) "" else detail,
                        )
                    },
                )
            }
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
        const val APP_MQTT = "dsh_app_mqtt"

        private const val POLL_INTERVAL_MS = 60_000L
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 20
        private const val CREDENTIAL_RENEW_MARGIN_MS = 3L * 86_400_000L
        private const val CLAIM_TTL_MS = 7L * 86_400_000L
    }
}
