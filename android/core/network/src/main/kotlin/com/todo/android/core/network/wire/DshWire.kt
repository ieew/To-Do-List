package com.todo.android.core.network.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * dsh 数据通道（新通道）线协议：设备注册、设备凭证、pull/ack。
 *
 * 与 `docs/server-protocol.md` §15 一致；字段名用 snake_case，
 * 所有响应都显式声明 `server_ms` 等可选字段，避免严格解码失败。
 */
@Serializable
data class DshRegisterRequest(
    val name: String,
    val platform: String,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class DshMqttEndpoint(
    val enabled: Boolean = false,
    val online: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val tls: Boolean = false,
    val username: String = "",
    @SerialName("topic_in") val topicIn: String = "",
    @SerialName("topic_out") val topicOut: String = "",
)

@Serializable
data class DshRegisterResponse(
    @SerialName("device_id") val deviceId: String,
    val action: String = "created",
    val ticket: String = "",
    @SerialName("ticket_expires_at") val ticketExpiresAt: Long = 0,
    val mqtt: DshMqttEndpoint = DshMqttEndpoint(),
)

@Serializable
data class DshCredentialResponse(
    @SerialName("device_id") val deviceId: String,
    val credential: String,
    @SerialName("credential_expires_at") val credentialExpiresAt: Long = 0,
)

@Serializable
data class DshRefreshRequest(
    @SerialName("device_id") val deviceId: String,
    val credential: String,
)

@Serializable
data class DshRefreshResponse(
    @SerialName("device_id") val deviceId: String,
    val ticket: String,
    @SerialName("ticket_expires_at") val ticketExpiresAt: Long = 0,
    val credential: String = "",
    @SerialName("credential_expires_at") val credentialExpiresAt: Long = 0,
    val mqtt: DshMqttEndpoint = DshMqttEndpoint(),
)

@Serializable
data class DshMessage(
    val seq: Long,
    @SerialName("msg_id") val msgId: String,
    val kind: String,
    val payload: JsonElement? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
)

@Serializable
data class DshPullResponse(
    val messages: List<DshMessage> = emptyList(),
    @SerialName("next_seq") val nextSeq: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("latest_seq") val latestSeq: Long = 0,
    @SerialName("server_ms") val serverMs: Long = 0,
)

@Serializable
data class DshAckRequest(
    @SerialName("device_id") val deviceId: String? = null,
    val seqs: List<Long>,
)

@Serializable
data class DshAckResponse(
    @SerialName("device_id") val deviceId: String = "",
    val acked: Int = 0,
    val requested: Int = 0,
)
