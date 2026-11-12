package com.todo.android.feature.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.ai.AiClient
import com.todo.android.core.ai.AiDraftResult
import com.todo.android.core.ai.AiKeyStore
import com.todo.android.core.ai.AiPlan
import com.todo.android.core.ai.AiPlanner
import com.todo.android.core.ai.AiWriter
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.settings.AppSettings
import com.todo.android.core.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiUiState(
    val input: String = "",
    val generating: Boolean = false,
    val plan: AiPlan? = null,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val keyConfigured: Boolean = false,
    val confirmPending: Boolean = false,
    val imageCount: Int = 0,
    val toast: String? = null,
    val pendingImages: Int = 0,
)

@HiltViewModel
class AiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: AiClient,
    private val planner: AiPlanner,
    private val writer: AiWriter,
    private val keyStore: AiKeyStore,
    private val projectionHolder: ProjectionHolder,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private data class Local(
        val input: String = "",
        val generating: Boolean = false,
        val plan: AiPlan? = null,
        val error: String? = null,
        val confirmPending: Boolean = false,
        val imageDataUrls: List<String> = emptyList(),
        val toast: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val keyConfigured = MutableStateFlow(false)

    val state: StateFlow<AiUiState> = combine(
        local,
        settingsStore.observe(),
        keyConfigured,
    ) { localState, settings, configured ->
        AiUiState(
            input = localState.input,
            generating = localState.generating,
            plan = localState.plan,
            error = localState.error,
            settings = settings,
            keyConfigured = configured,
            confirmPending = localState.confirmPending,
            imageCount = localState.imageDataUrls.size,
            toast = localState.toast,
            pendingImages = localState.imageDataUrls.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiUiState())

    init {
        viewModelScope.launch { keyConfigured.value = keyStore.hasKey() }
    }

    fun refreshKeyState() {
        viewModelScope.launch { keyConfigured.value = keyStore.hasKey() }
    }

    fun onInputChange(value: String) {
        local.value = local.value.copy(input = value)
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            if (local.value.imageDataUrls.size >= 5) {
                local.value = local.value.copy(toast = "最多添加 5 张图片")
                return@launch
            }
            val dataUrl = withContext(Dispatchers.IO) { encode(uri) }
            if (dataUrl == null) {
                local.value = local.value.copy(toast = "图片读取失败")
            } else {
                local.value = local.value.copy(imageDataUrls = local.value.imageDataUrls + dataUrl)
            }
        }
    }

    fun removeImages() {
        local.value = local.value.copy(imageDataUrls = emptyList())
    }

    fun generate() {
        val input = local.value.input.trim()
        if (input.isEmpty()) {
            local.value = local.value.copy(toast = "请先描述要做什么")
            return
        }
        val settings = settingsStore.currentSync()
        if (!settings.aiEnabled) {
            local.value = local.value.copy(toast = "AI 助手已在设置中关闭")
            return
        }
        viewModelScope.launch {
            if (!keyStore.hasKey()) {
                keyConfigured.value = false
                local.value = local.value.copy(error = "请先在「设置 → AI」配置 API 密钥")
                return@launch
            }
            local.value = local.value.copy(generating = true, error = null)
            val result = client.generate(
                endpoint = settings.aiEndpoint,
                model = settings.aiModel,
                prompt = input,
                imageDataUrls = local.value.imageDataUrls,
            )
            when (result) {
                is AiDraftResult.Success -> {
                    val plan = planner.plan(result.tasks, projectionHolder.projection.value, System.currentTimeMillis())
                    local.value = local.value.copy(generating = false, plan = plan, error = null)
                }

                is AiDraftResult.Failure -> {
                    local.value = local.value.copy(generating = false, error = result.message)
                }
            }
        }
    }

    fun requestWrite() {
        val plan = local.value.plan ?: return
        val threshold = settingsThreshold()
        if (plan.tasks.size >= threshold) {
            local.value = local.value.copy(confirmPending = true)
        } else {
            writeNow()
        }
    }

    fun confirmWrite() {
        local.value = local.value.copy(confirmPending = false)
        writeNow()
    }

    fun dismissConfirm() {
        local.value = local.value.copy(confirmPending = false)
    }

    fun cancelPlan() {
        local.value = local.value.copy(plan = null, error = null)
    }

    fun showToast(message: String?) {
        local.value = local.value.copy(toast = message)
    }

    private fun writeNow() {
        val plan = local.value.plan ?: return
        val workspaceId = projectionHolder.projection.value.activeWorkspaceId
        if (workspaceId.isEmpty()) {
            local.value = local.value.copy(toast = "当前没有可用工作区")
            return
        }
        viewModelScope.launch {
            val model = currentSettings().aiModel
            val count = writer.write(workspaceId, plan.tasks, model)
            local.value = local.value.copy(plan = null, toast = "已写入 $count 条任务")
        }
    }

    private fun settingsThreshold(): Int = currentSettings().aiConfirmThreshold.takeIf { it >= 1 } ?: 10

    private fun currentSettings(): AppSettings = settingsStore.currentSync()

    private fun encode(uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}
