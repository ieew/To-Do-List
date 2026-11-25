package com.dshkit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L3 业务会话：把「nudge → pull → 应用 → ack」串起来，并负责票据/令牌与重连。
 *
 * 可靠性口径（设计 §7.3）：数据库是唯一真相源，nudge 丢了不影响正确性——
 * 每次连上、每 2 分钟、以及每条 nudge 都会触发一次幂等 pull。
 *
 * 线程：所有方法都可以从任意线程调；内部一律丢给 {@link Scheduler} 的单线程队列。
 */
public final class Session {

    public interface Applier {
        /** 返回 true = 已处理，可以 ack；false = 暂时没处理，先不 ack（下轮再试）。 */
        boolean apply(String kind, String msgId, String payloadJson, boolean live);
    }

    public interface Listener {
        void onSessionChanged(Map<String, Object> status);
    }

    /**
     * 与待办 App 的桥：插件不保存账号密码，只向 App 取设备凭证。
     * 由 Kit 实现（provider 调用）；CLI 调试时可以为 null。
     */
    public interface Authorizer {
        /** 取授权信息：server / device_id / credential / credential_expires_at 等；失败返回 null。 */
        Map<String, Object> fetch();

        /** 续期后回写轮换的新凭证。 */
        void update(String credential, long expiresAt);

        /** 回写 MQTT 连接状态，供 App 设置页展示。 */
        void report(boolean connected, String error);

        /** 收到 nudge，让 App 立刻 pull（App 负责通知与提醒）。 */
        void nudge();
    }

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final long TOKEN_MARGIN_MS = 60_000L;
    private static final long TICKET_MARGIN_MS = 60_000L;
    private static final long CREDENTIAL_MARGIN_MS = 86_400_000L;
    private static final long SAFETY_PULL_MS = 120_000L;

    private static final String K_DEVICE_ID = "device_id";
    private static final String K_CURSOR = "cursor";
    private static final String K_ACCESS = "access";
    private static final String K_ACCESS_EXP = "access_exp";
    private static final String K_REFRESH = "refresh";
    private static final String K_REFRESH_EXP = "refresh_exp";
    private static final String K_CREDENTIAL = "credential";
    private static final String K_CREDENTIAL_EXP = "credential_exp";
    private static final String K_SERVER = "server";

    private volatile Config cfg;
    private final HttpApi api;
    private final Transport transport;
    private final Store store;
    private final Scheduler sched;
    private final Log log;
    private final Applier applier;

    private volatile boolean running;
    private volatile boolean pulling;
    private volatile boolean online;
    /** 防重入：同一时刻只允许一个 attempt（ensureToken 里是阻塞网络调用）。 */
    private final AtomicBoolean attempting = new AtomicBoolean(false);
    /** 同一时刻只挂一个退避重连任务。 */
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    /** 手动重连合并：连点多下只算一次。 */
    private final AtomicBoolean kickPending = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> reconnectFuture;

    private volatile String access;
    private volatile long accessExp;
    private volatile String refreshToken;
    private volatile String credential;
    private volatile long credentialExp;
    private volatile HttpApi.Device device;
    private volatile Authorizer authorizer;
    private volatile long cursor;
    private volatile String lastError = "";
    private volatile long lastNudgeAt;
    private volatile long lastPullAt;
    private volatile long pulledCount;
    private volatile long appliedCount;
    private volatile long ackedCount;
    private volatile long reconnectDelay = 2000L;
    private volatile Listener listener;
    /** App 下发的设置快照：插件兜底展示提醒时据此判断静默/提前量。 */
    private volatile boolean notificationsEnabled = true;
    private volatile boolean dndEnabled;
    private volatile String dndStart = "22:00";
    private volatile String dndEnd = "08:00";
    private volatile int reminderLead;

