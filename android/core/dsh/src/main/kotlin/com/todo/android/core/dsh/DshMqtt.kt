package com.todo.android.core.dsh

import com.todo.android.core.network.wire.DshMqttEndpoint
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * App 自建 MQTT（通道不可用时的兜底）。
 *
 * 只订阅自己的 `topic_in`，收到 nudge 就回调上层 pull；不做任何业务解析，
 * 与输入法插件用的是同一套协议（`dsh/v1/{user}/dev/{device}/in|out`）。
 */
@Singleton
class DshMqtt @Inject constructor(
    private val json: Json,
) {

    private companion object {
        const val PROBE_TIMEOUT_MS = 1500
    }

    private var client: MqttAsyncClient? = null

    @Volatile
    var connected: Boolean = false
        private set

    @Synchronized
    fun connect(
        endpoint: DshMqttEndpoint,
        clientId: String,
        ticket: String,
        keepAliveSeconds: Int,
        onNudge: () -> Unit,
        onSync: (DshSyncSignal) -> Unit,
        onState: (connected: Boolean, detail: String) -> Unit,
    ) {
        disconnect()
        if (!endpoint.enabled || endpoint.host.isBlank()) {
            onState(false, "服务端未启用 MQTT")
            return
        }
        val scheme = if (endpoint.tls) "ssl" else "tcp"
        // 双栈：IPv6 优先，连不上回落到 IPv4；Paho 只接受字面量 IP 时不用再解析。
        val host = reachableHost(endpoint.host, endpoint.port)
        val uri = "$scheme://${literalHost(host)}:${endpoint.port}"
        runCatching {
            val mqtt = MqttAsyncClient(uri, clientId, MemoryPersistence())
            mqtt.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    onState(false, cause?.message ?: "连接断开")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val text = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    val type = runCatching {
                        json.parseToJsonElement(text).jsonObject["t"]?.jsonPrimitive?.content
                    }.getOrNull()
                    when (type) {
                        "sync" -> onSync(parseSyncSignal(text))
                        "nudge" -> onNudge()
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                // §5.3（T3，P0）：Paho 的自动重连会复用建连时的票据，票一过期就永远 421/401，
                // 而我们这边看起来只是「在重连」。改成自己接管：断线回调 → 退避（带 jitter）→ 先续期再连。
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = keepAliveSeconds.coerceIn(15, 600)
                userName = endpoint.username
                password = ticket.toCharArray()
            }
            mqtt.connect(options, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    runCatching {
                        mqtt.subscribe(endpoint.topicIn, 1)
                        connected = true
                        onState(true, "")
                    }.onFailure { onState(false, it.message ?: "订阅失败") }
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?,
                ) {
                    connected = false
                    onState(false, exception?.message ?: "连接失败")
                }
            })
            client = mqtt
        }.onFailure { onState(false, it.message ?: "MQTT 初始化失败") }
    }

    /**
     * `{"t":"sync","seq":42,"ws":["ws-1"]}` → [DshSyncSignal]；旧服务端的 `{"t":"sync"}` 也能解析。
     */
    private fun parseSyncSignal(text: String): DshSyncSignal = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L
        val workspaces = obj["ws"]?.jsonArray
            ?.mapNotNull { item -> runCatching { item.jsonPrimitive.content }.getOrNull() }
            ?: emptyList()
        DshSyncSignal(seq = seq, workspaces = workspaces)
    }.getOrElse { DshSyncSignal() }

    /**
     * 双栈地址选择：IPv6 优先，TCP 探活不通再回落 IPv4；全部失败返回原 host 让 Paho 自己解析。
     * 探测只是 TCP 握手，连上立刻关掉，不算一次 MQTT 会话。
     */
    private fun reachableHost(host: String, port: Int): String = runCatching {
        val addresses = InetAddress.getAllByName(host)
            .sortedByDescending { it is Inet6Address }
        for (address in addresses) {
            val reachable = try {
                Socket().use { it.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MS) }
                true
            } catch (_: Exception) {
                false
            }
            if (reachable) return@runCatching address.hostAddress ?: host
        }
        host
    }.getOrDefault(host)

    private fun literalHost(host: String): String = if (':' in host) "[$host]" else host

    @Synchronized
    fun disconnect() {        connected = false
        val mqtt = client ?: return
        client = null
        runCatching { mqtt.disconnectForcibly(0, 0, false) }
        runCatching { mqtt.close() }
    }
}
