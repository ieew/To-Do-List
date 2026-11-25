package com.todo.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.data.settings.AppSettings
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.designsystem.ThemeMode
import com.todo.android.core.dsh.DshManager
import com.todo.android.core.reminder.DshReminderBridge
import com.todo.android.core.reminder.KeepAliveManager
import com.todo.android.core.reminder.ReminderScheduler
import com.todo.android.core.sync.AccountManager
import com.todo.android.core.sync.SyncEngine
import com.todo.android.core.sync.SyncNotice
import com.todo.android.core.sync.SyncNotifier
import com.todo.android.core.sync.SyncScheduler
import com.todo.android.core.sync.SyncState
import com.todo.android.core.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Screen {
    data object Tasks : Screen

    data object Calendar : Screen

    data object Stats : Screen

    data object Settings : Screen

    data object Ai : Screen

    data class Detail(val taskId: String?) : Screen

    data class History(val taskId: String) : Screen

    data class Members(val workspaceId: String) : Screen
}

sealed interface BootState {
    data object Loading : BootState

    data object Ready : BootState

    data class Failed(val message: String) : BootState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val projectionHolder: ProjectionHolder,
    private val syncEngine: SyncEngine,
    private val settingsStore: SettingsStore,
    private val accountManager: AccountManager,
    private val syncNotifier: SyncNotifier,
    private val repository: OpRepository,
    private val commands: TaskCommands,
    private val reminderScheduler: ReminderScheduler,
    private val keepAliveManager: KeepAliveManager,
    private val syncScheduler: SyncScheduler,
    private val dsh: DshManager,
    private val dshReminderBridge: DshReminderBridge,
) : ViewModel() {
    private val _screen = MutableStateFlow<Screen>(Screen.Tasks)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    val boot: StateFlow<BootState> = _boot.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    /** 最近一次应用的周期同步间隔（分钟），避免设置每次 emit 都重排 Worker。 */
    private var periodicMinutes = 0

    private val noticeChannel = Channel<SyncNotice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()

    val themeMode: StateFlow<ThemeMode> = _settings
        .map { ThemeMode.from(it.theme) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    init {
        // 通道消息 → 提醒：只启动一次（Activity 重建时 ViewModel 已存在）。
        runCatching { dshReminderBridge.start() }
        // 其它设备改了数据：dsh 只发一个 sync 信号，这里合并短时间内的多条后立即同步。
        viewModelScope.launch {
            dsh.syncSignals.conflate().collect { signal ->
                val cursor = syncEngine.state.value.cursor
                // seq 是服务端 push 后的全局游标：已经拉到这个位置就不必再跑一轮。
                if (signal.seq > 0 && signal.seq <= cursor) return@collect
                delay(400)
                runCatching { syncEngine.runOnce() }
            }
        }
        viewModelScope.launch {
            settingsStore.observe().collect {
                _settings.value = it
                if (it.dshChannelEnabled) dsh.start() else dsh.stop()
                applySyncInterval(it)
            }
        }
        viewModelScope.launch {
            syncEngine.state.collect {
                _sync.value = it
                // 提醒重建属于旁路任务，失败不能打断收集协程（否则直接闪退）。
                if (it.status == SyncStatus.SYNCED) runCatching { reminderScheduler.rescheduleAll() }
            }
        }
        viewModelScope.launch { syncEngine.notices.collect { noticeChannel.send(it) } }
        viewModelScope.launch {
            syncNotifier.setForeground(true)
            syncNotifier.foregroundRequests.collect { syncEngine.runOnce() }
        }
    }

    fun bootstrap() {
        viewModelScope.launch {
            _boot.value = BootState.Loading
            val ok = withTimeoutOrNull(3_000) {
                accountManager.restore()
                syncEngine.bootstrap()
                ensureLocalWorkspace()
                dsh.start()
                true
            }
            _boot.value = if (ok == true) {
                BootState.Ready
            } else {
                BootState.Failed("初始化超时，请重试")
            }
            if (ok == true) {
                runCatching { reminderScheduler.rescheduleAll() }
                runCatching { syncEngine.runOnce() }
                // 后台任务放到首帧之后，避免启动期就碰 WorkManager / 闹钟。
                applySyncInterval(settingsStore.current())
                runCatching { keepAliveManager.bootstrap() }
            }
        }
    }

    /**
     * 阶段三：有实时通道时轮询只做 15 分钟安全兜底（WorkManager 最小周期），
     * 关闭通道则按用户设置的间隔跑（下限同样是 15 分钟）。
     */
    private fun applySyncInterval(settings: AppSettings) {
        val minutes = if (settings.dshChannelEnabled) {
            SyncScheduler.DEFAULT_INTERVAL_MINUTES
        } else {
            settings.syncIntervalMinutes.coerceAtLeast(SyncScheduler.DEFAULT_INTERVAL_MINUTES)
        }
        if (minutes == periodicMinutes) return
        periodicMinutes = minutes
        runCatching { syncScheduler.schedulePeriodic(minutes) }
    }

    /**
     * 未登录且本地没有工作区时，用真实 op 建一个本地工作区（含 4 清单 5 标签）。
     * 这不是演示数据：它走 appendOps，后续登录其他账户会先备份再清库。
     */
    private suspend fun ensureLocalWorkspace() {
        // 只依据 op_log 判断是否首次启动；投影是异步 Flow，可能尚未发出首帧。
        if (repository.loadAllOps().isNotEmpty()) return
        val userId = repository.currentUserId().ifEmpty {
            "local-user".also { repository.setCurrentUserId(it) }
        }
        commands.createWorkspace("我的空间", userId)
    }

    fun navigate(target: Screen) {
        _screen.value = target
    }

    fun openDetail(taskId: String?) {
        _screen.value = Screen.Detail(taskId)
    }

    fun openHistory(taskId: String) {
        _screen.value = Screen.History(taskId)
    }

    fun openMembers(workspaceId: String) {
        val target = workspaceId.ifBlank {
            projectionHolder.projection.value.activeWorkspaceId
        }
        _screen.value = Screen.Members(target)
    }

    fun back(): Boolean = when (_screen.value) {
        Screen.Tasks -> false
        Screen.Calendar, Screen.Stats -> {
            _screen.value = Screen.Tasks
            true
        }

        Screen.Settings -> {
            _screen.value = Screen.Tasks
            true
        }

        Screen.Ai -> {
            _screen.value = Screen.Tasks
            true
        }

        is Screen.Detail -> {
            _screen.value = Screen.Tasks
            true
        }

        is Screen.History -> {
            val history = _screen.value as Screen.History
            _screen.value = Screen.Detail(history.taskId)
            true
        }

        is Screen.Members -> {
            _screen.value = Screen.Settings
            true
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    theme = when (mode) {
                        ThemeMode.LIGHT -> "light"
                        ThemeMode.DARK -> "dark"
                        ThemeMode.OLED -> "oled"
                        ThemeMode.SYSTEM -> "system"
                    },
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncEngine.runOnce() }
    }
}
