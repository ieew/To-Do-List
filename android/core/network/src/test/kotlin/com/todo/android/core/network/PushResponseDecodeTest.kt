package com.todo.android.core.network

import com.todo.android.core.network.wire.PushResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 回归用例：服务端 op 被拒时，`error` 里带 `retryable`（协议必填）。
 * 若模型漏字段，`Json(ignoreUnknownKeys = false)` 会抛 SerializationException，
 * 而 `apiCall` 只捕获 IOException/HttpException —— 异常会直接打闪退。
 */
class PushResponseDecodeTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun `被拒的 op 错误体可以完整解码`() {
        val raw = """
            {"server_hlc":{"physical_ms":1762000000000,"counter":0,"node_id":"server"},
             "server_seq":7,
             "results":[{"op_id":"op-1","status":"rejected",
               "error":{"code":"invalid_value","message":"序号必须是有限数字","retryable":false}}]}
        """.trimIndent()

        val response = json.decodeFromString(PushResponse.serializer(), raw)

        val result = response.results.single()
        assertEquals("rejected", result.status)
        assertEquals("invalid_value", result.error?.code)
        assertEquals("序号必须是有限数字", result.error?.message)
        assertFalse(result.error?.retryable ?: true)
    }

    @Test
    fun `带 details 与 server_hlc 的错误体也能解码`() {
        val raw = """
            {"server_hlc":{"physical_ms":1,"counter":0,"node_id":"s"},"server_seq":1,
             "results":[{"op_id":"op-2","status":"rejected",
               "error":{"code":"hlc_too_far_ahead","message":"时钟超前","retryable":true,
                 "details":[{"field":"hlc","reason":"too_far_ahead"}],
                 "server_hlc":{"physical_ms":1,"counter":2,"node_id":"s"}}}]}
        """.trimIndent()

        val response = json.decodeFromString(PushResponse.serializer(), raw)

        val error = response.results.single().error
        assertEquals("hlc_too_far_ahead", error?.code)
        assertEquals(2L, error?.serverHlc?.counter)
    }
}
