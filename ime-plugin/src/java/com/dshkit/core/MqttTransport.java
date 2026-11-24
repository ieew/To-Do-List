package com.dshkit.core;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * L3 传输实现：Paho mqttv3（TCP / TLS）。
 *
 * 有意**不用** Paho 的自动重连：票据 TTL 只有 10 分钟，自动重连会拿旧票一直撞墙。
 * 重连由 {@link Session} 负责，每次连之前重新注册设备拿新票。
 */
public final class MqttTransport implements Transport {

    private final Log log;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private volatile State state = State.OFFLINE;
    private volatile String lastError = "";
    private volatile MqttAsyncClient client;
    private volatile String topicIn = "";
    private volatile int keepAlive = 60;

    public MqttTransport(Log log) {
        this.log = log;
    }

    @Override
    public synchronized void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public synchronized void connect(final Credentials c) {
        disconnectQuietly();
        setState(State.CONNECTING, "连接 " + c.host() + ":" + c.port());
        topicIn = c.topicIn();
        keepAlive = Math.max(15, c.keepAlive());
        String clientId = c.clientId() == null || c.clientId().isEmpty()
                ? "dsh-" + UUID.randomUUID().toString().substring(0, 8)
                : c.clientId();
        String uri = (c.tls() ? "ssl://" : "tcp://") + c.host() + ":" + c.port();
        try {
            final MqttAsyncClient mqtt = new MqttAsyncClient(uri, clientId, new MemoryPersistence());
            client = mqtt;
            mqtt.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    // 不做额外动作：订阅在 connect 的 onSuccess 里做（重连由 Session 驱动）。
                }

                @Override
                public void connectionLost(Throwable cause) {
                    lastError = cause == null ? "连接断开" : describe(cause);
                    setState(State.OFFLINE, "连接断开: " + lastError);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    byte[] payload = message == null ? null : message.getPayload();
                    log.i("mqtt", "收到消息 " + (payload == null ? 0 : payload.length) + "B");
                    for (Listener l : snapshotListeners()) {
                        try {
                            l.onMessage(topic, payload);
                        } catch (Throwable t) {
                            log.e("mqtt/listener", t);
                        }
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // QoS1 确认；nudge 由服务端发，这里只用于上报/自检
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(false);
            options.setKeepAliveInterval(keepAlive);
            options.setConnectionTimeout(15);
            options.setUserName(c.username());
            options.setPassword(c.password() == null ? new char[0] : c.password().toCharArray());
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            // 服务端 broker 只在 IPv4 上监听；Android 默认先解析 IPv6，连 240e:...:1883 会 ECONNREFUSED。
            options.setSocketFactory(new Ipv4SocketFactory());
            mqtt.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    subscribeTopic();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    lastError = "连接失败: " + describe(exception);
                    setState(State.OFFLINE, lastError);
                }
            });
        } catch (Throwable t) {
            lastError = "连接异常: " + t.getMessage();
            log.e("mqtt/connect", t);
            setState(State.OFFLINE, lastError);
        }
    }

    private void subscribeTopic() {
        final MqttAsyncClient mqtt = client;
        final String topic = topicIn;
        if (mqtt == null || topic.isEmpty()) return;
        try {
            mqtt.subscribe(topic, 1, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    setState(State.ONLINE, "已订阅 " + topic);
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    lastError = "订阅失败: " + (exception == null ? "?" : exception.getMessage());
                    setState(State.OFFLINE, lastError);
                }
            });
        } catch (Throwable t) {
            lastError = "订阅异常: " + t.getMessage();
            log.e("mqtt/subscribe", t);
            setState(State.OFFLINE, lastError);
        }
    }

    @Override
    public boolean publish(String topic, byte[] payload, int qos) {
        MqttAsyncClient mqtt = client;
        if (mqtt == null || !mqtt.isConnected()) {
            lastError = "未连接，无法发布";
            return false;
        }
        try {
            mqtt.publish(topic, payload == null ? new byte[0] : payload, qos, false);
            return true;
        } catch (Throwable t) {
            lastError = "发布失败: " + t.getMessage();
            log.e("mqtt/publish", t);
            return false;
        }
    }

    @Override
    public void subscribe(String filter, int qos) {
        MqttAsyncClient mqtt = client;
        if (mqtt == null || filter == null || filter.isEmpty()) return;
        try {
            mqtt.subscribe(filter, qos);
        } catch (Throwable t) {
            lastError = "订阅失败: " + t.getMessage();
            log.e("mqtt/subscribe", t);
        }
    }

    @Override
    public void disconnect() {
        disconnectQuietly();
        setState(State.OFFLINE, "已断开");
    }

    /** 把 Paho 异常拆开打全：message 常见为空，reasonCode/cause 才是真因。 */
    private static String describe(Throwable t) {
        if (t == null) return "?";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        if (t instanceof MqttException) {
            sb.append(" code=").append(((MqttException) t).getReasonCode());
        }
        Throwable cause = t.getCause();
        for (int i = 0; cause != null && i < 4; i++) {
            sb.append(" <- ").append(cause.getClass().getName());
            if (cause.getMessage() != null) sb.append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private void disconnectQuietly() {
        MqttAsyncClient old = client;
        client = null;
        if (old == null) return;
        try {
            if (old.isConnected()) {
                old.disconnectForcibly(500, 500);
            }
        } catch (Throwable ignored) {
        }
        try {
            old.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        disconnectQuietly();
        setState(State.OFFLINE, "已关闭");
    }

    private void setState(State next, String detail) {
        state = next;
        if (detail != null) lastError = detail;
        if (next == State.ONLINE) log.i("mqtt", detail == null ? "online" : detail);
        else if (next == State.OFFLINE) log.w("mqtt", detail == null ? "offline" : detail);
        for (Listener l : snapshotListeners()) {
            try {
                l.onState(next, detail);
            } catch (Throwable t) {
                log.e("mqtt/listener", t);
            }
        }
    }

    private synchronized List<Listener> snapshotListeners() {
        return new ArrayList<Listener>(listeners);
    }
}