    public Session(Config cfg, HttpApi api, Transport transport, Store store, Scheduler sched,
                   Log log, Applier applier) {
        this.cfg = cfg;
        this.api = api;
        this.transport = transport;
        this.store = store;
        this.sched = sched;
        this.log = log;
        this.applier = applier;
        this.cursor = store.getLong(K_CURSOR, 0);
        this.access = store.getString(K_ACCESS, null);
        this.accessExp = store.getLong(K_ACCESS_EXP, 0);
        this.refreshToken = store.getString(K_REFRESH, null);
        this.credential = Secret.open(store.getString(K_CREDENTIAL, null));
        this.credentialExp = store.getLong(K_CREDENTIAL_EXP, 0);
        api.setServer(store.getString(K_SERVER, cfg.server));
        transport.addListener(new Transport.Listener() {
            @Override
            public void onState(Transport.State state, String detail) {
                if (state == Transport.State.ONLINE) {
                    sched.run(new Runnable() {
                        @Override
                        public void run() {
                            onOnline();
                        }
                    });
                } else if (state == Transport.State.OFFLINE) {
                    sched.run(new Runnable() {
                        @Override
                        public void run() {
                            onOffline(detail);
                        }
                    });
                }
            }

            @Override
            public void onMessage(final String topic, final byte[] payload) {
                // 注意：必须写 Session.this，否则这里会无限递归到监听器自己的 onMessage。
                sched.run(new Runnable() {
                    @Override
                    public void run() {
                        Session.this.onMessage(topic, payload);
                    }
                });
            }

            @Override
            public void onError(String detail) {
                lastError = detail == null ? "" : detail;
                fireStatus();
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 注入 App 桥（provider）。必须在 start() 之前调用才能走「凭证优先」路径。 */
    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    /** 当前是否持有设备凭证（持有则完全不需要账号密码/refresh token）。 */
    public boolean hasCredential() {
        return credential != null && !credential.isEmpty();
    }

    /** 配置重载：只换配置对象，连接由调用方 kick() 重新建立。 */
    public void setConfig(Config cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------ 生命周期

    public void start() {
        if (!cfg.hasServer() && !hasCredential() && !loadAuthorization()) {
            lastError = "还没授权：请在待办 App 的设置里启用输入法通道";
            log.w("session", lastError);
            fireStatus();
            return;
        }
        if (!cfg.autoConnect && !hasCredential()) {
            lastError = "auto_connect=false，等待手动连接";
            fireStatus();
            return;
        }
        running = true;
        log.i("session", "启动：server=" + cfg.server + " device=" + deviceId());
        sched.run(new Runnable() {
            @Override
            public void run() {
                attempt();
            }
        });
        sched.every(new Runnable() {
            @Override
            public void run() {
                if (running && online) pullNow("safety");
            }
        }, SAFETY_PULL_MS, SAFETY_PULL_MS);
    }

    public void stop() {
        running = false;
        online = false;
        cancelReconnect();
        try {
            transport.disconnect();
        } catch (Throwable ignored) {
        }
        store.put(K_CURSOR, Long.valueOf(cursor));
        store.saveIfDirty();
        fireStatus();
    }

    /** 立刻拉一次（面板按钮 / 手动同步）。 */
    public void syncNow() {
        sched.run(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    running = true;
                    attempt();
                    return;
                }
                pullNow("manual");
            }
        });
    }

    /** 立刻重连（切网、手动重连、面板按钮）；多次触发合并成一次。 */
    public void kick() {
        if (!running) {
            running = true;
        }
        reconnectDelay = 2000L;
        if (!kickPending.compareAndSet(false, true)) return;
        log.i("session", "手动重连");
        sched.run(new Runnable() {
            @Override
            public void run() {
                kickPending.set(false);
                try {
                    transport.disconnect();
                } catch (Throwable ignored) {
                }
                online = false;
                cancelReconnect();
                attempt();
            }
        });
    }

    /** 剪贴板上行：best-effort，失败不重试（服务端默认也不落库）。 */
    public void sendClip(final String text, final String fp) {
        if (!cfg.clipEnabled) return;
        if (text == null || text.isEmpty()) return;
        sched.run(new Runnable() {
            @Override
            public void run() {
                if (!ensureToken() || !ensureDevice()) {
                    log.w("session", "剪贴板上行跳过：还没登录/设备未就绪");
                    fireStatus();
                    return;
                }
                try {
                    // 优先用设备票据上行：服务端把票据里的设备当「来源」，不会回声给它。
                    // 用户 token 没有来源身份，只能退化成「不传 device_id」（服务端发给所有在线设备）。
                    HttpApi.Device dev = device;
                    Map<String, Object> out;
                    if (dev != null && dev.ticket != null && !dev.ticket.isEmpty()) {
                        out = api.clipAsDevice(dev.deviceId, dev.ticket, text, fp, null);
                    } else {
                        out = api.clip(access, null, text, fp, null);
                    }
                    log.i("session", "剪贴板上行 delivered=" + Json.lng(out, "delivered", 0)
                            + " stored=" + Json.bool(out, "stored", false));
                } catch (HttpApi.ApiException e) {
                    if (e.status == 401) {
                        access = null;
                        store.put(K_ACCESS, null);
                        store.saveIfDirty();
                    }
                    lastError = "剪贴板上行失败：" + e.getMessage();
                    log.w("session", lastError);
                }
            }
        });
    }

    /** 当前设备号（没注册成功时可能是空）。 */
    public String deviceIdText() {
        HttpApi.Device d = device;
        if (d != null) return d.deviceId;
        String id = deviceId();
        return id == null ? "" : id;
    }

    /** 自检：给自己推一条通知，验证「服务端 → nudge → pull → 通知」整条链。 */
    public void selfTestPush() {
        selfTestPush(7);
    }

    /** 自检推送：带等级，用来验证「等级只影响提醒方式，不影响送达」。 */
    public void selfTestPush(final int level) {
        sched.run(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ensureToken() || !ensureDevice()) {
                        fireStatus();
                        return;
                    }
                    Map<String, Object> payload = Json.obj(
                            "title", "dsh-kit 自检 L" + Levels.clamp(level),
                            "body", "收到这条说明 nudge → pull → 通知 都通了",
                            "level", Integer.valueOf(Levels.clamp(level)));
                    api.push(access, deviceId(), "notify", payload, null);
                    log.i("session", "自检推送已发出");
                } catch (HttpApi.ApiException e) {
                    lastError = "自检推送失败：" + e.getMessage();
                    fireStatus();
                }
            }
        });
    }

    // ------------------------------------------------------------ 连接

    private void attempt() {
        if (!running || online) return;
        if (!attempting.compareAndSet(false, true)) return;
        try {
            if (!ensureAuth()) {
                scheduleReconnect();
                return;
            }
            if (!ensureDevice()) {
                scheduleReconnect();
                return;
            }
            final HttpApi.Device d = device;
            if (d == null) {
                scheduleReconnect();
                return;
            }
            lastError = "";
            fireStatus();
            transport.connect(new Transport.Credentials() {
                @Override
                public String host() {
                    return d.host;
                }

                @Override
                public int port() {
                    return d.port;
                }

                @Override
                public boolean tls() {
                    return d.tls;
                }

                @Override
                public String clientId() {
                    return d.deviceId;
                }

                @Override
                public String username() {
                    return d.username;
                }

                @Override
                public String password() {
                    return d.ticket;
                }

                @Override
                public String topicIn() {
                    return d.topicIn;
                }

                @Override
                public int keepAlive() {
                    return cfg.keepAlive;
                }
            });
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.e("session/attempt", t);
            scheduleReconnect();
        } finally {
            attempting.set(false);
        }
    }

    private void onOnline() {
        if (!running) return;
        if (online) {
            // connect/disconnect 交错时 Paho 可能回调两次，第二次只记一行，别重复订阅/拉取
            log.i("session", "忽略重复的 MQTT 连接回调");
            return;
        }
        online = true;
        reconnectDelay = 2000L;
        lastError = "";
        log.i("session", "MQTT 已连接 " + (device == null ? "" : device.host + ":" + device.port));
        fireStatus();
        reportState(true, "");
        pullNow("connected");
    }

    private void onOffline(String detail) {
        if (!online && !running) return;
        online = false;
        if (detail != null && !detail.isEmpty()) lastError = detail;
        log.w("session", "MQTT 断开：" + detail);
        fireStatus();
        reportState(false, detail == null ? "" : detail);
        scheduleReconnect();
    }

    private void reportState(boolean connected, String error) {
        Authorizer a = authorizer;
        if (a == null) return;
        try {
            a.report(connected, error);
        } catch (Throwable t) {
            log.w("session", "回写通道状态失败：" + t.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!running || online) return;
        if (!reconnectPending.compareAndSet(false, true)) return;
        long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, 60_000L);
        log.i("session", "将在 " + delay + "ms 后重连");
        reconnectFuture = sched.delayFuture(new Runnable() {
            @Override
            public void run() {
                reconnectPending.set(false);
                attempt();
            }
        }, delay);
    }

    /** 作废排队中的退避重连（手动重连、停止时调用）。 */
    private void cancelReconnect() {
        ScheduledFuture<?> f = reconnectFuture;
        reconnectFuture = null;
        if (f != null) f.cancel(false);
        reconnectPending.set(false);
    }

    /**
     * 鉴权：优先设备凭证（无需账号密码）；否则回落到旧路径（refresh_token / 登录）。
     */
    private boolean ensureAuth() {
        if (hasCredential()) return true;
        if (loadAuthorization()) return true;
        return ensureToken();
    }

    /** 从 App（provider）取授权信息；成功返回 true。 */
    private boolean loadAuthorization() {
        Authorizer a = authorizer;
        if (a == null) return false;
        Map<String, Object> info;
        try {
            info = a.fetch();
        } catch (Throwable t) {
            lastError = "读取输入法授权失败：" + t.getMessage();
            log.w("session", lastError);
            return false;
        }
        if (info == null) {
            lastError = "待办 App 未授权输入法通道";
            log.w("session", lastError);
            return false;
        }
        String server = Json.str(info, "server", "");
        if (!server.isEmpty()) {
            api.setServer(server);
            store.put(K_SERVER, server);
        }
        String id = Json.str(info, "device_id", "");
        if (!id.isEmpty()) store.put(K_DEVICE_ID, id);
        String cred = Json.str(info, "credential", "");
        if (!cred.isEmpty()) {
            saveCredential(cred, Json.lng(info, "credential_expires_at", 0));
        }
        notificationsEnabled = Json.bool(info, "notifications", true);
        dndEnabled = Json.bool(info, "dnd_enabled", false);
        dndStart = Json.str(info, "dnd_start", "22:00");
        dndEnd = Json.str(info, "dnd_end", "08:00");
        reminderLead = Json.integer(info, "reminder_lead", 0);
        store.saveIfDirty();
        return hasCredential();
    }

    private void saveCredential(String value, long expiresAt) {
        credential = value;
        credentialExp = expiresAt;
        store.put(K_CREDENTIAL, value == null || value.isEmpty() ? null : Secret.seal(value));
        store.put(K_CREDENTIAL_EXP, Long.valueOf(expiresAt));
        store.saveIfDirty();
        Authorizer a = authorizer;
        if (a != null && value != null && !value.isEmpty()) {
            try {
                a.update(value, expiresAt);
            } catch (Throwable t) {
                log.w("session", "回写凭证失败：" + t.getMessage());
            }
        }
    }

    private void clearCredential() {
        credential = null;
        credentialExp = 0;
        store.put(K_CREDENTIAL, null);
        store.put(K_CREDENTIAL_EXP, null);
        store.saveIfDirty();
    }

    private boolean ensureToken() {
        long now = System.currentTimeMillis();
        if (access != null && accessExp - now > TOKEN_MARGIN_MS) return true;
        if (refreshToken != null) {
            try {
                HttpApi.Tokens t = api.refresh(refreshToken, deviceId());
                saveTokens(t);
                return true;
            } catch (HttpApi.ApiException e) {
                if (e.status == 401) {
                    refreshToken = null;
                    store.put(K_REFRESH, null);
                } else {
                    lastError = "换令牌失败：" + e.getMessage();
                    log.w("session", lastError);
                    return false;
                }
            }
        }
        if (cfg.autoLogin()) {
            try {
                HttpApi.Tokens t = api.login(cfg.username, cfg.password, deviceId());
                saveTokens(t);
                log.i("session", "登录成功：" + cfg.username);
                return true;
            } catch (HttpApi.ApiException e) {
                lastError = "登录失败：" + e.getMessage();
                log.w("session", lastError);
                return false;
            }
        }
        lastError = "没有可用凭据（config 里填 username/password 或先登录一次）";
        return false;
    }

    private void saveTokens(HttpApi.Tokens t) {
        access = t.access;
        accessExp = t.accessExpiresAt;
        refreshToken = t.refresh;
        store.put(K_ACCESS, t.access);
        store.put(K_ACCESS_EXP, Long.valueOf(t.accessExpiresAt));
        store.put(K_REFRESH, t.refresh);
        store.put(K_REFRESH_EXP, Long.valueOf(t.refreshExpiresAt));
        store.saveIfDirty();
    }

    private boolean ensureDevice() {
        HttpApi.Device d = device;
        long now = System.currentTimeMillis();
        if (d != null && d.ticketExpiresAt - now > TICKET_MARGIN_MS) return true;

        // 凭证优先：不需要账号密码，也不占用账号 refresh token。
        if (hasCredential()) {
            if (refreshWithCredential()) return true;
            if (!loadAuthorization() || !refreshWithCredential()) {
                lastError = "设备凭证已失效，请在待办 App 中重新授权输入法通道";
                log.w("session", lastError);
                return false;
            }
            return true;
        }

        try {
            d = api.registerDevice(access, deviceId(), cfg.deviceName, "ime");
            device = d;
            store.put(K_DEVICE_ID, d.deviceId);
            store.saveIfDirty();
            log.i("session", "设备就绪（账号票据）：" + d.deviceId + " broker=" + d.host + ":" + d.port);
            return true;
        } catch (HttpApi.ApiException e) {
            if (e.status == 401) {
                access = null;
                accessExp = 0;
                store.put(K_ACCESS, null);
                store.saveIfDirty();
            } else if (e.status == 404 || e.status == 409
                    || "device_revoked".equals(e.code) || "device_not_found".equals(e.code)) {
                // 旧设备被吊销/不属于本账号：换一个新的 device_id 再来
                log.w("session", "设备注册被拒(" + e.code + ")，换新 device_id");
                device = null;
                store.put(K_DEVICE_ID, null);
                store.saveIfDirty();
            }
            lastError = "设备注册失败：" + e.getMessage();
            log.w("session", lastError);
            return false;
        }
    }

    /** 用设备凭证换票据；成功则轮换并持久化新凭证。 */
    private boolean refreshWithCredential() {
        String id = deviceId();
        if (id == null || id.isEmpty()) {
            lastError = "缺少 device_id，无法续期";
            return false;
        }
        try {
            HttpApi.Credential c = api.refreshDevice(id, credential);
            saveCredential(c.credential, c.credentialExpiresAt);
            device = c.endpoint;
            store.put(K_DEVICE_ID, c.deviceId);
            store.saveIfDirty();
            log.i("session", "设备续期成功：" + c.deviceId + " broker="
                    + c.endpoint.host + ":" + c.endpoint.port);
            return true;
        } catch (HttpApi.ApiException e) {
            if (e.status == 401 || "invalid_credential".equals(e.code)) {
                clearCredential();
                notifyCredentialInvalid();
            }
            if (e.status == 409 || "device_revoked".equals(e.code)) {
                clearCredential();
                notifyCredentialInvalid();
                device = null;
                store.put(K_DEVICE_ID, null);
                store.saveIfDirty();
            }
            lastError = "设备续期失败：" + e.getMessage();
            log.w("session", lastError);
            return false;
        } catch (Throwable t) {
            lastError = "设备续期异常：" + t.getMessage();
            log.e("session/credential", t);
            return false;
        }
    }

    /** 告诉 App 本机凭证已失效：App 清掉映射，下次 dsh_authorize 重新签发。 */
    private void notifyCredentialInvalid() {
        Authorizer a = authorizer;
        if (a == null) return;
        try {
            a.update("", 0L);
            log.i("session", "已通知 App 凭证失效，等待重新授权");
        } catch (Throwable t) {
            log.w("session", "通知 App 凭证失效失败：" + t.getMessage());
        }
    }

    private String deviceId() {
        String id = store.getString(K_DEVICE_ID, null);
        if (id != null && !id.isEmpty()) return id;
        if (cfg.deviceId != null && !cfg.deviceId.isEmpty()) return cfg.deviceId;
        return null; // 交给服务端生成，注册成功后落库
    }

    // ------------------------------------------------------------ 入站消息

    private void onMessage(String topic, byte[] payload) {
        if (payload == null) return;
        log.i("session", "入站消息 " + payload.length + "B topic=" + topic);
        String raw;
        try {
            raw = new String(payload, "UTF-8");
        } catch (Throwable t) {
            return;
        }
        Protocol.Nudge nudge = Protocol.parseNudge(raw);
        if (nudge != null) {
            lastNudgeAt = System.currentTimeMillis();
            log.i("session", "收到 nudge " + nudge);
            Authorizer a = authorizer;
            if (a != null) {
                try {
                    a.nudge();
                } catch (Throwable t) {
                    log.w("session", "唤醒 App 失败：" + t.getMessage());
                }
            }
            pullNow("nudge");
            return;
        }
        // 数据变更信号：插件自己不跑 op 同步，转给 App 让它立即 pull。
        if (raw.indexOf("\"t\":\"sync\"") >= 0) {
            log.i("session", "收到数据变更信号，转给 App");
            Authorizer a = authorizer;
            if (a != null) {
                try {
                    a.nudge();
                } catch (Throwable t) {
                    log.w("session", "转发同步信号失败：" + t.getMessage());
                }
            }
            return;
        }
        Protocol.Clip clip = Protocol.parseClip(raw);
        if (clip != null) {
            boolean ok = false;
            try {
                ok = applier != null && applier.apply("clip", clip.id, clip.payloadJson, true);
            } catch (Throwable t) {
                log.e("session/clip", t);
            }
            log.i("session", "直传剪贴板 " + (ok ? "已应用" : "未处理") + " id=" + clip.id);
            return;
        }
        log.w("session", "未知消息，忽略（" + raw.length() + "B）");
    }

    private void pullNow(String reason) {
        if (pulling) {
            log.i("session", "pull(" + reason + ") 跳过：上一轮还在跑");
            return;
        }
        if (device == null) {
            log.w("session", "pull(" + reason + ") 跳过：设备未就绪");
            return;
        }
        log.i("session", "pull(" + reason + ") since=" + cursor);
        pulling = true;
        try {
            boolean deviceTicket = device.ticket != null && !device.ticket.isEmpty();
            if (!deviceTicket && !ensureToken()) return;
            for (int page = 0; page < MAX_PAGES; page++) {
                HttpApi.Page result;
                try {
                    result = deviceTicket
                            ? api.pullAsDevice(device.deviceId, device.ticket, cursor, PAGE_SIZE)
                            : api.pull(access, device.deviceId, cursor, PAGE_SIZE);
                } catch (HttpApi.ApiException e) {
                    if (e.status != 401) throw e;
                    if (deviceTicket) {
                        // 设备票据过期：下一轮 attempt 会用凭证换新票据。
                        device = null;
                        throw e;
                    }
                    access = null; // 令牌过期：换一张再来一次
                    if (!ensureToken()) throw e;
                    result = api.pull(access, device.deviceId, cursor, PAGE_SIZE);
                }
                if (result.messages.isEmpty()) {
                    // 服务端库重建/重编号后 seq 会回退：游标比最新 seq 还大就永远拉不到新消息，
                    // 重置一次从头补（服务端对已 ack 的 msg_id 幂等）。
                    if (result.latestSeq > 0 && result.latestSeq < cursor) {
                        log.w("session", "服务端消息 seq 回退（latest=" + result.latestSeq
                                + " < 游标 " + cursor + "），重置游标");
                        resetCursor();
                        continue;
                    }
                    if (result.nextSeq > cursor) setCursor(result.nextSeq);
                    break;
                }
                List<Long> ackSeqs = new ArrayList<Long>();
                long maxSeq = cursor;
                for (HttpApi.Message m : result.messages) {
                    boolean applied = false;
                    try {
                        applied = applier == null || applier.apply(m.kind, m.msgId, m.payloadJson, false);
                    } catch (Throwable t) {
                        log.e("session/apply", t);
                    }
                    if (m.seq > maxSeq) maxSeq = m.seq;
                    if (applied) {
                        ackSeqs.add(Long.valueOf(m.seq));
                    }
                }
                pulledCount += result.messages.size();
                // 顺序：先 ack 再推进游标。ack 幂等，崩在中间最多重复投递，不会丢。
                if (!ackSeqs.isEmpty()) {
                    ackedCount += deviceTicket
                            ? api.ackAsDevice(device.deviceId, device.ticket, ackSeqs)
                            : api.ack(access, device.deviceId, ackSeqs);
                }
                setCursor(Math.max(result.nextSeq, maxSeq));
                if (!result.hasMore) break;
            }
            lastPullAt = System.currentTimeMillis();
            log.i("session", "pull 完成 cursor=" + cursor + " 累计拉取=" + pulledCount
                    + " 确认=" + ackedCount);
        } catch (HttpApi.ApiException e) {
            if (e.status == 401) {
                if (device != null && device.ticket != null && !device.ticket.isEmpty()) {
                    device = null; // 设备票据过期，下一轮换新票
                } else {
                    access = null;
                    store.put(K_ACCESS, null);
                    store.saveIfDirty();
                }
            }
            lastError = "拉取失败：" + e.getMessage();
            log.w("session", "pull(" + reason + ") " + lastError);
            // 0/5xx 是网络或服务端抖动；401 是账号/设备票据过期——都要断开重连，
            // 由 attempt() 里的 ensureDevice 换新票。只记错误不重连会让通道永久卡住。
            if (online && (e.status == 0 || e.status >= 500 || e.status == 401)) {
                try {
                    transport.disconnect();
                } catch (Throwable ignored) {
                }
                online = false;
                scheduleReconnect();
            }
        } catch (Throwable t) {
            lastError = "拉取异常：" + t.getMessage();
            log.e("session/pull", t);
        } finally {
            pulling = false;
            fireStatus();
        }
    }

    private void setCursor(long value) {
        if (value <= cursor) return;
        cursor = value;
        store.put(K_CURSOR, Long.valueOf(value));
        store.saveIfDirty();
    }

    /** 服务端 seq 回退时强行把游标打回 0（setCursor 只会前进）。 */
    private void resetCursor() {
        cursor = 0;
        store.put(K_CURSOR, Long.valueOf(0));
        store.saveIfDirty();
    }

    // ------------------------------------------------------------ 状态

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("state", !running ? "stopped" : (online ? "online" : "offline"));
        out.put("server", api.server().isEmpty() ? cfg.server : api.server());
        out.put("username", cfg.username);
        out.put("device_id", device == null ? (deviceId() == null ? "" : deviceId()) : device.deviceId);
        out.put("broker", device == null ? "" : (device.host + ":" + device.port));
        out.put("topic_in", device == null ? "" : device.topicIn);
        out.put("cursor", Long.valueOf(cursor));
        out.put("pulled", Long.valueOf(pulledCount));
        out.put("applied", Long.valueOf(appliedCount));
        out.put("acked", Long.valueOf(ackedCount));
        out.put("last_nudge_at", Long.valueOf(lastNudgeAt));
        out.put("last_pull_at", Long.valueOf(lastPullAt));
        out.put("last_error", lastError);
        out.put("ticket_expires_at", Long.valueOf(device == null ? 0 : device.ticketExpiresAt));
        out.put("auth", hasCredential() ? "credential" : "token");
        out.put("credential_expires_at", Long.valueOf(credentialExp));
        out.put("im_authorized", Boolean.valueOf(hasCredential()));
        return out;
    }

    /** App 的设置快照（插件兜底时用）。 */
    public boolean notificationsEnabled() {
        return notificationsEnabled;
    }

    public boolean dndEnabled() {
        return dndEnabled;
    }

    public int reminderLead() {
        return reminderLead;
    }

    /** 免打扰判定（支持跨天）。 */
    public boolean inDnd(long nowMs) {
        if (!dndEnabled) return false;
        int start = minutesOfDay(dndStart);
        int end = minutesOfDay(dndEnd);
        if (start < 0 || end < 0 || start == end) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return start < end ? (now >= start && now < end) : (now >= start || now < end);
    }

    private static int minutesOfDay(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable t) {
            return -1;
        }
    }

    public void noteApplied() {
        appliedCount++;
    }

    public Config config() {
        return cfg;
    }

    public String topicIn() {
        HttpApi.Device d = device;
        return d == null ? "" : d.topicIn;
    }

    private void fireStatus() {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onSessionChanged(status());
        } catch (Throwable t) {
            log.e("session/listener", t);
        }
    }
}
