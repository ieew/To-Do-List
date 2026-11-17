package com.todo.android.feature.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.ai.AiClient
import com.todo.android.core.ai.AiIntentApplier
import com.todo.android.core.ai.AiIntentParser
import com.todo.android.core.ai.AiIntentPlan
import com.todo.android.core.ai.AiKeyStore
import com.todo.android.core.ai.AiRawResult
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
    val intent: AiIntentPlan? = null,
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
    private val applier: AiIntentApplier,
    private val keyStore: AiKeyStore,
    private val projectionHolder: ProjectionHolder,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private data class Local(
        val input: String = "",
        val generating: Boolean = false,
        val intent: AiIntentPlan? = null,
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
            intent = localState.intent,
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
            val projection = projectionHolder.projection.value
            val now = System.currentTimeMillis()
            val systemPrompt = AiIntentParser.buildPrompt(projection, now)
            when (
                val result = client.generateIntent(
                    endpoint = settings.aiEndpoint,
                    model = settings.aiModel,
                    systemPrompt = systemPrompt,
                    userPrompt = input,
                    imageDataUrls = local.value.imageDataUrls,
                )
            ) {
                is AiRawResult.Success -> {
                    val intent = AiIntentParser.parse(result.content, projection, now)
                    local.value = local.value.copy(generating = false, intent = intent, error = null)
                }

                is AiRawResult.Failure -> {
                    local.value = local.value.copy(generating = false, error = result.message)
                }
            }
        }
    }

    fun requestWrite() {
        val intent = local.value.intent ?: return
        val threshold = settingsThreshold()
        // 危险操作（删除 / 标记完成）无论多少条都需两步确认，其余按阈值。
        val needConfirm = intent.hasDangerous || intent.operations.size >= threshold
        if (needConfirm && !local.value.confirmPending) {
            local.value = local.value.copy(confirmPending = true)
            return
        }
        writeNow()
    }

    fun confirmWrite() {
        local.value = local.value.copy(confirmPending = false)
        writeNow()
    }

    fun dismissConfirm() {
        local.value = local.value.copy(confirmPending = false)
    }

    fun cancelPlan() {
        local.value = local.value.copy(intent = null, error = null, confirmPending = false)
    }

    fun showToast(message: String?) {
        local.value = local.value.copy(toast = message)
    }

    private fun writeNow() {
        val intent = local.value.intent ?: return
        val projection = projectionHolder.projection.value
        val workspaceId = projection.activeWorkspaceId
        if (workspaceId.isEmpty()) {
            local.value = local.value.copy(toast = "当前没有可用工作区")
            return
        }
        viewModelScope.launch {
            val model = currentSettings().aiModel
            val result = applier.apply(
                workspaceId = workspaceId,
                operations = intent.operations,
                model = model,
                projection = projection,
            )
            val message = buildString {
                append("已执行 ").append(result.applied).append(" 条操作")
                if (result.warnings.isNotEmpty()) {
                    append("，").append(result.warnings.size).append(" 条被跳过")
                }
                append("；可在任务历史里逐条撤销")
            }
            local.value = local.value.copy(intent = null, toast = message, confirmPending = false)
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
