package com.todo.android.core.network

import com.todo.android.core.network.api.MemberApi
import com.todo.android.core.network.wire.InviteRequest
import com.todo.android.core.network.wire.RemoveMemberRequest
import com.todo.android.core.network.wire.WireRoleRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/** 成员接口的路径与 JSON 契约（与 server/app/features/workspace_member 对齐）。 */
class MemberApiTest {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): MemberApi {
        server.start()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(JsonConverterFactory(json))
            .build()
            .create(MemberApi::class.java)
    }

    @Test
    fun `成员列表解析并与服务端字段对齐`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"workspace_id":"ws-1","members":[
                  {"user_id":"u-1","username":"alice","display_name":"爱丽丝","name":"爱丽丝",
                   "initial":"爱","color":"#3B82F6","email":"a@todo.local","role":"owner",
                   "joined_at":"2026-11-01T09:00:00+08:00"}]}
                """.trimIndent(),
            ),
        )
        val api = api()

        val result = apiCall(json) { api.members("ws-1") }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/workspace/members?workspace_id=ws-1", request.path)

        val members = (result as ApiResult.Success).value.members
        assertEquals(1, members.size)
        assertEquals("u-1", members.first().userId)
        assertEquals("alice", members.first().username)
        assertEquals("爱丽丝", members.first().displayName)
        assertEquals("owner", members.first().role)
    }

    @Test
    fun `邀请与改角色走 POST 与 PATCH 且字段为蛇形`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user_id":"u-2","role":"editor"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user_id":"u-2","role":"viewer"}"""))
        val api = api()

        val invited = apiCall(json) { api.invite(InviteRequest("ws-1", "bob", "editor")) }
        val inviteRequest = server.takeRequest()
        assertEquals("POST", inviteRequest.method)
        assertEquals("/v1/workspace/invite", inviteRequest.path)
        val inviteBody = inviteRequest.body.readUtf8()
        assertTrue(inviteBody.contains("\"workspace_id\":\"ws-1\""))
        assertTrue(inviteBody.contains("\"account\":\"bob\""))
        assertTrue(inviteBody.contains("\"role\":\"editor\""))
        assertEquals("editor", (invited as ApiResult.Success).value.role)

        val changed = apiCall(json) { api.setRole(WireRoleRequest("ws-1", "u-2", "viewer")) }
        val roleRequest = server.takeRequest()
        assertEquals("PATCH", roleRequest.method)
        assertEquals("/v1/workspace/role", roleRequest.path)
        assertTrue(roleRequest.body.readUtf8().contains("\"user_id\":\"u-2\""))
        assertEquals("viewer", (changed as ApiResult.Success).value.role)
    }

    @Test
    fun `移除成员返回 204 也能正常完成`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val api = api()

        val result = apiCall(json) { api.remove(RemoveMemberRequest("ws-1", "u-2")) }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/workspace/remove", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `权限被拒时按 code 暴露 forbidden`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":"forbidden","message":"当前角色无权执行此操作","retryable":false}}""",
            ),
        )
        val api = api()

        val result = apiCall(json) { api.invite(InviteRequest("ws-1", "bob", "editor")) }

        val failure = (result as ApiResult.Failure).failure
        assertEquals("forbidden", failure.code)
        assertEquals("当前角色无权执行此操作", failure.message)
    }
}
