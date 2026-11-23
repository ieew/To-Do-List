package com.dshkit.core;

/**
 * L3 传输抽象：业务层只认这个接口，换传输（TCP→QUIC）不动模块。
 *
 * 票是短期的（默认 10 分钟），所以每次连接都通过 {@link Credentials} 现取现用，
 * 不用 Paho 的自动重连（它会拿旧票一直重试）。
 */
public interface Transport {

    enum State {
        OFFLINE,
        CONNECTING,
        ONLINE
    }

    interface Credentials {
        String host();

        int port();

        boolean tls();

        String clientId();

        String username();

        String password();

        /** 连上后要订阅的主题（自己那条 in）。 */
        String topicIn();

        /** 心跳秒数（Paho keepAlive）。 */
        int keepAlive();
    }

    interface Listener {
        void onState(State state, String detail);

        void onMessage(String topic, byte[] payload);

        void onError(String detail);
    }

    void addListener(Listener listener);

    void connect(Credentials credentials);

    void disconnect();

    State state();

    String lastError();

    boolean publish(String topic, byte[] payload, int qos);

    void subscribe(String filter, int qos);

    void close();
}
