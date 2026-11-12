package com.todo.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.ai.AiKeyStore
import com.todo.android.core.data.backup.BackupService
import com.todo.android.core.data.backup.ImportPreview
import com.todo.android.core.data.settings.AppSettings
import com.todo.android.core.data.settings.KeepAliveModes
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.data.settings.StartupModes
import com.todo.android.core.data.settings.ThemeModes
import com.todo.android.core.domain.WorkspaceProjection
import com.todo.android.core.network.ApiResult
import com.todo.android.core.reminder.KeepAliveManager
import com.todo.android.core.reminder.KeepAliveStats
import com.todo.android.core.reminder.ReminderScheduler
import com.todo.android.core.sync.AccountManager
import com.todo.android.core.sync.SyncEngine
import com.todo.android.core.sync.SyncState
import com.todo.android.core.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sync: SyncState = SyncState(),
    val projection: WorkspaceProjection = WorkspaceProjection.EMPTY,
    val toast: String? = null,
    val busy: Boolean = false,
    val aiKeyConfigured: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
    val notificationAllowed: Boolean = false,
    val importPreview: ImportPreview? = null,
    val databaseSize: String = "0 KB",
    /** 已加入电池优化白名单（false 表示可能被系统限制后台）。 */
    val batteryWhitelisted: Boolean = false,
    val keepAliveLastBeatMs: Long? = null,
    val keepAliveBeatCount: Long = 0L,
) {
    val signedIn: Boolean get() = sync.status != SyncStatus.SIGNED_OUT
    val accountName: String get() = sync.account?.displayName ?: sync.account?.username ?: "未登录"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val syncEngine: SyncEngine,
    private val accountManager: AccountManager,
    private val projectionHolder: ProjectionHolder,
    private val aiKeyStore: AiKeyStore,
    private val reminderScheduler: ReminderScheduler,
    private val keepAliveManager: KeepAliveManager,
    private val backupService: BackupService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private val aiKeyConfigured = MutableStateFlow(false)
    private val reminderPermissions = MutableStateFlow(false to false)
    private val importPreview = MutableStateFlow<ImportPreview?>(null)
    private val databaseSize = MutableStateFlow("0 KB")
    private val keepAliveInfo = MutableStateFlow(KeepAliveInfo())

    private data class LocalState(
        val toast: String? = null,
        val busy: Boolean = false,
    )

    private data class LocalBundle(
        val local: LocalState,
        val permissions: Pair<Boolean, Boolean>,
        val preview: ImportPreview?,
        val databaseSize: String,
        val keepAlive: KeepAliveInfo,
    )

    private data class KeepAliveInfo(
        val batteryWhitelisted: Boolean = false,
        val lastBeatMs: Long? = null,
        val beatCount: Long = 0L,
    )

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.observe(),
        syncEngine.state,
        projectionHolder.projection,
        combine(local, reminderPermissions, importPreview, databaseSize, keepAliveInfo) { localState, permissions, preview, size, keepAlive ->
            LocalBundle(localState, permissions, preview, size, keepAlive)
        },
        aiKeyConfigured,
    ) { settings, sync, projection, bundle, keyConfigured ->
        SettingsUiState(
            settings = settings,
            sync = sync,
            projection = projection,
            toast = bundle.local.toast,
            busy = bundle.local.busy,
            aiKeyConfigured = keyConfigured,
            exactAlarmAllowed = bundle.permissions.first,
            notificationAllowed = bundle.permissions.second,
            importPreview = bundle.preview,
            databaseSize = bundle.databaseSize,
            batteryWhitelisted = bundle.keepAlive.batteryWhitelisted,
            keepAliveLastBeatMs = bundle.keepAlive.lastBeatMs,
            keepAliveBeatCount = bundle.keepAlive.beatCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { aiKeyConfigured.value = aiKeyStore.hasKey() }
        refreshReminderPermissions()
        refreshKeepAliveInfo()
        databaseSize.value = databaseSizeText()
    }

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { backupService.export(uri) }
                .onSuccess { summary -> local.value = local.value.copy(toast = "已导出 ${summary.ops} 条 op") }
                .onFailure { local.value = local.value.copy(toast = "导出失败：${it.message}") }
        }
    }

    fun inspectBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            val preview = runCatching { backupService.inspect(uri) }.getOrNull()
            if (preview == null) {
                local.value = local.value.copy(toast = "备份文件无效或为空")
            } else {
                importPreview.value = preview
            }
        }
    }

    fun confirmImport() {
        val preview = importPreview.value ?: return
        viewModelScope.launch {
            val result = runCatching { backupService.import(preview) }.getOrNull()
            importPreview.value = null
            local.value = local.value.copy(
                toast = if (result == null) {
                    "导入失败"
                } else {
                    "已导入 ${result.inserted} 条 op（重复跳过 ${result.skipped} 条）"
                },
            )
        }
    }

    fun cancelImport() {
        importPreview.value = null
    }

    fun clearCache() {
        local.value = local.value.copy(toast = "已清理本地缓存（op_log 保留，重启后重新投影）")
    }

    private fun databaseSizeText(): String {
        val name = com.todo.android.core.data.db.TodoDatabase.NAME
        val bytes = context.getDatabasePath(name).length() + context.getDatabasePath("$name-wal").length()
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }

    fun refreshReminderPermissions() {
        reminderPermissions.value = reminderScheduler.canScheduleExact() to reminderScheduler.canPostNotifications()
    }

    fun setTheme(mode: String) {
        viewModelScope.launch { settingsStore.update { it.copy(theme = mode) } }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(reduceMotion = enabled) } }
    }

    fun setStartupMode(mode: String) {
        viewModelScope.launch { settingsStore.update { it.copy(startup = mode) } }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch { settingsStore.update { it.copy(syncIntervalMinutes = minutes) } }
    }

    fun syncNow() {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            val result = syncEngine.runOnce()
            local.value = local.value.copy(
                busy = false,
                toast = when (result.status) {
                    SyncStatus.SYNCED -> "同步完成"
                    SyncStatus.SIGNED_OUT -> "请先登录后再同步"
                    SyncStatus.AUTH_REQUIRED -> "登录已过期，请重新登录"
                    else -> result.lastError ?: "网络不稳定，稍后重试"
                },
            )
        }
    }

    fun login(serverUrl: String, username: String, password: String, register: Boolean) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            val result = if (register) {
                accountManager.register(username, password, null, serverUrl)
            } else {
                accountManager.login(username, password, serverUrl)
            }
            local.value = local.value.copy(
                busy = false,
                toast = when (result) {
                    is ApiResult.Success ->
                        if (register) "注册成功，已同步" else "登录成功，已同步"

                    is ApiResult.Failure -> result.failure.message
                },
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            accountManager.logout()
            local.value = local.value.copy(toast = "已退出登录（本地数据保留）")
        }
    }

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(aiEnabled = enabled) } }
    }

    fun setAiImageInput(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(aiImageInput = enabled) } }
    }

    fun setAiEndpoint(endpoint: String) {
        viewModelScope.launch { settingsStore.update { it.copy(aiEndpoint = endpoint.trim()) } }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch { settingsStore.update { it.copy(aiModel = model.trim()) } }
    }

    fun setAiThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsStore.update { it.copy(aiConfirmThreshold = threshold.coerceIn(1, 100)) }
        }
    }

    fun saveAiKey(apiKey: String) {
        viewModelScope.launch {
            aiKeyStore.save(apiKey.trim())
            aiKeyConfigured.value = true
            local.value = local.value.copy(toast = "API 密钥已加密保存")
        }
    }

    fun clearAiKey() {
        viewModelScope.launch {
            aiKeyStore.clear()
            aiKeyConfigured.value = false
            local.value = local.value.copy(toast = "已清除 API 密钥")
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(notifications = enabled) }
            reminderScheduler.rescheduleAll()
        }
    }

    fun setExactAlarm(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(exactAlarm = enabled) }
            reminderScheduler.rescheduleAll()
            refreshReminderPermissions()
        }
    }

    fun setDndEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(dndEnabled = enabled) } }
    }

    fun setDndWindow(start: String, end: String): Boolean {
        val pattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        if (!pattern.matches(start.trim()) || !pattern.matches(end.trim())) return false
        viewModelScope.launch {
            settingsStore.update { it.copy(dndStart = start.trim(), dndEnd = end.trim()) }
        }
        return true
    }

    fun showToast(message: String?) {
        local.value = local.value.copy(toast = message)
    }

    // ---- 后台存活 ----

    fun setKeepAliveMode(mode: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(keepAliveMode = mode) }
            keepAliveManager.applyMode(mode)
            refreshKeepAliveInfo()
            local.value = local.value.copy(toast = "后台保活：${keepAliveModeLabel(mode)}")
        }
    }

    fun refreshKeepAliveInfo() {
        viewModelScope.launch {
            val stats: KeepAliveStats = keepAliveManager.stats()
            keepAliveInfo.value = KeepAliveInfo(
                batteryWhitelisted = keepAliveManager.isIgnoringBatteryOptimizations(),
                lastBeatMs = stats.lastBeatMs,
                beatCount = stats.beatCount,
            )
        }
    }

    fun openBatteryWhitelist() {
        val opened = keepAliveManager.requestIgnoreBatteryOptimizations()
        local.value = local.value.copy(
            toast = if (opened) "请在系统页面允许后台运行" else "无法打开电池优化设置",
        )
        refreshKeepAliveInfo()
    }

    fun openAutoStart() {
        val opened = keepAliveManager.openAutoStartSettings()
        local.value = local.value.copy(
            toast = if (opened) "请在系统页面允许自启动" else "无法打开自启动设置，请手动在系统设置中查找",
        )
    }

    fun openKeepAliveChannel() {
        keepAliveManager.openNotificationChannelSettings(KeepAliveManager.CHANNEL_KEEPALIVE)
    }

    private fun keepAliveModeLabel(mode: String): String = when (mode) {
        KeepAliveModes.OFF -> "关闭"
        KeepAliveModes.SERVICE -> "常驻服务"
        else -> "心跳（推荐）"
    }

    companion object {
        val themeOptions = listOf(ThemeModes.LIGHT, ThemeModes.DARK, ThemeModes.OLED, ThemeModes.SYSTEM)
        val themeLabels = listOf("浅色", "深色", "纯黑", "跟随系统")
        val startupLabels = listOf("主界面", "上次")
        val startupValues = listOf(StartupModes.MAIN, StartupModes.LAST)
    }
}
