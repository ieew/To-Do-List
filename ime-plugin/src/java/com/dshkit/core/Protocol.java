package com.dshkit.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线上的协议细节（服务端契约见 `To-Do-List/docs/server-protocol.md` 第十五节）。
 *
 * 这里只做「解析/构造」，不碰网络、不碰 Android —— 可单测。
 */
public final class Protocol {

    public static final int VERSION = 1;

    /** MQTT 主题前缀：dsh/v1/{uid}/dev/{deviceId}/in|out */
    public static final String TOPIC_PREFIX = "dsh/v1";

    public static final class Nudge {
        public final long seq;
        public final int pending;

        public Nudge(long seq, int pending) {
            this.seq = seq;
            this.pending = pending;
        }

        @Override
        public String toString() {
            return "nudge(seq=" + seq + ",n=" + pending + ")";
        }
    }

    public static final class Clip {
        public final String id;
        public final String payloadJson;
        /** 发送端算的指纹，用于防回流/去重（可能为空）。 */
        public final String fp;

        public Clip(String id, String payloadJson, String fp) {
            this.id = id;
            this.payloadJson = payloadJson;
            this.fp = fp == null ? "" : fp;
        }
    }

    private Protocol() {
    }

    /** 服务端下发：`{"t":"nudge","seq":1024,"n":3}`；不认识就返回 null。 */
    public static Nudge parseNudge(String raw) {
        try {
            Map<String, Object> o = Json.asObject(Json.parse(raw));
            if (!"nudge".equals(Json.str(o, "t", ""))) return null;
            return new Nudge(Json.lng(o, "seq", 0), Json.integer(o, "n", 0));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 剪贴板直传：`{"t":"clip","id":"msg-…","p":<任意 JSON>}`。 */
    public static Clip parseClip(String raw) {
        try {
            Map<String, Object> o = Json.asObject(Json.parse(raw));
            if (!"clip".equals(Json.str(o, "t", ""))) return null;
            // 统一成 JSON 文本，和 pull 回来的 payloadJson 形状保持一致
            String payload = Json.write(o.get("p"));
            return new Clip(Json.str(o, "id", ""), payload, Json.str(o, "fp", ""));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 剪贴板消息里的正文：payload 可能是 `"文本"`、`{"text":…}` 或 `{"p":…}`。 */
    public static String clipText(String payloadJson) {
        if (payloadJson == null) return "";
        String trimmed = payloadJson.trim();
        if (trimmed.isEmpty()) return "";
        try {
            Object parsed = Json.parse(trimmed);
            if (parsed instanceof String) return (String) parsed;
            if (parsed instanceof Map) {
                Map<String, Object> map = Json.asObject(parsed);
                String text = Json.str(map, "text", "");
                if (!text.isEmpty()) return text;
                Object p = map.get("p");
                if (p instanceof String) return (String) p;
                if (p != null) return Json.write(p);
            }
            return Json.write(parsed);
        } catch (Throwable t) {
            return trimmed;
        }
    }

    /** 设备上报信封：`{"v":1,"id":…,"t":…,"ts":…,"ack":…,"p":{}}`。 */
    public static String envelope(String type, String msgId, long ts, boolean ack, Object payload) {
        Map<String, Object> env = new LinkedHashMap<String, Object>();
        env.put("v", VERSION);
        env.put("id", msgId);
        env.put("t", type);
        env.put("ts", ts);
        env.put("ack", ack);
        env.put("p", payload == null ? Json.obj() : payload);
        return Json.write(env);
    }

    public static String topicIn(String userId, String deviceId) {
        return TOPIC_PREFIX + "/" + userId + "/dev/" + deviceId + "/in";
    }

    public static String topicOut(String userId, String deviceId) {
        return TOPIC_PREFIX + "/" + userId + "/dev/" + deviceId + "/out";
    }

    /** 从入站主题里取 user_id；格式不对返回 null。 */
    public static String userIdOfTopic(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        if (parts.length != 6) return null;
        if (!"dsh".equals(parts[0]) || !"v1".equals(parts[1]) || !"dev".equals(parts[3])) {
            return null;
        }
        return parts[2];
    }

    /** 去重键：同一设备同一条消息只处理一次。 */
    public static String dedupeKey(String deviceId, String msgId) {
        return (deviceId == null ? "" : deviceId) + ":" + (msgId == null ? "" : msgId);
    }
}
