package com.dshkit.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端 HTTP 客户端（纯 `java.net`，不用 okhttp，方便在电脑上跑同一套代码）。
 *
 * 只封装 `/v1/auth/*` 与 `/v1/dsh/*` 这几条；所有请求带超时，失败一律抛
 * {@link ApiException}（含 HTTP 状态与业务 code），由 Session 决定重试/降级。
 */
public final class HttpApi {

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        ApiException(int status, String code, String message) {
            super(message == null ? ("HTTP " + status) : message);
            this.status = status;
            this.code = code == null ? "" : code;
        }

        public boolean retryable() {
            return status == 0 || status == 429 || status >= 500;
        }
    }

    public static final class Tokens {
        public final String access;
        public final long accessExpiresAt;
        public final String refresh;
        public final long refreshExpiresAt;

        Tokens(String access, long accessExpiresAt, String refresh, long refreshExpiresAt) {
            this.access = access;
            this.accessExpiresAt = accessExpiresAt;
            this.refresh = refresh;
            this.refreshExpiresAt = refreshExpiresAt;
        }
    }

    public static final class Device {
        public final String deviceId;
        public final String ticket;
        public final long ticketExpiresAt;
        public final String host;
        public final int port;
        public final boolean tls;
        public final String username;
        public final String topicIn;
        public final String topicOut;

        Device(String deviceId, String ticket, long ticketExpiresAt, String host, int port,
               boolean tls, String username, String topicIn, String topicOut) {
            this.deviceId = deviceId;
            this.ticket = ticket;
            this.ticketExpiresAt = ticketExpiresAt;
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.username = username;
            this.topicIn = topicIn;
            this.topicOut = topicOut;
        }
    }

    public static final class Message {
        public final long seq;
        public final String msgId;
        public final String kind;
        public final String payloadJson;
        public final long createdAt;

        Message(long seq, String msgId, String kind, String payloadJson, long createdAt) {
            this.seq = seq;
            this.msgId = msgId;
            this.kind = kind;
            this.payloadJson = payloadJson;
            this.createdAt = createdAt;
        }
    }

    public static final class Page {
        public final List<Message> messages;
        public final long nextSeq;
        public final boolean hasMore;
        public final long latestSeq;

        Page(List<Message> messages, long nextSeq, boolean hasMore, long latestSeq) {
            this.messages = messages;
            this.nextSeq = nextSeq;
            this.hasMore = hasMore;
            this.latestSeq = latestSeq;
        }
    }

    private final Log log;
    private volatile String server = "";

    public HttpApi(Log log) {
        this.log = log;
    }

    public void setServer(String base) {
        this.server = base == null ? "" : base.trim();
    }

    public String server() {
        return server;
    }

    // ------------------------------------------------------------ 接口

    public Tokens login(String username, String password, String deviceId) throws ApiException {
        Map<String, Object> body = Json.obj(
                "username", username,
                "password", password,
                "device_id", deviceId);
        Map<String, Object> out = post("/v1/auth/login", body, null);
        return tokens(out);
    }

    public Tokens refresh(String refreshToken, String deviceId) throws ApiException {
        Map<String, Object> out = post("/v1/auth/refresh",
                Json.obj("refresh_token", refreshToken, "device_id", deviceId), null);
        return tokens(out);
    }

    public Device registerDevice(String accessToken, String deviceId, String name, String platform)
            throws ApiException {
        Map<String, Object> body = Json.obj("name", name, "platform", platform);
        if (deviceId != null && !deviceId.isEmpty()) body.put("device_id", deviceId);
        Map<String, Object> out = post("/v1/dsh/devices/register", body, accessToken);
        Map<String, Object> mqtt = Json.asObject(out.get("mqtt"));
        String host = Json.str(mqtt, "host", "");
        int port = Json.integer(mqtt, "port", 1883);
        if (host.isEmpty()) throw new ApiException(200, "bad_endpoint", "服务端没下发 broker 地址");
        return new Device(
                Json.str(out, "device_id", deviceId),
                Json.str(out, "ticket", ""),
                Json.lng(out, "ticket_expires_at", 0),
                host,
                port,
                Json.bool(mqtt, "tls", false),
                Json.str(mqtt, "username", deviceId),
                Json.str(mqtt, "topic_in", ""),
                Json.str(mqtt, "topic_out", ""));
    }

    public Page pull(String accessToken, String deviceId, long since, int limit)
            throws ApiException {
        Map<String, Object> out = get("/v1/dsh/pull?device_id=" + enc(deviceId)
                + "&since=" + since + "&limit=" + limit, accessToken);
        List<Message> messages = new ArrayList<Message>();
        for (Object item : Json.asArray(out.get("messages"))) {
            Map<String, Object> m = Json.asObject(item);
            messages.add(new Message(
                    Json.lng(m, "seq", 0),
                    Json.str(m, "msg_id", ""),
                    Json.str(m, "kind", ""),
                    Json.write(m.get("payload")),
                    Json.lng(m, "created_at", 0)));
        }
        return new Page(messages,
                Json.lng(out, "next_seq", since),
                Json.bool(out, "has_more", false),
                Json.lng(out, "latest_seq", 0));
    }

    public int ack(String accessToken, String deviceId, List<Long> seqs) throws ApiException {
        Map<String, Object> out = post("/v1/dsh/ack",
                Json.obj("device_id", deviceId, "seqs", seqs), accessToken);
        return Json.integer(out, "acked", 0);
    }

    /** 剪贴板上行：默认服务端只实时转发给同用户的其他设备。 */
    public Map<String, Object> clip(String accessToken, String deviceId, String text, String fp,
                                    String msgId) throws ApiException {
        Map<String, Object> body = Json.obj("text", text, "fp", fp);
        if (deviceId != null && !deviceId.isEmpty()) body.put("device_id", deviceId);
        if (msgId != null && !msgId.isEmpty()) body.put("msg_id", msgId);
        return post("/v1/dsh/clip", body, accessToken);
    }

    /**
     * 剪贴板上行（设备票据）：服务端把票据里的设备当来源，天然不给它回声。
     * 用户 token 没有来源身份，所以走这条路才对。
     */
    public Map<String, Object> clipAsDevice(String deviceId, String ticket, String text, String fp,
                                            String msgId) throws ApiException {
        Map<String, Object> body = Json.obj("text", text, "fp", fp);
        if (msgId != null && !msgId.isEmpty()) body.put("msg_id", msgId);
        return request("POST", "/v1/dsh/clip", null, Json.write(body), deviceId, ticket);
    }

    /** 自检用：给自己推一条（面板上的「测试通知」走这里）。 */
    public Map<String, Object> push(String accessToken, String deviceId, String kind,
                                    Object payload, String msgId) throws ApiException {
        Map<String, Object> body = Json.obj("kind", kind, "payload", payload);
        if (deviceId != null && !deviceId.isEmpty()) body.put("device_id", deviceId);
        if (msgId != null && !msgId.isEmpty()) body.put("msg_id", msgId);
        return post("/v1/dsh/push", body, accessToken);
    }

    // ------------------------------------------------------------ 管道

    private Tokens tokens(Map<String, Object> out) throws ApiException {
        Map<String, Object> t = Json.asObject(out.get("tokens"));
        String access = Json.str(t, "access_token", "");
        String refresh = Json.str(t, "refresh_token", "");
        if (access.isEmpty()) throw new ApiException(200, "bad_tokens", "服务端没返回访问令牌");
        return new Tokens(access, Json.lng(t, "access_expires_at", 0),
                refresh, Json.lng(t, "refresh_expires_at", 0));
    }

    private Map<String, Object> post(String path, Map<String, Object> body, String token)
            throws ApiException {
        return request("POST", path, token, Json.write(body), null, null);
    }

    private Map<String, Object> get(String path, String token) throws ApiException {
        return request("GET", path, token, null, null, null);
    }

    private Map<String, Object> request(String method, String path, String token, String body)
            throws ApiException {
        return request(method, path, token, body, null, null);
    }

    /** 带设备票据的请求；服务端据此把该设备当来源（剪贴板据此排除自己）。 */
    private Map<String, Object> request(String method, String path, String token, String body,
                                        String deviceId, String ticket) throws ApiException {
        if (!server.startsWith("http")) {
            throw new ApiException(0, "no_server", "没有配置服务端地址");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(server + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (deviceId != null && !deviceId.isEmpty() && ticket != null && !ticket.isEmpty()) {
                conn.setRequestProperty("X-Dsh-Device-Id", deviceId);
                conn.setRequestProperty("X-Dsh-Ticket", ticket);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] raw = body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(raw.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(raw);
                } finally {
                    os.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            if (status >= 400) {
                throw errorFrom(status, text);
            }
            if (text == null || text.trim().isEmpty()) {
                return new LinkedHashMap<String, Object>();
            }
            return Json.asObject(Json.parse(text));
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            if (log != null) log.w("http", method + " " + path + " 失败: " + t);
            throw new ApiException(0, "network", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiException errorFrom(int status, String text) {
        String code = "";
        String message = "HTTP " + status;
        try {
            Map<String, Object> err = Json.asObject(Json.asObject(Json.parse(text)).get("error"));
            code = Json.str(err, "code", "");
            String m = Json.str(err, "message", "");
            if (!m.isEmpty()) message = m;
        } catch (Throwable ignored) {
            // 非 JSON 错误体就用状态码
        }
        return new ApiException(status, code, message);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
