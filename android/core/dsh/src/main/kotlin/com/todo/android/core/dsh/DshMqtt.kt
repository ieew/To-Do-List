package com.todo.android.core.dsh

import com.todo.android.core.network.wire.DshMqttEndpoint
import javax.inject.Inject
import javax.inject.Singleton
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
class DshMqtt @Inject constructor() {

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
        onState: (connected: Boolean, detail: String) -> Unit,
    ) {
        disconnect()
        if (!endpoint.enabled || endpoint.host.isBlank()) {
            onState(false, "服务端未启用 MQTT")
            return
        }
        val scheme = if (endpoint.tls) "ssl" else "tcp"
        val uri = "$scheme://${endpoint.host}:${endpoint.port}"
        runCatching {
            val mqtt = MqttAsyncClient(uri, clientId, MemoryPersistence())
            mqtt.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    onState(false, cause?.message ?: "连接断开")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val text = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    if (text.contains("\"nudge\"")) onNudge()
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
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

    @Synchronized
    fun disconnect() {
        connected = false
        val mqtt = client ?: return
        client = null
        runCatching { mqtt.disconnectForcibly(0, 0, false) }
        runCatching { mqtt.close() }
    }
}
