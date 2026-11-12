package com.todo.android.core.ai

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI 兼容客户端（`POST {endpoint}/chat/completions`）；密钥只从 Keystore 解密后使用。 */
@Singleton
class AiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val json: Json,
    private val keyStore: AiKeyStore,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        endpoint: String,
        model: String,
        prompt: String,
        imageDataUrls: List<String> = emptyList(),
    ): AiDraftResult = withContext(Dispatchers.IO) {
        val key = keyStore.read()
            ?: return@withContext AiDraftResult.Failure("ai_key_missing", "请先在设置中配置 API 密钥")
        val url = endpoint.trim().trimEnd('/') + "/chat/completions"

        val userContent = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", prompt) })
            imageDataUrls.take(5).forEach { dataUrl ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", dataUrl) })
                    },
                )
            }
        }
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("stream", false)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    add(buildJsonObject { put("role", "user"); put("content", userContent) })
                },
            )
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching { client.newCall(request).execute() }.fold(
            onSuccess = { response ->
                response.use {
                    val body = it.body.string()
                    if (!it.isSuccessful) {
                        return@withContext AiDraftResult.Failure(
                            code = "ai_http_${it.code}",
                            message = parseErrorMessage(body) ?: "AI 请求失败（${it.code}）",
                        )
                    }
                    val content = extractContent(body)
                        ?: return@withContext AiDraftResult.Failure("ai_schema_error", "AI 返回格式不正确，已忽略")
                    val tasks = runCatching {
                        json.decodeFromString(AiDraftResponse.serializer(), content).tasks
                    }.getOrNull()
                        ?: return@withContext AiDraftResult.Failure("ai_schema_error", "AI 返回格式不正确，已忽略")
                    if (tasks.isEmpty()) {
                        return@withContext AiDraftResult.Failure("ai_empty", "AI 没有返回可写入的任务")
                    }
                    AiDraftResult.Success(tasks = tasks, raw = content)
                }
            },
            onFailure = { AiDraftResult.Failure("network_error", "网络连接失败，请稍后重试") },
        )
    }

    private fun extractContent(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"] as JsonArray
        val message = choices.first().jsonObject["message"] as JsonObject
        message["content"]!!.jsonPrimitive.content
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }.getOrNull()

    private fun parseErrorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SYSTEM_PROMPT =
            "你是待办清单助手。只输出 JSON 对象，格式：{\"tasks\":[{\"title\":\"必填\",\"notes\":\"\",\"priority\":\"high|mid|low\"," +
                "\"start_at\":\"RFC3339 或 null\",\"due_at\":\"RFC3339 或 null\",\"list\":\"清单名或 null\"," +
                "\"tags\":[\"标签名\"],\"subtasks\":[{\"text\":\"\",\"done\":false}]}]}。" +
                "不要输出解释、Markdown 或代码块。"
    }
}
