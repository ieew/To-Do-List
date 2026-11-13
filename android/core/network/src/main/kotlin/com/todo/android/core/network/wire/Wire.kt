package com.todo.android.core.network.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WireHlc(
    @SerialName("physical_ms") val physicalMs: Long,
    val counter: Long,
    @SerialName("node_id") val nodeId: String,
)

@Serializable
data class WireSourceMeta(
    val model: String? = null,
    @SerialName("prompt_hash") val promptHash: String? = null,
)

@Serializable
data class WireOpIn(
    @SerialName("op_id") val opId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("device_id") val deviceId: String,
    val operation: String,
    val field: String,
    val value: JsonElement,
    val hlc: WireHlc,
    val source: String,
    @SerialName("source_meta") val sourceMeta: WireSourceMeta? = null,
    val seq: Long? = null,
    @SerialName("received_at") val receivedAt: Long? = null,
)

/** 上行信封：不含服务端只读字段 `seq` / `received_at`。 */
@Serializable
data class WireOpOut(
    @SerialName("op_id") val opId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("device_id") val deviceId: String,
    val operation: String,
    val field: String,
    val value: JsonElement,
    val hlc: WireHlc,
    val source: String,
    @SerialName("source_meta") val sourceMeta: WireSourceMeta,
)

@Serializable
data class PushRequest(
    @SerialName("device_id") val deviceId: String,
    val ops: List<WireOpOut>,
)

@Serializable
data class WireOpError(
    val code: String,
    val message: String = "",
    // 协议 error.schema.json 里 retryable 是必填项；漏了字段会在 ignoreUnknownKeys=false 下解码失败。
    val retryable: Boolean = false,
    val details: JsonElement? = null,
    @SerialName("server_hlc") val serverHlc: WireHlc? = null,
)

@Serializable
data class PushResult(
    @SerialName("op_id") val opId: String,
    val status: String,
    val seq: Long? = null,
    val error: WireOpError? = null,
)

@Serializable
data class PushResponse(
    @SerialName("server_hlc") val serverHlc: WireHlc,
    @SerialName("server_seq") val serverSeq: Long,
    val results: List<PushResult>,
)

@Serializable
data class PullResponse(
    val ops: List<WireOpIn> = emptyList(),
    @SerialName("next_seq") val nextSeq: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("server_seq") val serverSeq: Long = 0,
    @SerialName("server_hlc") val serverHlc: WireHlc? = null,
)

@Serializable
data class UserOut(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class WireTokens(
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: Long,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: Long,
)

@Serializable
data class WorkspaceOut(
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    val role: String,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("workspace_name") val workspaceName: String? = null,
)

@Serializable
data class RegisterResponse(
    val user: UserOut,
    @SerialName("default_workspace_id") val defaultWorkspaceId: String,
    val tokens: WireTokens,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class LoginResponse(
    val user: UserOut,
    val workspaces: List<WorkspaceOut> = emptyList(),
    val tokens: WireTokens,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class RefreshResponse(val tokens: WireTokens)

@Serializable
data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String = "",
    val retryable: Boolean = false,
    val details: JsonElement? = null,
    @SerialName("server_hlc") val serverHlc: WireHlc? = null,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

// ---- 工作区成员（服务端 /v1/workspace/*） ----

@Serializable
data class WireMember(
    @SerialName("user_id") val userId: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val name: String = "",
    val initial: String = "",
    val color: String = "",
    val email: String = "",
    val role: String = "viewer",
    @SerialName("joined_at") val joinedAt: String = "",
)

@Serializable
data class MembersResponse(
    @SerialName("workspace_id") val workspaceId: String = "",
    val members: List<WireMember> = emptyList(),
)

@Serializable
data class InviteRequest(
    @SerialName("workspace_id") val workspaceId: String,
    /** 用户名或邮箱。 */
    val account: String,
    val role: String,
)

@Serializable
data class WireRoleRequest(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
)

@Serializable
data class RemoveMemberRequest(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("user_id") val userId: String,
)

/** 邀请/改角色的统一返回体。 */
@Serializable
data class MemberMutationResponse(
    @SerialName("user_id") val userId: String,
    val role: String,
)
