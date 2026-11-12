package com.todo.android.core.network

import com.todo.android.core.network.api.SyncApi
import com.todo.android.core.network.wire.PushRequest
import com.todo.android.core.network.wire.WireHlc
import com.todo.android.core.network.wire.WireOpOut
import com.todo.android.core.network.wire.WireSourceMeta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

class ApiResultTest {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun syncApi(): SyncApi {
        server.start()
        val client = OkHttpClient.Builder().build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(JsonConverterFactory(json))
            .build()
            .create(SyncApi::class.java)
    }

    @Test
    fun `错误信封按 code 映射且保留 server_hlc`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """
                {"error":{"code":"workspace_access_revoked","message":"权限已变更，此操作未生效",
                "retryable":false,"details":null,
                "server_hlc":{"physical_ms":1700000000000,"counter":3,"node_id":"srv"}}}
                """.trimIndent(),
            ),
        )
        val api = syncApi()

        val result = apiCall(json) {
            api.push(
                PushRequest(
                    deviceId = "dev-1",
                    ops = listOf(
                        WireOpOut(
                            opId = "op-1",
                            workspaceId = "ws-1",
                            entityType = "task",
                            entityId = "task-1",
                            actorId = "u-1",
                            deviceId = "dev-1",
                            operation = "set",
                            field = "title",
                            value = JsonPrimitive("A"),
                            hlc = WireHlc(1, 0, "node-a"),
                            source = "human",
                            sourceMeta = WireSourceMeta(model = null, promptHash = null),
                        ),
                    ),
                ),
            )
        }

        val failure = (result as ApiResult.Failure).failure
        assertEquals("workspace_access_revoked", failure.code)
        assertEquals("权限已变更，此操作未生效", failure.message)
        assertFalse(failure.retryable)
        assertEquals(403, failure.httpStatus)
        assertEquals(1700000000000L, failure.serverHlc?.physicalMs)
    }

    @Test
    fun `网络错误视为可重试`() = runBlocking {
        val api = syncApi()
        server.shutdown()

        val result = apiCall(json) { api.pull(sinceSeq = 0, limit = 200) }
        val failure = (result as ApiResult.Failure).failure
        assertEquals("network_error", failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `上行信封不含 seq 与 received_at`() {
        val op = WireOpOut(
            opId = "op-1",
            workspaceId = "ws-1",
            entityType = "task",
            entityId = "task-1",
            actorId = "u-1",
            deviceId = "dev-1",
            operation = "set",
            field = "due_at",
            value = kotlinx.serialization.json.JsonNull,
            hlc = WireHlc(1, 0, "node-a"),
            source = "human",
            sourceMeta = WireSourceMeta(model = null, promptHash = null),
        )
        val encoded = json.encodeToString(PushRequest.serializer(), PushRequest("dev-1", listOf(op)))
        assertFalse(encoded.contains("\"seq\""))
        assertFalse(encoded.contains("received_at"))
        assertTrue("value 为 null 时必须保留字段", encoded.contains("\"value\":null"))
    }

    @Test
    fun `拉取响应解析出 seq 与 server_hlc`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"ops":[{"op_id":"op-1","workspace_id":"ws-1","entity_type":"task","entity_id":"task-1",
                "actor_id":"u-1","device_id":"dev-1","operation":"set","field":"title","value":"A",
                "hlc":{"physical_ms":10,"counter":0,"node_id":"node-a"},"source":"human",
                "source_meta":{"model":null,"prompt_hash":null},"seq":7,"received_at":1700000000000}],
                "next_seq":7,"has_more":false,"server_seq":7,
                "server_hlc":{"physical_ms":10,"counter":0,"node_id":"srv"}}
                """.trimIndent(),
            ),
        )
        val api = syncApi()

        val response = api.pull(sinceSeq = 0, limit = 200)
        assertEquals(7L, response.nextSeq)
        assertEquals(7L, response.ops.single().seq)
        assertEquals("srv", response.serverHlc?.nodeId)
    }
}
